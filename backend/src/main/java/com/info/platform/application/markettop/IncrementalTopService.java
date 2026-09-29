package com.info.platform.application.markettop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.analysis.PipelineGuardService;
import com.info.platform.application.markettop.DeepDiveService.DiveResult;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.analysis.GuardLevel;
import com.info.platform.domain.markettop.DeepDiveInput;
import com.info.platform.domain.markettop.DeepDiveOutcome;
import com.info.platform.domain.markettop.DeepDiveOutputParser.Entry;
import com.info.platform.domain.markettop.MarketTopRepository;
import com.info.platform.domain.markettop.MarketTopRepository.MarketTopBatchRow;
import com.info.platform.domain.markettop.MarketTopRepository.MarketTopRankRow;
import com.info.platform.domain.markettop.MarketTopRepository.MarketTopVersion;
import com.info.platform.domain.markettop.RankDiffer;
import com.info.platform.domain.markettop.SqueezeJudge;
import com.info.platform.domain.markettop.SqueezeJudge.Verdict;
import com.info.platform.domain.markettop.TopComposer;
import com.info.platform.domain.valuation.FactorSnapshotRepository;
import com.info.platform.domain.valuation.FactorSnapshotRepository.PoolRow;
import com.info.platform.domain.valuation.IncrementalReevalRepository.ReevalEvent;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 增量榜单联动编排（应用层，M22 T191，方案 §3.3 + ADR-0061 裁决 3）：<b>不走</b> MarketTopService.generate() 全四阶段（粗筛 40
 * 只深析 = 成本放量，违反护栏语义），以 SqueezeJudge 过阈判定集直落一版本：
 *
 * <ol>
 *   <li>version+1 追加（trigger_source=EVENT + trigger_events 归因 JSON——该版本全部变动归因到触发事件，故事 2 场景 4）；
 *   <li>不变成员<b>深析成果直继承</b>（dive_detail/dive_method/generation 沿用前版本行零重析；final 按 mt-v1 公式以新 total
 *       重合成）；
 *   <li>仅「新入榜且当日无 FULL 深析」补一次深析（护栏 scene-10 内；触顶/护栏降级/失败 → FACTOR_ONLY 兜底入榜页面明示）；
 *   <li>RankDiffer：EVENT 版本 diff 基准 = 同日前一版本（无则回落既有昨日口径）。
 * </ol>
 *
 * <p>幂等：UNIQUE(rank_date, version, market) 冲突由仓储 insertVersion 重取 +1 重试一次（互斥层③，DAILY/EVENT
 * 共用）。<b>M29 T256：EVENT 联动恒 A 股</b>（因子快照/增量重评链 A 股-only，方案 §7.4 market 隔离触发——港美股无因子快照行不进联动）。
 */
@Service
public class IncrementalTopService {

    /** 深析 scene 键（batch dive_cost_micros 口径，MarketTopService 同源）。 */
    private static final String SCENE_KEY = com.info.platform.domain.ai.BriefType.DEEP_DIVE.key();

    private static final Logger log = LoggerFactory.getLogger(IncrementalTopService.class);

    private final MarketTopRepository repository;

    private final FactorSnapshotRepository snapshotRepository;

    private final DeepDiveService deepDiveService;

    private final MarketTopService marketTopService;

    private final PipelineGuardService guardService;

    private final MarketTopConfigSettings configSettings;

    private final ObjectMapper objectMapper;

    private final Clock clock;

    public IncrementalTopService(
            MarketTopRepository repository,
            FactorSnapshotRepository snapshotRepository,
            DeepDiveService deepDiveService,
            MarketTopService marketTopService,
            PipelineGuardService guardService,
            MarketTopConfigSettings configSettings,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.snapshotRepository = snapshotRepository;
        this.deepDiveService = deepDiveService;
        this.marketTopService = marketTopService;
        this.guardService = guardService;
        this.configSettings = configSettings;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 联动落一版本（幂等键 = (rank_date, version)：同日 version+1 追加不覆盖，冲突由仓储重试兜底）。
     *
     * @param rankDate 榜单日（Asia/Shanghai 口径）
     * @param verdict SqueezeJudge 过阈判定（newTop 即新版本成员序）
     * @param events 本轮触发事件（归因与留痕面）
     * @return 落库版本号
     */
    public int link(LocalDate rankDate, Verdict verdict, Collection<ReevalEvent> events) {
        String date = rankDate.toString();
        int maxVersion = repository.maxVersion(date, Market.A_SHARE);
        Map<Long, MarketTopRankRow> priorRows = priorVersionRows(date, maxVersion);
        List<PoolRow> poolRows = snapshotRepository.findPoolRowsByDate(date);
        Map<Long, PoolRow> rowById = new HashMap<>();
        for (PoolRow row : poolRows) {
            rowById.put(row.subjectId(), row);
        }
        Set<Long> entrants = new HashSet<>();
        for (SqueezeJudge.Swap swap : verdict.swaps()) {
            entrants.add(swap.entrant().subjectId());
        }

        // diff 基准：同日前一版本；无则回落昨日口径（findPreviousTop 7 天回看窗）
        List<RankDiffer.PrevSubject> prevTop =
                maxVersion > 0
                        ? toPrevSubjects(priorRows)
                        : repository.findPreviousTop(date, Market.A_SHARE);
        RankDiffer.Diff diff =
                RankDiffer.diff(
                        prevTop,
                        verdict.newTop().stream().map(SqueezeJudge.Candidate::subjectId).toList());

        List<MarketTopRankRow> rankRows = new ArrayList<>(verdict.newTop().size());
        int diveLlmCalls = 0;
        String computedAt = clock.instant().toString();
        String basis = TopComposer.basis(topConfig());
        for (int index = 0; index < verdict.newTop().size(); index++) {
            SqueezeJudge.Candidate member = verdict.newTop().get(index);
            PoolRow row = rowById.get(member.subjectId());
            if (row == null) {
                log.warn("联动成员无当日快照行（跳过——防御）: date={} subjectId={}", date, member.subjectId());
                continue;
            }
            MarketTopRankRow prior = priorRows.get(member.subjectId());
            Inherited inherit = inheritOf(prior);
            DeepDiveOutcome dive = inherit.dive();
            boolean supplementDived = false;
            if (entrants.contains(member.subjectId()) && !inherit.full()) {
                // 仅新入榜且当日无 FULL 深析补一次（护栏内；失败/触顶 FACTOR_ONLY 兜底）
                DiveResult result = supplementDive(row, rankDate);
                if (result != null
                        && result.outcome() != null
                        && result.outcome().method() == DeepDiveOutcome.GenMethod.LLM) {
                    dive = result.outcome();
                    supplementDived = true;
                    diveLlmCalls++;
                }
            }
            double totalScore = row.totalScore();
            boolean fullDive = inherit.full() || supplementDived;
            double finalScore =
                    fullDive && dive != null
                            ? Math.max(
                                    totalScore,
                                    0.8 * totalScore + 0.2 * TopComposer.diveScore(dive))
                            : totalScore;
            String diveSummary =
                    inherit.full()
                            ? inherit.diveSummary()
                            : supplementDived
                                            && dive != null
                                            && dive.summary() != null
                                            && !dive.summary().isBlank()
                                    ? dive.summary()
                                    : (fullDive ? "深析合格入榜。" : "该标的未深析（增量联动兜底），按因子分排序。");
            RankDiffer.Change change =
                    diff.changes()
                            .getOrDefault(
                                    member.subjectId(),
                                    new RankDiffer.Change(null, RankDiffer.NEW));
            rankRows.add(
                    new MarketTopRankRow(
                            Market.A_SHARE,
                            date,
                            maxVersion + 1,
                            index + 1,
                            member.subjectId(),
                            member.code(),
                            member.name(),
                            totalScore,
                            finalScore,
                            percentileOf(poolRows, totalScore),
                            row.breakthrough(),
                            fullDive ? "FULL" : "FACTOR_ONLY",
                            fullDive ? "LLM" : inherit.diveMethod(),
                            diveSummary,
                            fullDive ? marketTopService.diveDetailJson(dive) : "{}",
                            evidenceCountOf(row.factorDetailJson()),
                            row.lastEventDate(),
                            change.prevRank(),
                            change.changeType(),
                            basis,
                            computedAt));
        }
        if (rankRows.isEmpty()) {
            log.warn("联动零行（防御跳过不落库）: date={}", date);
            return maxVersion;
        }
        MarketTopBatchRow batch =
                new MarketTopBatchRow(
                        Market.A_SHARE,
                        date,
                        maxVersion + 1,
                        "EVENT",
                        date,
                        funnelStatsJson(rankRows.size()),
                        false,
                        null,
                        droppedSubjectsJson(diff),
                        guardService.todaySceneCostMicros(SCENE_KEY),
                        diveLlmCalls,
                        null,
                        basis,
                        triggerEventsJson(events),
                        computedAt);
        repository.insertVersion(batch, rankRows);
        log.info(
                "增量联动版本落库: rankDate={} version={} topSize={} swaps={} diveCalls={}",
                date,
                batch.version(),
                rankRows.size(),
                verdict.swaps().size(),
                diveLlmCalls);
        return batch.version();
    }

    // ---- 深析继承与补析 ----

    /** 继承态（前版本行深析产物：FULL 直继承 dive 结构与摘要；FACTOR_ONLY/无行 = 无深析幅面）。 */
    private record Inherited(
            DeepDiveOutcome dive, boolean full, String diveMethod, String diveSummary) {

        static final Inherited NONE = new Inherited(null, false, null, null);
    }

    private Inherited inheritOf(MarketTopRankRow prior) {
        if (prior == null) {
            return Inherited.NONE;
        }
        boolean full = "FULL".equals(prior.generation());
        return new Inherited(
                full ? outcomeOfDetail(prior.diveDetailJson()) : null,
                full,
                prior.diveMethod(),
                prior.diveSummary());
    }

    /** dive_detail JSON → DeepDiveOutcome（TopComposer.diveScore 同源公式消费——继承重合成零第二套口径）。 */
    private DeepDiveOutcome outcomeOfDetail(String detailJson) {
        try {
            JsonNode detail = objectMapper.readTree(detailJson == null ? "{}" : detailJson);
            List<Entry> highlights = entriesOf(detail.path("highlights"));
            List<Entry> risks = entriesOf(detail.path("risks"));
            Set<com.info.platform.domain.markettop.Citation> citations =
                    new java.util.LinkedHashSet<>();
            for (Entry entry : highlights) {
                citations.addAll(entry.citations());
            }
            for (Entry entry : risks) {
                citations.addAll(entry.citations());
            }
            // 顶层 citations 为落库时的去重并集（条目内嵌与顶层两处等价防御双读）
            JsonNode topLevel = detail.path("citations");
            if (topLevel.isArray()) {
                for (JsonNode node : topLevel) {
                    citations.add(
                            new com.info.platform.domain.markettop.Citation(
                                    node.path("type").asText(null), node.path("id").asLong()));
                }
            }
            return new DeepDiveOutcome(
                    DeepDiveOutcome.GenMethod.LLM,
                    detail.path("thesis").asText(""),
                    highlights,
                    risks,
                    List.copyOf(citations),
                    null);
        } catch (Exception e) {
            log.warn("继承 dive_detail 解析失败（回落无深析幅面）: {}", e.getMessage());
            return null;
        }
    }

    private List<Entry> entriesOf(JsonNode array) {
        List<Entry> entries = new ArrayList<>();
        if (!array.isArray()) {
            return entries;
        }
        for (JsonNode node : array) {
            List<com.info.platform.domain.markettop.Citation> citations = new ArrayList<>();
            for (JsonNode citation : node.path("citations")) {
                citations.add(
                        new com.info.platform.domain.markettop.Citation(
                                citation.path("type").asText(null), citation.path("id").asLong()));
            }
            entries.add(new Entry(node.path("text").asText(null), citations));
        }
        return entries;
    }

    /** 补深析（护栏 scene-10 内单次）：管道级别非 NORMAL 或触顶 → null（FACTOR_ONLY 兜底）。 */
    private DiveResult supplementDive(PoolRow row, LocalDate rankDate) {
        if (guardService.currentLevel() != GuardLevel.NORMAL) {
            log.warn("管道护栏非 NORMAL，增量补深析跳过（FACTOR_ONLY 兜底）: level={}", guardService.currentLevel());
            return null;
        }
        DeepDiveInput input = marketTopService.assembleIncrementalInput(row, rankDate);
        return deepDiveService.analyze(input);
    }

    // ---- 组装辅助 ----

    private Map<Long, MarketTopRankRow> priorVersionRows(String date, int maxVersion) {
        Map<Long, MarketTopRankRow> rows = new HashMap<>();
        if (maxVersion <= 0) {
            return rows;
        }
        Optional<MarketTopVersion> version = repository.find(date, maxVersion, Market.A_SHARE);
        version.ifPresent(v -> v.items().forEach(item -> rows.put(item.subjectId(), item)));
        return rows;
    }

    private static List<RankDiffer.PrevSubject> toPrevSubjects(
            Map<Long, MarketTopRankRow> priorRows) {
        List<MarketTopRankRow> ordered = new ArrayList<>(priorRows.values());
        ordered.sort(java.util.Comparator.comparingInt(MarketTopRankRow::rankNo));
        return ordered.stream()
                .map(
                        row ->
                                new RankDiffer.PrevSubject(
                                        row.subjectId(),
                                        row.subjectCode(),
                                        row.subjectName(),
                                        row.rankNo()))
                .toList();
    }

    /** 全市场百分位（MarketTopService 同口径：rank = 严格大于 + 1，并列同名次）。 */
    private static double percentileOf(List<PoolRow> rows, double totalScore) {
        long total = rows.size();
        long rank = rows.stream().filter(row -> row.totalScore() > totalScore).count() + 1;
        return Math.round(100.0 * (total - rank) / Math.max(1, total - 1));
    }

    /** 依据事件总数（catalyst + fundamental + risk 三维条目合计——evidence_count 口径同 MarketTopService）。 */
    private static int evidenceCountOf(String factorDetailJson) {
        try {
            JsonNode detail =
                    new ObjectMapper().readTree(factorDetailJson == null ? "{}" : factorDetailJson);
            return detail.path("catalyst").path("entries").size()
                    + detail.path("fundamental").path("entries").size()
                    + detail.path("risk").path("entries").size();
        } catch (Exception e) {
            return 0;
        }
    }

    private TopComposer.Config topConfig() {
        MarketTopConfig config = configSettings.current();
        return new TopComposer.Config(
                config.poolSize(), config.deepDiveLimit(), config.deepDiveCostCapRatio());
    }

    private String funnelStatsJson(int topSize) {
        Map<String, Object> funnel = new LinkedHashMap<>();
        funnel.put("source", "incremental");
        funnel.put("topSize", topSize);
        return writeJson(funnel);
    }

    /** 归因 JSON：[{eventId, summary, importance}]（batch.trigger_events——trace-v1 下钻面）。 */
    private String triggerEventsJson(Collection<ReevalEvent> events) {
        List<Map<String, Object>> items = new ArrayList<>(events.size());
        for (ReevalEvent event : events) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("eventId", event.eventId());
            item.put("summary", event.summary());
            item.put("importance", event.importance() == null ? null : event.importance().name());
            items.add(item);
        }
        return writeJson(items);
    }

    private String droppedSubjectsJson(RankDiffer.Diff diff) {
        return writeJson(diff.dropped());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("联动 JSON 序列化失败（回落空结构）: {}", e.getMessage());
            return value instanceof List ? "[]" : "{}";
        }
    }
}
