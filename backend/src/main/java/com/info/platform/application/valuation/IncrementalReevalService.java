package com.info.platform.application.valuation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.jobrun.RunningJobIndicator;
import com.info.platform.application.markettop.IncrementalTopService;
import com.info.platform.application.valuation.FactorSnapshotService.IncrementalReport;
import com.info.platform.domain.markettop.MarketTopRepository;
import com.info.platform.domain.markettop.SqueezeJudge;
import com.info.platform.domain.markettop.SqueezeJudge.Affected;
import com.info.platform.domain.markettop.SqueezeJudge.Candidate;
import com.info.platform.domain.markettop.SqueezeJudge.Verdict;
import com.info.platform.domain.valuation.FactorSnapshotRepository;
import com.info.platform.domain.valuation.FactorSnapshotRepository.PoolRow;
import com.info.platform.domain.valuation.FactorSnapshotRepository.SubjectRef;
import com.info.platform.domain.valuation.IncrementalReevalRepository;
import com.info.platform.domain.valuation.IncrementalReevalRepository.PendingLink;
import com.info.platform.domain.valuation.IncrementalReevalRepository.ReevalEvent;
import com.info.platform.domain.valuation.IndustryAssociator.MemberLink;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 增量重评编排（应用层，M22 T190，方案 §4.3.1 tick 状态机 + ADR-0061 裁决 1/2）：扫未消费 HIGH 事件（LEFT JOIN 判重 + 落库缓冲 +
 * 补跑窗）→ 受影响标的集（subjects ∪ IndustryAssociator 路 C 成员边投影——与 F2 同源非第二套关联）→ 当日快照行局部重算（同投影同纯函数 零漂移）→
 * 挤入挤出判定（SqueezeJudge 纯函数）→ 留痕状态机。
 *
 * <p><b>互斥层②让路</b>：tick 入口查 {@code isRunning(FACTOR_SNAPSHOT ‖ MARKET_TOP_JOB)} → 本轮整体推迟（事件不动，60s
 * 后重扫）—— 17:30/18:00 重窗内增量读到半量行情/maxVersion 旧值的竞态由让路消除，M20/M21 Job 类零改动。
 *
 * <p><b>防抖红线</b>：无新事件且无挂起轮 → 空转直返（零重算零版本）；判定未过阈 → NO_LINK 终态不重排。状态机 {@code SCANNED → RECOMPUTED →
 * {NO_LINK | DEFERRED → LINKED | LINKED}}，FAILED 旁路（整轮异常不上抛——ManagedJob 惯例，次日全量自然修复）。
 */
@Service
public class IncrementalReevalService {

    /** 单轮扫描上限（防御性；HIGH 日增个位数，24h 窗内未消费量有界）。 */
    public static final int SCAN_CAP = 200;

    /** 让路查询的重 Job 键（互斥层②，ADR-0061 裁决 3）。 */
    static final String HEAVY_JOB_SNAPSHOT = "FACTOR_SNAPSHOT";

    static final String HEAVY_JOB_MARKET_TOP = "MARKET_TOP_JOB";

    private static final Logger log = LoggerFactory.getLogger(IncrementalReevalService.class);

    private final IncrementalReevalRepository logRepository;

    private final FactorSnapshotRepository snapshotRepository;

    private final FactorSnapshotService factorSnapshotService;

    private final MarketTopRepository marketTopRepository;

    private final IncrementalTopService incrementalTopService;

    private final IncrementalReevalSettings settings;

    private final RunningJobIndicator runningIndicator;

    private final ObjectMapper objectMapper;

    private final Clock clock;

    public IncrementalReevalService(
            IncrementalReevalRepository logRepository,
            FactorSnapshotRepository snapshotRepository,
            FactorSnapshotService factorSnapshotService,
            MarketTopRepository marketTopRepository,
            IncrementalTopService incrementalTopService,
            IncrementalReevalSettings settings,
            RunningJobIndicator runningIndicator,
            ObjectMapper objectMapper,
            Clock clock) {
        this.logRepository = logRepository;
        this.snapshotRepository = snapshotRepository;
        this.factorSnapshotService = factorSnapshotService;
        this.marketTopRepository = marketTopRepository;
        this.incrementalTopService = incrementalTopService;
        this.settings = settings;
        this.runningIndicator = runningIndicator;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 执行一轮增量重评（整轮异常不上抛——ManagedJob 惯例）。
     *
     * @return tick 报告（JobRunStats 留痕：{@code incr=scan:n;recompute=rows:x;judge=pass|nopass;link=…}）
     */
    public ReevalReport tick() {
        Instant now = clock.instant();
        if (runningIndicator.isRunning(HEAVY_JOB_SNAPSHOT)
                || runningIndicator.isRunning(HEAVY_JOB_MARKET_TOP)) {
            // 互斥层②：重 Job 运行 → 本轮整体推迟（事件不动，下轮重扫）
            return new ReevalReport("incr=defer=heavy_running", 0);
        }
        IncrementalReevalConfig config = settings.current();
        Instant createdBefore = now.minus(Duration.ofSeconds(config.eventBufferSeconds()));
        Instant createdSince = now.minus(Duration.ofHours(config.scanWindowHours()));
        List<ReevalEvent> events =
                logRepository.findUnconsumedEvents(
                        config.minImportance(), createdBefore, createdSince, SCAN_CAP);
        List<PendingLink> pending = logRepository.findPendingLink();
        if (events.isEmpty() && pending.isEmpty()) {
            // 空转防抖：无输入零重算零版本（防抖红线，故事 5 场景 4）
            return new ReevalReport("incr=idle", 0);
        }
        try {
            return processRound(events, pending, config, now);
        } catch (RuntimeException e) {
            log.error("增量重评轮失败（事件标 FAILED，次日全量自愈）: {}", e.toString(), e);
            markRoundFailed(events, pending, e);
            return new ReevalReport("incr=failed=" + head(e.toString(), 120), events.size());
        }
    }

    /** 一轮处理：落 SCANNED → 受影响集 → 前分读取 → 重算 → 判定 → 留痕（联动段 T191 接线）。 */
    private ReevalReport processRound(
            List<ReevalEvent> events,
            List<PendingLink> pending,
            IncrementalReevalConfig config,
            Instant now) {
        String nowIso = now.toString();
        for (ReevalEvent event : events) {
            logRepository.insertScanned(event, nowIso); // UNIQUE(event_id) 幂等
        }
        LocalDate today = now.atZone(FactorSnapshotService.SNAPSHOT_ZONE).toLocalDate();
        String dateText = today.toString();
        Set<Long> affectedIds = affectedSubjectIds(events);
        for (PendingLink link : pending) {
            affectedIds.addAll(subjectIdsOf(link.subjectsJson())); // 挂起轮标的并入判定集
        }

        // 前分留痕（重算前读当日行原值）→ 重算（挂起轮数据已就位不重算）
        Map<Long, Double> before = beforeScores(dateText, affectedIds);
        String snapshotAt = null;
        if (!events.isEmpty()) {
            IncrementalReport report =
                    factorSnapshotService.recomputeIncremental(today, affectedIds);
            snapshotAt = report.incrementAtIso();
        }

        // 判定输入：重算后的当日快照行现读（after 分与 SqueezeJudge 输入同源）
        List<PoolRow> poolRows = poolRowsOf(dateText, affectedIds);
        Verdict verdict =
                SqueezeJudge.judge(
                        currentTopCandidates(dateText),
                        affectedCandidates(poolRows, affectedIds),
                        config.minScoreGap());
        List<Long> roundEventIds = roundEventIds(events, pending);
        logRepository.markRecomputed(
                roundEventIds,
                subjectsJson(poolRows, before),
                snapshotAt,
                verdict.passed(),
                nowIso);
        if (!verdict.passed()) {
            // 防抖红线：不过阈不重排——NO_LINK 终态
            logRepository.markStatus(
                    roundEventIds, IncrementalReevalRepository.STATUS_NO_LINK, nowIso);
            return new ReevalReport(
                    "incr=scan:"
                            + events.size()
                            + ";recompute=rows:"
                            + affectedIds.size()
                            + ";judge=nopass",
                    roundEventIds.size());
        }
        // 联动防抖：距上一 EVENT 版本 linkMinIntervalMinutes 内 → 本轮 DEFERRED，留痕挂起下轮 tick 自动重试
        // （事件标的分已在详情页可见，仅榜单延后——最坏 +10min 仍在 P90 红线内，方案 §3.3-5）
        Optional<String> lastEventVersionAt = logRepository.findLastEventVersionAt(dateText);
        if (deferForInterval(lastEventVersionAt, config, now)) {
            logRepository.markStatus(
                    roundEventIds, IncrementalReevalRepository.STATUS_DEFERRED, nowIso);
            return new ReevalReport(
                    "incr=scan:"
                            + events.size()
                            + ";recompute=rows:"
                            + affectedIds.size()
                            + ";judge=pass;defer=interval",
                    roundEventIds.size());
        }
        // 联动收口：version+1(EVENT 归因) + 冲突重试在仓储层；留痕 LINKED 终态（时效口径终点 top_version_at）
        int topVersion = incrementalTopService.link(today, verdict, roundEvents(events, pending));
        String topVersionAt = clock.instant().toString();
        logRepository.markLinked(roundEventIds, topVersion, topVersionAt, nowIso);
        return new ReevalReport(
                "incr=scan:"
                        + events.size()
                        + ";recompute=rows:"
                        + affectedIds.size()
                        + ";judge=pass;link=v:"
                        + topVersion,
                roundEventIds.size());
    }

    private boolean deferForInterval(
            Optional<String> lastEventVersionAt, IncrementalReevalConfig config, Instant now) {
        if (lastEventVersionAt.isEmpty()) {
            return false;
        }
        try {
            Instant lastAt = Instant.parse(lastEventVersionAt.get());
            return now.isBefore(lastAt.plus(Duration.ofMinutes(config.linkMinIntervalMinutes())));
        } catch (RuntimeException e) {
            log.warn("上一 EVENT 版本时刻解析失败（不防抖直接联动）: {}", lastEventVersionAt.get());
            return false;
        }
    }

    /** 联动归因事件集（本轮新扫描 + 挂起轮重试事件——版本级归因口径）。 */
    private List<ReevalEvent> roundEvents(List<ReevalEvent> events, List<PendingLink> pending) {
        List<ReevalEvent> round = new ArrayList<>(events);
        // 挂起轮事件仅携带 id/subjects_json——归因摘要缺失时以留痕 id 兜底（eventId 仍是下钻主键）
        for (PendingLink link : pending) {
            round.add(new ReevalEvent(link.eventId(), null, null, null, List.of(), List.of()));
        }
        return round;
    }

    // ---- 判定段（SqueezeJudge 纯函数输入装配） ----

    /** 判定基准 = 当日最新版本 Top10（当日无版本回落最新有榜日——用户可见榜单，方案 §3.3-1）。 */
    private List<Candidate> currentTopCandidates(String dateText) {
        return marketTopRepository
                .findLatest(dateText, com.info.platform.domain.aggregation.Market.A_SHARE)
                .or(
                        () ->
                                marketTopRepository.findLatestAnyDate(
                                        com.info.platform.domain.aggregation.Market.A_SHARE))
                .map(
                        version ->
                                version.items().stream()
                                        .map(
                                                item ->
                                                        new Candidate(
                                                                item.subjectId(),
                                                                item.subjectCode(),
                                                                item.subjectName(),
                                                                item.finalScore()))
                                        .toList())
                .orElse(List.of());
    }

    /** 受影响标的判定输入（final 口径 = total——深析幅面在联动段继承时合成，方案 §4.3.2 注记）。 */
    private List<Affected> affectedCandidates(List<PoolRow> poolRows, Set<Long> affectedIds) {
        List<Affected> affected = new ArrayList<>();
        for (PoolRow row : poolRows) {
            if (affectedIds.contains(row.subjectId())) {
                affected.add(
                        new Affected(
                                row.subjectId(),
                                row.subjectCode(),
                                row.subjectName(),
                                row.totalScore(),
                                row.fCatalyst(),
                                row.fConduction()));
            }
        }
        return affected;
    }

    /** 当日快照行中受影响标的的行（重算后现读 = after 分；无行标的不进判定集）。 */
    private List<PoolRow> poolRowsOf(String dateText, Set<Long> affectedIds) {
        if (affectedIds.isEmpty()) {
            return List.of();
        }
        List<PoolRow> rows = new ArrayList<>();
        for (PoolRow row : snapshotRepository.findPoolRowsByDate(dateText)) {
            if (affectedIds.contains(row.subjectId())) {
                rows.add(row);
            }
        }
        return rows;
    }

    // ---- 受影响标的集：subjects ∪ IndustryAssociator 路 C 投影（与 F2 同源） ----

    private Set<Long> affectedSubjectIds(List<ReevalEvent> events) {
        Set<Long> ids = new LinkedHashSet<>();
        if (events.isEmpty()) {
            return ids;
        }
        Map<String, Long> idByCode = new HashMap<>();
        for (SubjectRef subject : snapshotRepository.findActiveSubjects()) {
            idByCode.put(subject.code(), subject.id());
        }
        Set<String> affectedIndustries = new LinkedHashSet<>();
        for (ReevalEvent event : events) {
            for (String code : event.subjectCodes()) {
                Long id = idByCode.get(code);
                if (id != null) {
                    ids.add(id); // 池内代码标的（池外代码不入受影响集）
                }
            }
            affectedIndustries.addAll(event.affectedIndustries());
        }
        if (!affectedIndustries.isEmpty()) {
            // 路 C 同源投影：同一 memberLinks 转换（swPrimaryOf 映射 + 未收录过滤）——非第二套关联
            for (MemberLink link :
                    FactorSnapshotService.memberLinks(snapshotRepository.findIndustryMembers())) {
                if (affectedIndustries.contains(link.industry())) {
                    Long id = idByCode.get(link.subjectCode());
                    if (id != null) {
                        ids.add(id);
                    }
                }
            }
        }
        return ids;
    }

    // ---- 留痕辅助 ----

    /** 前分：重算前的当日行分值（无当日行标的不进 before——首覆盖如实标注）。 */
    private Map<Long, Double> beforeScores(String dateText, Set<Long> affectedIds) {
        Map<Long, Double> before = new HashMap<>();
        for (var score : logRepository.findScoresBySubjectIds(dateText, affectedIds)) {
            before.put(score.subjectId(), score.totalScore());
        }
        return before;
    }

    /** subjects_json：[{id, code, before, after}]（id 升序确定性；after 取重算后当日行）。 */
    private String subjectsJson(List<PoolRow> poolRows, Map<Long, Double> before) {
        if (poolRows.isEmpty()) {
            return "[]";
        }
        Map<Long, PoolRow> rowById = new TreeMap<>();
        for (PoolRow row : poolRows) {
            rowById.put(row.subjectId(), row);
        }
        List<Map<String, Object>> subjects = new ArrayList<>(rowById.size());
        for (Map.Entry<Long, PoolRow> entry : rowById.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", entry.getKey());
            item.put("code", entry.getValue().subjectCode());
            item.put("before", before.get(entry.getKey()));
            item.put("after", entry.getValue().totalScore());
            subjects.add(item);
        }
        try {
            return objectMapper.writeValueAsString(subjects);
        } catch (Exception e) {
            log.warn("subjects_json 序列化失败（回落空数组）: {}", e.getMessage());
            return "[]";
        }
    }

    private static List<Long> roundEventIds(List<ReevalEvent> events, List<PendingLink> pending) {
        List<Long> ids = new ArrayList<>(events.size() + pending.size());
        for (ReevalEvent event : events) {
            ids.add(event.eventId());
        }
        for (PendingLink link : pending) {
            ids.add(link.eventId());
        }
        return ids;
    }

    private void markRoundFailed(
            List<ReevalEvent> events, List<PendingLink> pending, RuntimeException e) {
        try {
            logRepository.markFailed(
                    roundEventIds(events, pending),
                    head(e.toString(), 400),
                    clock.instant().toString());
        } catch (RuntimeException markError) {
            log.error("FAILED 留痕写入失败（业务状态以快照/榜单表为准，log 可由对账修复）: {}", markError.toString());
        }
    }

    /** subjects_json → 标的 id 集（挂起轮重走联动段的判定集重建；损坏容错空集）。 */
    private Set<Long> subjectIdsOf(String subjectsJson) {
        Set<Long> ids = new LinkedHashSet<>();
        try {
            var array = objectMapper.readTree(subjectsJson == null ? "[]" : subjectsJson);
            if (array.isArray()) {
                for (var node : array) {
                    if (node.path("id").canConvertToLong()) {
                        ids.add(node.path("id").asLong());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("挂起轮 subjects_json 解析失败（容错空集）: {}", e.getMessage());
        }
        return ids;
    }

    private static String head(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /** tick 报告（JobRunStats 留痕面）。 */
    public record ReevalReport(String detail, int processed) {}
}
