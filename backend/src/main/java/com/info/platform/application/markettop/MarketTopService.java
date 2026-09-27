package com.info.platform.application.markettop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.markettop.DeepDiveService.DiveResult;
import com.info.platform.application.markettop.IndustryMemberBackfillService.BackfillReport;
import com.info.platform.domain.markettop.DeepDiveInput;
import com.info.platform.domain.markettop.DeepDiveInput.EventFact;
import com.info.platform.domain.markettop.DeepDiveInput.FactorDim;
import com.info.platform.domain.markettop.DeepDiveInput.NewsFact;
import com.info.platform.domain.markettop.DeepDiveInput.SubjectRef;
import com.info.platform.domain.markettop.DeepDiveOutcome;
import com.info.platform.domain.markettop.MarketTopPoolBuilder;
import com.info.platform.domain.markettop.MarketTopPoolBuilder.Candidate;
import com.info.platform.domain.markettop.MarketTopPoolBuilder.PoolConfig;
import com.info.platform.domain.markettop.MarketTopPoolBuilder.PoolResult;
import com.info.platform.domain.markettop.MarketTopRepository;
import com.info.platform.domain.markettop.MarketTopRepository.MarketTopBatchRow;
import com.info.platform.domain.markettop.MarketTopRepository.MarketTopRankRow;
import com.info.platform.domain.markettop.RankDiffer;
import com.info.platform.domain.markettop.RankDiffer.Change;
import com.info.platform.domain.markettop.TopComposer;
import com.info.platform.domain.markettop.TopComposer.Composed;
import com.info.platform.domain.markettop.TopComposer.ScoredSubject;
import com.info.platform.domain.recommendation.IndustryDirectory;
import com.info.platform.domain.valuation.FactorSnapshotRepository;
import com.info.platform.domain.valuation.FactorSnapshotRepository.PoolRow;
import com.info.platform.domain.valuation.HeatRow;
import com.info.platform.domain.valuation.MarketDailySnapshotRepository;
import com.info.platform.domain.valuation.ValuationParams;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 全市场榜单四阶段编排（应用层，M21 T183，方案 §4.6 + ADR-0059 裁决 2/4/5/6）：
 *
 * <ol>
 *   <li>阶段 0 回联保障：成员覆盖率预检 + 双通道回填（整段失败降级继续）；
 *   <li>阶段 1 粗筛：当日快照全量行 → PoolBuilder 排除/四键排序/切分（池 ~300 + 深析候选 ~40）；
 *   <li>阶段 2 深析：逐只「成本预检 → 输入组装 → LLM → 五步校验链 → 兜底」，三档降级监控（单次失败模板兜底 / 连续 ≥5 失败中止 LLM_FAILURE /
 *       成本触顶停剩余 COST_CAP）；
 *   <li>阶段 3 合成：TopComposer（层数断言 → final 排序 → 恰 10 截断）→ RankDiffer（昨日 diff）→ V31 两表追加式版本化落库。
 * </ol>
 *
 * <p><b>快照日守卫</b>（§4.6）：请求日无当日快照 → WARN 跳过留痕（次日全量自然修复 + 任务中心手动补触发）。LLM 全 Mock 下本类可全链单测。
 */
@Service
public class MarketTopService {

    /** 榜单口径时区（rank_date 语义）。 */
    static final ZoneId RANK_ZONE = ZoneId.of("Asia/Shanghai");

    /** 连续 LLM 失败中止阈值（降级三档之②，§4.4.7）。 */
    static final int CONSECUTIVE_FAILURE_ABORT = 5;

    /** 关联资讯回看窗（§4.4.2 relatedNews 窗）。 */
    static final int RELATED_NEWS_WINDOW_DAYS = 7;

    /** Top 依据事件上限（§4.4.2 cap 6）。 */
    static final int TOP_EVENTS_CAP = 6;

    /** 关联资讯上限（§4.4.2 cap 8）。 */
    static final int RELATED_NEWS_CAP = 8;

    /** 行业资讯上限（§4.4.2 cap 3）。 */
    static final int INDUSTRY_NEWS_CAP = 3;

    /** 版本列表读取上限（护栏——180 天 × 日 1~3 版本量级）。 */
    static final int VERSIONS_LIMIT = 200;

    /** 降级原因常量（batch.degraded_reason 值域）。 */
    static final String DEGRADED_COST_CAP = "COST_CAP";

    static final String DEGRADED_LLM_FAILURE = "LLM_FAILURE";

    private static final Logger log = LoggerFactory.getLogger(MarketTopService.class);

    private final IndustryMemberBackfillService backfillService;

    private final FactorSnapshotRepository snapshotRepository;

    private final MarketDailySnapshotRepository marketRepository;

    private final DeepDiveNewsStore newsStore;

    private final DeepDiveService deepDiveService;

    private final MarketTopRepository repository;

    private final MarketTopConfigSettings configSettings;

    private final com.info.platform.application.analysis.PipelineGuardService guardService;

    private final ObjectMapper objectMapper;

    private final Clock clock;

    /** 深析调用间隔（生产 200ms；测试注入 0 提速）。 */
    private final long diveCallIntervalMillis;

    /**
     * 单构造器（Spring 自动装配；diveCallIntervalMillis 经 {@code markettop.rank-job.dive-interval-millis}
     * 可配——测试直构传 0 免睡眠）。
     */
    public MarketTopService(
            IndustryMemberBackfillService backfillService,
            FactorSnapshotRepository snapshotRepository,
            MarketDailySnapshotRepository marketRepository,
            DeepDiveNewsStore newsStore,
            DeepDiveService deepDiveService,
            MarketTopRepository repository,
            MarketTopConfigSettings configSettings,
            com.info.platform.application.analysis.PipelineGuardService guardService,
            ObjectMapper objectMapper,
            Clock clock,
            @org.springframework.beans.factory.annotation.Value(
                            "${markettop.rank-job.dive-interval-millis:200}")
                    long diveCallIntervalMillis) {
        this.backfillService = backfillService;
        this.snapshotRepository = snapshotRepository;
        this.marketRepository = marketRepository;
        this.newsStore = newsStore;
        this.deepDiveService = deepDiveService;
        this.repository = repository;
        this.configSettings = configSettings;
        this.guardService = guardService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.diveCallIntervalMillis = diveCallIntervalMillis;
    }

    /**
     * 生成一版本榜单（幂等键 = (rank_date, version)：同日重跑 version+1 追加不覆盖）。
     *
     * @param rankDate 榜单日（Asia/Shanghai 口径；须已有该日快照，否则守卫跳过）
     * @return 生成报告（状态/漏斗计数/深析统计/降级态——JobRunStats detail 留痕面）
     */
    public GenerationReport generate(LocalDate rankDate) {
        String date = rankDate.toString();
        Optional<String> latestSnapshot = snapshotRepository.findLatestSnapshotDate();
        if (latestSnapshot.isEmpty() || !latestSnapshot.get().equals(date)) {
            log.warn(
                    "快照日守卫跳过（当日快照未出，次日自愈或手动补触发）: rankDate={} latestSnapshot={}",
                    date,
                    latestSnapshot.orElse(null));
            return GenerationReport.skipped(date, latestSnapshot.orElse(null));
        }

        BackfillReport backfill = backfillService.backfillIfBelowFloor();
        List<PoolRow> rows = snapshotRepository.findPoolRowsByDate(date);
        if (rows.isEmpty()) {
            log.warn("当日快照零行（防御跳过）: rankDate={}", date);
            return GenerationReport.skipped(date, date);
        }
        MarketTopConfig config = configSettings.current();
        PoolResult pool =
                MarketTopPoolBuilder.build(
                        candidatesOf(rows),
                        new PoolConfig(config.poolSize(), config.deepDiveLimit()));

        DiveLoopResult diveLoop = runDiveLoop(pool.diveCandidates(), rows, rankDate);

        List<Composed> top =
                TopComposer.compose(
                        scoredSubjects(pool.pool(), rows, diveLoop.outcomes()),
                        new TopComposer.Config(
                                config.poolSize(),
                                config.deepDiveLimit(),
                                config.deepDiveCostCapRatio()));
        try {
            MarketTopPoolBuilder.assertFunnelLayers(
                    rows.size(), pool.pool().size(), pool.diveCandidates().size(), top.size());
        } catch (IllegalStateException e) {
            // 宁缺毋错：装配/配置错误中止落库（「全量 LLM 逐股」类事故的运行时防线，§4.3.4）
            log.error("漏斗层数断言违反，中止落库: {}", e.getMessage());
            return GenerationReport.failed(date, e.getMessage(), pool, diveLoop, backfill);
        }
        int version = persist(date, rows.size(), pool, diveLoop, top);
        return GenerationReport.success(date, version, pool, diveLoop, top.size(), backfill);
    }

    // ---- 阶段 2：深析循环（三档降级监控） ----

    private DiveLoopResult runDiveLoop(
            List<Candidate> diveCandidates, List<PoolRow> rows, LocalDate rankDate) {
        Map<Long, PoolRow> rowById = new HashMap<>();
        for (PoolRow row : rows) {
            rowById.put(row.subjectId(), row);
        }
        Map<Long, Double> percentileBySubject = percentilesOf(rows);
        Map<String, Integer> heatRankByIndustry = heatRanks();
        Map<Long, MarketDailySnapshotRepository.MarketDailyRow> marketBySubject =
                marketRepository.findByDate(rankDate.toString());
        String newsFromIso =
                rankDate.minusDays(RELATED_NEWS_WINDOW_DAYS)
                        .atStartOfDay(RANK_ZONE)
                        .toInstant()
                        .toString();

        Map<Long, DeepDiveOutcome> outcomes = new LinkedHashMap<>();
        int llmDone = 0;
        int templateDone = 0;
        int skipped = 0;
        int llmCalls = 0;
        int citationDrops = 0;
        int consecutiveFailures = 0;
        String degradedReason = null;
        String promptVersion = null;

        for (Candidate candidate : diveCandidates) {
            if (degradedReason != null) {
                skipped++;
                continue;
            }
            DeepDiveInput input =
                    assembleInput(
                            candidate,
                            rowById.get(candidate.subjectId()),
                            percentileBySubject,
                            heatRankByIndustry,
                            marketBySubject.get(candidate.subjectId()),
                            newsFromIso);
            DiveResult result = deepDiveService.analyze(input);
            if (result.costCapped()) {
                degradedReason = DEGRADED_COST_CAP;
                skipped++;
                continue;
            }
            if (result.promptVersion() != null) {
                llmCalls++;
                promptVersion = result.promptVersion();
            }
            citationDrops += result.citationDrops();
            outcomes.put(candidate.subjectId(), result.outcome());
            if (result.outcome().method() == DeepDiveOutcome.GenMethod.LLM) {
                llmDone++;
            } else {
                templateDone++;
            }
            if (result.llmCallThrew()) {
                consecutiveFailures++;
                if (consecutiveFailures >= CONSECUTIVE_FAILURE_ABORT) {
                    log.warn("深析连续 {} 次失败，中止剩余（LLM_FAILURE）", consecutiveFailures);
                    degradedReason = DEGRADED_LLM_FAILURE;
                }
            } else {
                consecutiveFailures = 0;
            }
            sleepBetweenCalls();
        }
        return new DiveLoopResult(
                outcomes,
                llmDone,
                templateDone,
                skipped,
                llmCalls,
                citationDrops,
                degradedReason,
                promptVersion);
    }

    private void sleepBetweenCalls() {
        if (diveCallIntervalMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(diveCallIntervalMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("深析调用间隔睡眠被中断（继续）");
        }
    }

    /** 单标的深析输入组装（§4.4.2 输入契约——引用白名单单源）。 */
    private DeepDiveInput assembleInput(
            Candidate candidate,
            PoolRow row,
            Map<Long, Double> percentileBySubject,
            Map<String, Integer> heatRankByIndustry,
            MarketDailySnapshotRepository.MarketDailyRow marketRow,
            String newsFromIso) {
        String swIndustry =
                row.industry() == null ? null : IndustryDirectory.swPrimaryOf(row.industry());
        ValuationParams params = ValuationParams.fromBasis(row.weightBasis());
        List<EventFact> topEvents = topEventsOf(row.factorDetailJson());
        List<NewsFact> relatedNews =
                newsStore.findRelatedNews(candidate.subjectCode(), newsFromIso, RELATED_NEWS_CAP);
        List<NewsFact> industryNews =
                swIndustry == null
                        ? List.of()
                        : newsStore.findIndustryNews(swIndustry, newsFromIso, INDUSTRY_NEWS_CAP);
        return new DeepDiveInput(
                new SubjectRef(candidate.subjectCode(), candidate.subjectName(), swIndustry),
                List.of(
                        new FactorDim("catalyst", "事件催化", row.fCatalyst(), params.wCatalyst()),
                        new FactorDim(
                                "conduction", "行业传导", row.fConduction(), params.wConduction()),
                        new FactorDim(
                                "fundamental", "基本面边际", row.fFundamental(), params.wFundamental()),
                        new FactorDim("risk", "风险安全", row.fRisk(), params.wRisk()),
                        new FactorDim("valuation", "估值水平", row.fValuation(), params.wValuation())),
                row.totalScore(),
                Math.round(percentileBySubject.getOrDefault(candidate.subjectId(), 0.0)),
                row.breakthrough(),
                topEvents,
                relatedNews,
                industryNews,
                marketSnapshotOf(marketRow),
                ValuationParams.fromBasis(row.weightBasis()).catalystWindowDays(),
                evidenceCountOf(row.factorDetailJson()),
                swIndustry == null ? 0 : heatRankByIndustry.getOrDefault(swIndustry, 0));
    }

    // ---- 阶段 3：合成 + diff + 落库 ----

    /** 落库一版本（合成终态 → RankDiffer → 两表追加；返回落库版本号）。 */
    private int persist(
            String date,
            long snapshotRows,
            PoolResult pool,
            DiveLoopResult diveLoop,
            List<Composed> top) {
        RankDiffer.Diff diff =
                RankDiffer.diff(
                        repository.findPreviousTop(date),
                        top.stream().map(Composed::subjectId).toList());
        int version = repository.maxVersion(date) + 1;
        String computedAt = clock.instant().toString();
        MarketTopConfig config = configSettings.current();
        String basis =
                TopComposer.basis(
                        new TopComposer.Config(
                                config.poolSize(),
                                config.deepDiveLimit(),
                                config.deepDiveCostCapRatio()));

        List<MarketTopRankRow> rankRows = new ArrayList<>(top.size());
        for (int index = 0; index < top.size(); index++) {
            Composed composed = top.get(index);
            Change change =
                    diff.changes()
                            .getOrDefault(
                                    composed.subjectId(),
                                    new RankDiffer.Change(null, RankDiffer.NEW));
            rankRows.add(
                    new MarketTopRankRow(
                            date,
                            version,
                            index + 1,
                            composed.subjectId(),
                            composed.subjectCode(),
                            composed.subjectName(),
                            composed.totalScore(),
                            composed.finalScore(),
                            composed.percentile(),
                            composed.breakthrough(),
                            composed.generation(),
                            composed.diveMethod(),
                            composed.dive() == null
                                    ? "该标的未深析（降级/未入深析候选），按因子分排序。"
                                    : composed.dive().summary(),
                            diveDetailJson(composed.dive()),
                            composed.evidenceCount(),
                            composed.lastEventDate(),
                            change.prevRank(),
                            change.changeType(),
                            basis,
                            computedAt));
        }

        MarketTopBatchRow batch =
                new MarketTopBatchRow(
                        date,
                        version,
                        "DAILY",
                        date,
                        funnelStatsJson(snapshotRows, pool, diveLoop, top.size()),
                        diveLoop.degradedReason() != null,
                        diveLoop.degradedReason(),
                        droppedSubjectsJson(diff),
                        guardService.todaySceneCostMicros(
                                com.info.platform.domain.ai.BriefType.DEEP_DIVE.key()),
                        diveLoop.llmCalls(),
                        diveLoop.promptVersion(),
                        basis,
                        null); // created_at 由仓储落库时回填
        repository.insertVersion(batch, rankRows);
        log.info(
                "榜单版本落库: rankDate={} version={} topSize={} degraded={} reason={}",
                date,
                version,
                top.size(),
                batch.degraded(),
                diveLoop.degradedReason());
        return version;
    }

    // ---- 组装辅助（纯投影） ----

    private static List<Candidate> candidatesOf(List<PoolRow> rows) {
        return rows.stream()
                .map(
                        row ->
                                new Candidate(
                                        row.subjectId(),
                                        row.subjectCode(),
                                        row.subjectName(),
                                        row.totalScore(),
                                        row.fCatalyst(),
                                        row.fConduction(),
                                        row.lastEventDate()))
                .toList();
    }

    private static List<ScoredSubject> scoredSubjects(
            List<Candidate> pool, List<PoolRow> rows, Map<Long, DeepDiveOutcome> outcomes) {
        Map<Long, PoolRow> rowById = new HashMap<>();
        for (PoolRow row : rows) {
            rowById.put(row.subjectId(), row);
        }
        return pool.stream()
                .map(
                        candidate -> {
                            PoolRow row = rowById.get(candidate.subjectId());
                            return new ScoredSubject(
                                    candidate.subjectId(),
                                    candidate.subjectCode(),
                                    candidate.subjectName(),
                                    candidate.totalScore(),
                                    candidate.fCatalyst(),
                                    candidate.lastEventDate(),
                                    row != null && row.breakthrough(),
                                    row == null ? 0.0 : percentileInMemory(rows, row.totalScore()),
                                    row == null ? 0 : evidenceCountOf(row.factorDetailJson()),
                                    outcomes.get(candidate.subjectId()));
                        })
                .toList();
    }

    /** 全市场百分位（查询层口径同 ValueScoreQueryService：rank = 严格大于 + 1，并列同名次）。 */
    private static double percentileInMemory(List<PoolRow> rows, double totalScore) {
        long total = rows.size();
        long rank = rows.stream().filter(row -> row.totalScore() > totalScore).count() + 1;
        return Math.round(100.0 * (total - rank) / Math.max(1, total - 1));
    }

    private static Map<Long, Double> percentilesOf(List<PoolRow> rows) {
        Map<Long, Double> percentiles = new HashMap<>();
        for (PoolRow row : rows) {
            percentiles.put(row.subjectId(), percentileInMemory(rows, row.totalScore()));
        }
        return percentiles;
    }

    /** 行业 24h 热度排名（heat_score 降序，1 起）。 */
    private Map<String, Integer> heatRanks() {
        List<HeatRow> heatRows = new ArrayList<>(snapshotRepository.findH24Heat());
        heatRows.sort(Comparator.comparingDouble(HeatRow::heatScore).reversed());
        Map<String, Integer> ranks = new HashMap<>();
        for (int index = 0; index < heatRows.size(); index++) {
            ranks.putIfAbsent(heatRows.get(index).industry(), index + 1);
        }
        return ranks;
    }

    /** Top 依据事件：catalyst + risk 条目按贡献（coef）降序 cap 6（§4.4.2）。 */
    private List<EventFact> topEventsOf(String factorDetailJson) {
        List<EventFact> events = new ArrayList<>();
        JsonNode detail = readTree(factorDetailJson);
        appendEvents(events, detail.path("catalyst").path("entries"));
        appendEvents(events, detail.path("risk").path("entries"));
        events.sort(Comparator.comparingDouble(EventFact::coef).reversed());
        return List.copyOf(events.subList(0, Math.min(TOP_EVENTS_CAP, events.size())));
    }

    private static void appendEvents(List<EventFact> events, JsonNode entries) {
        if (!entries.isArray()) {
            return;
        }
        for (JsonNode entry : entries) {
            JsonNode eventId = entry.get("eventId");
            if (eventId == null || !eventId.canConvertToLong()) {
                continue;
            }
            events.add(
                    new EventFact(
                            eventId.asLong(),
                            text(entry.get("summary")),
                            text(entry.get("direction")),
                            text(entry.get("importance")),
                            text(entry.get("eventDate")),
                            entry.path("coef").asDouble(0.0)));
        }
    }

    /** 依据事件总数（catalyst + fundamental + risk 三维条目合计——evidence_count 口径）。 */
    private static int evidenceCountOf(String factorDetailJson) {
        JsonNode detail = readTree(factorDetailJson);
        return detail.path("catalyst").path("entries").size()
                + detail.path("fundamental").path("entries").size()
                + detail.path("risk").path("entries").size();
    }

    /** 行情快照投影（缺数键省略；无行情行返回空 Map）。 */
    private static Map<String, Double> marketSnapshotOf(
            MarketDailySnapshotRepository.MarketDailyRow marketRow) {
        Map<String, Double> snapshot = new LinkedHashMap<>();
        if (marketRow == null) {
            return snapshot;
        }
        putIfPresent(snapshot, "close", marketRow.closePrice());
        putIfPresent(snapshot, "pctChange", marketRow.pctChange());
        putIfPresent(snapshot, "turnoverRate", marketRow.turnoverRate());
        putIfPresent(snapshot, "pe", marketRow.peTtm());
        putIfPresent(snapshot, "pb", marketRow.pb());
        return snapshot;
    }

    private static void putIfPresent(Map<String, Double> target, String key, Double value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json == null ? "{}" : json);
        } catch (IOException e) {
            return MAPPER.createObjectNode();
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    /** dive_detail JSON（校验后终态；factor_only 未深析 = 空结构）。 */
    private String diveDetailJson(DeepDiveOutcome dive) {
        if (dive == null) {
            return "{}";
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("thesis", dive.thesis());
        detail.put("highlights", dive.highlights());
        detail.put("risks", dive.risks());
        detail.put("citations", dive.citations());
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            log.warn("dive_detail 序列化失败（回落空对象）: {}", e.getMessage());
            return "{}";
        }
    }

    /** funnel_stats JSON（§4.2 契约：漏斗五值 + dive 计数）。 */
    private String funnelStatsJson(
            long snapshotRows, PoolResult pool, DiveLoopResult diveLoop, int topSize) {
        Map<String, Object> funnel = new LinkedHashMap<>();
        funnel.put("snapshotRows", snapshotRows);
        funnel.put("eligible", pool.funnel().eligible());
        funnel.put("excluded", pool.funnel().excluded());
        funnel.put("poolSize", pool.funnel().poolSize());
        funnel.put("divePlanned", pool.funnel().divePlanned());
        funnel.put("diveDone", diveLoop.llmDone());
        funnel.put("diveTemplate", diveLoop.templateDone());
        funnel.put("diveSkipped", diveLoop.skipped());
        funnel.put("topSize", topSize);
        try {
            return objectMapper.writeValueAsString(funnel);
        } catch (Exception e) {
            log.warn("funnel_stats 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    /** dropped_subjects JSON（[{code,name,prevRank}]）。 */
    private String droppedSubjectsJson(RankDiffer.Diff diff) {
        try {
            return objectMapper.writeValueAsString(diff.dropped());
        } catch (Exception e) {
            log.warn("dropped_subjects 序列化失败（回落空数组）: {}", e.getMessage());
            return "[]";
        }
    }

    /** 深析循环产物（计数留痕面）。 */
    public record DiveLoopResult(
            Map<Long, DeepDiveOutcome> outcomes,
            int llmDone,
            int templateDone,
            int skipped,
            int llmCalls,
            int citationDrops,
            String degradedReason,
            String promptVersion) {

        boolean degraded() {
            return degradedReason != null;
        }
    }

    /**
     * 生成报告（JobRunStats detail 留痕面；MarketTopQueryService 版本读取另见）。
     *
     * @param status SKIPPED（快照日守卫/零行）/ SUCCESS / FAILED（层数断言中止）
     */
    record GenerationReport(
            String status,
            String rankDate,
            int version,
            long snapshotRows,
            int eligible,
            int poolSize,
            int divePlanned,
            int diveDone,
            int diveTemplate,
            int diveSkipped,
            int topSize,
            boolean degraded,
            String degradedReason,
            int citationDrops,
            String backfillDetail,
            String reason) {

        static final String STATUS_SKIPPED = "SKIPPED";

        static final String STATUS_SUCCESS = "SUCCESS";

        static final String STATUS_FAILED = "FAILED";

        static GenerationReport skipped(String rankDate, String latestSnapshot) {
            return new GenerationReport(
                    STATUS_SKIPPED,
                    rankDate,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    false,
                    null,
                    0,
                    "",
                    "snapshotGuard: latestSnapshot=" + latestSnapshot);
        }

        static GenerationReport failed(
                String rankDate,
                String reason,
                PoolResult pool,
                DiveLoopResult diveLoop,
                BackfillReport backfill) {
            return new GenerationReport(
                    STATUS_FAILED,
                    rankDate,
                    0,
                    pool.funnel().snapshotRows(),
                    pool.funnel().eligible(),
                    pool.funnel().poolSize(),
                    pool.funnel().divePlanned(),
                    diveLoop.llmDone(),
                    diveLoop.templateDone(),
                    diveLoop.skipped(),
                    0,
                    diveLoop.degraded(),
                    diveLoop.degradedReason(),
                    diveLoop.citationDrops(),
                    backfill.detail(),
                    reason);
        }

        static GenerationReport success(
                String rankDate,
                int version,
                PoolResult pool,
                DiveLoopResult diveLoop,
                int topSize,
                BackfillReport backfill) {
            return new GenerationReport(
                    STATUS_SUCCESS,
                    rankDate,
                    version,
                    pool.funnel().snapshotRows(),
                    pool.funnel().eligible(),
                    pool.funnel().poolSize(),
                    pool.funnel().divePlanned(),
                    diveLoop.llmDone(),
                    diveLoop.templateDone(),
                    diveLoop.skipped(),
                    topSize,
                    diveLoop.degraded(),
                    diveLoop.degradedReason(),
                    diveLoop.citationDrops(),
                    backfill.detail(),
                    null);
        }

        /** 快照日守卫跳过态。 */
        boolean skipped() {
            return STATUS_SKIPPED.equals(status);
        }

        /** JobRunStats 留痕明细（段式约定：funnel/dive/degraded/backfill 全量计数）。 */
        String detail() {
            return "funnel="
                    + snapshotRows
                    + ">"
                    + poolSize
                    + ">"
                    + divePlanned
                    + ">"
                    + topSize
                    + ";eligible="
                    + eligible
                    + ";dive=ok:"
                    + diveDone
                    + "|tpl:"
                    + diveTemplate
                    + "|skip:"
                    + diveSkipped
                    + ";citationDrops="
                    + citationDrops
                    + (degraded ? ";degraded=" + degradedReason : "")
                    + ";backfill["
                    + backfillDetail
                    + "]"
                    + (reason == null ? "" : ";reason=" + reason);
        }
    }
}
