package com.info.platform.application.markettop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.analysis.PipelineGuardService;
import com.info.platform.application.markettop.DeepDiveService.DiveResult;
import com.info.platform.domain.analysis.GuardLevel;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.markettop.DeepDiveInput;
import com.info.platform.domain.markettop.DeepDiveOutcome;
import com.info.platform.domain.markettop.MarketTopRepository;
import com.info.platform.domain.markettop.MarketTopRepository.MarketTopBatchRow;
import com.info.platform.domain.markettop.MarketTopRepository.MarketTopRankRow;
import com.info.platform.domain.markettop.MarketTopRepository.MarketTopVersion;
import com.info.platform.domain.markettop.RankDiffer.PrevSubject;
import com.info.platform.domain.markettop.SqueezeJudge.Candidate;
import com.info.platform.domain.markettop.SqueezeJudge.Verdict;
import com.info.platform.domain.valuation.FactorSnapshotRepository;
import com.info.platform.domain.valuation.FactorSnapshotRepository.PoolRow;
import com.info.platform.domain.valuation.IncrementalReevalRepository.ReevalEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * IncrementalTopService 联动编排单测（M22 T191，方案 §3.3 + ADR-0061 裁决 3，LLM 全 Mock）：version+1 追加不覆盖 +
 * trigger_source=EVENT + trigger_events 归因 / 不变成员深析成果直继承（dive_detail/method/generation 沿用 + final 按
 * mt-v1 公式重合成）/ 仅新入榜且当日无 FULL 补一次深析 / 触顶与护栏降级 FACTOR_ONLY 兜底 / diff 基准同日前一版本（无则昨日口径）。
 */
class IncrementalTopServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 28);

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-28T02:05:00Z"), ZoneOffset.UTC);

    /** 3 亮点/2 风险/5 引用 → diveScore = 25+30+20+25 = 100（继承重合成口径校准面）。 */
    private static final String FULL_DETAIL =
            "{\"thesis\":\"双升\",\"highlights\":[{\"text\":\"a\"},{\"text\":\"b\"},{\"text\":\"c\"}],"
                    + "\"risks\":[{\"text\":\"d\"},{\"text\":\"e\"}],"
                    + "\"citations\":[{\"type\":\"EVENT\",\"id\":1},{\"type\":\"EVENT\",\"id\":2},"
                    + "{\"type\":\"NEWS\",\"id\":3},{\"type\":\"NEWS\",\"id\":4},{\"type\":\"NEWS\",\"id\":5}]}";

    private MarketTopRepository repository;

    private FactorSnapshotRepository snapshotRepository;

    private DeepDiveService deepDiveService;

    private MarketTopService marketTopService;

    private PipelineGuardService guardService;

    private IncrementalTopService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repository = mock(MarketTopRepository.class);
        snapshotRepository = mock(FactorSnapshotRepository.class);
        deepDiveService = mock(DeepDiveService.class);
        marketTopService = mock(MarketTopService.class);
        guardService = mock(PipelineGuardService.class);
        MarketTopConfigSettings configSettings = mock(MarketTopConfigSettings.class);
        lenient().when(configSettings.current()).thenReturn(MarketTopConfig.defaults());
        lenient().when(guardService.currentLevel()).thenReturn(GuardLevel.NORMAL);
        lenient().when(guardService.todaySceneCostMicros(anyString())).thenReturn(0L);
        lenient().when(marketTopService.diveDetailJson(any())).thenReturn(FULL_DETAIL);
        lenient()
                .when(
                        marketTopService.assembleIncrementalInput(
                                any(PoolRow.class), any(LocalDate.class)))
                .thenReturn(diveInputFixture());
        service =
                new IncrementalTopService(
                        repository,
                        snapshotRepository,
                        deepDiveService,
                        marketTopService,
                        guardService,
                        configSettings,
                        objectMapper,
                        CLOCK);
    }

    // ---- 夹具 ----

    /** 补深析输入夹具（引用白名单单源——与 DeepDiveServiceTest 同形）。 */
    private static DeepDiveInput diveInputFixture() {
        return new DeepDiveInput(
                new com.info.platform.domain.markettop.DeepDiveInput.SubjectRef(
                        "SZ000011", "标的11", "机械设备"),
                List.of(
                        new com.info.platform.domain.markettop.DeepDiveInput.FactorDim(
                                "catalyst", "事件催化", 81.2, 0.40),
                        new com.info.platform.domain.markettop.DeepDiveInput.FactorDim(
                                "conduction", "行业传导", 29.4, 0.20),
                        new com.info.platform.domain.markettop.DeepDiveInput.FactorDim(
                                "fundamental", "基本面边际", 55.1, 0.20),
                        new com.info.platform.domain.markettop.DeepDiveInput.FactorDim(
                                "risk", "风险安全", 70.0, 0.20),
                        new com.info.platform.domain.markettop.DeepDiveInput.FactorDim(
                                "valuation", "估值水平", 50.0, 0.00)),
                70.0,
                99,
                true,
                List.of(),
                List.of(),
                List.of(),
                java.util.Map.of(),
                10,
                2,
                3);
    }

    private static ReevalEvent triggerEvent() {
        return new ReevalEvent(
                101L,
                "2026-09-28T01:50:00Z",
                "签订重大合同",
                Importance.HIGH,
                List.of("SZ300024"),
                List.of());
    }

    private static PoolRow poolRow(long subjectId, String code, double total) {
        return new PoolRow(
                subjectId,
                code,
                "标的" + subjectId,
                "机械设备",
                40.0,
                30.0,
                55.0,
                90.0,
                50.0,
                total,
                true,
                "{\"catalyst\":{\"entries\":[]}}",
                "vs-v1:…",
                "2026-09-28");
    }

    /** 判定结果：换入 11（70 分）挤出 10——newTop 为 SqueezeJudge 分序输出形态（final 降序）。 */
    private static Verdict swapVerdict() {
        List<Candidate> newTop = new java.util.ArrayList<>();
        newTop.add(new Candidate(11, "SZ000011", "标的11", 70.0));
        for (long id = 9; id >= 1; id--) {
            newTop.add(new Candidate(id, "SH00000" + id, "标的" + id, 60.0 + id));
        }
        return new Verdict(
                true,
                List.copyOf(newTop),
                List.of(
                        new com.info.platform.domain.markettop.SqueezeJudge.Swap(
                                new Candidate(11, "SZ000011", "标的11", 70.0),
                                new Candidate(10, "SH000010", "标的10", 65.0))));
    }

    /** 同日前一版本（v1）：1~9 在榜且为 FULL（dive_detail 可继承），10 在榜。 */
    private void stubPriorVersion() {
        when(repository.maxVersion(DATE.toString())).thenReturn(1);
        List<MarketTopRankRow> items = new java.util.ArrayList<>();
        for (long id = 1; id <= 10; id++) {
            items.add(
                    new MarketTopRankRow(
                            DATE.toString(),
                            1,
                            (int) id,
                            id,
                            "SH00000" + id,
                            "标的" + id,
                            55.0,
                            60.0,
                            90.0,
                            true,
                            id == 10 ? "FACTOR_ONLY" : "FULL",
                            id == 10 ? null : "LLM",
                            "摘要" + id,
                            id == 10 ? "{}" : FULL_DETAIL,
                            3,
                            "2026-09-28",
                            null,
                            "NEW",
                            "mt-v1:…",
                            "2026-09-28T01:00:00Z"));
        }
        when(repository.find(DATE.toString(), 1))
                .thenReturn(
                        Optional.of(
                                new MarketTopVersion(
                                        new MarketTopBatchRow(
                                                DATE.toString(),
                                                1,
                                                "DAILY",
                                                DATE.toString(),
                                                "{}",
                                                false,
                                                null,
                                                "[]",
                                                0L,
                                                0,
                                                null,
                                                "mt-v1:…",
                                                null,
                                                "2026-09-28T01:00:00Z"),
                                        items)));
        when(repository.findPreviousTop(anyString())).thenReturn(List.of());
    }

    private void stubPool() {
        List<PoolRow> rows = new java.util.ArrayList<>();
        for (long id = 1; id <= 9; id++) {
            rows.add(poolRow(id, "SH00000" + id, 55.0));
        }
        rows.add(poolRow(11, "SZ000011", 70.0));
        when(snapshotRepository.findPoolRowsByDate(DATE.toString())).thenReturn(rows);
    }

    // ---- 归因与版本追加 ----

    @Test
    void link_appendsNextVersionWithEventAttribution() {
        stubPriorVersion();
        stubPool();

        int version = service.link(DATE, swapVerdict(), List.of(triggerEvent()));

        assertThat(version).isEqualTo(2);
        ArgumentCaptor<MarketTopBatchRow> batchCaptor =
                ArgumentCaptor.forClass(MarketTopBatchRow.class);
        ArgumentCaptor<List<MarketTopRankRow>> ranksCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).insertVersion(batchCaptor.capture(), ranksCaptor.capture());
        MarketTopBatchRow batch = batchCaptor.getValue();
        assertThat(batch.triggerSource()).isEqualTo("EVENT");
        assertThat(batch.rankDate()).isEqualTo(DATE.toString());
        assertThat(batch.version()).isEqualTo(2);
        assertThat(batch.snapshotDate()).isEqualTo(DATE.toString());
        // 归因：该版本全部变动归因到触发事件（故事 2 场景 4）
        assertThat(batch.triggerEventsJson()).contains("\"eventId\":101").contains("签订重大合同");
        assertThat(ranksCaptor.getValue())
                .allSatisfy(row -> assertThat(row.version()).isEqualTo(2));
        assertThat(ranksCaptor.getValue())
                .extracting(MarketTopRankRow::rankNo)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    }

    @Test
    void link_unchangedMembersInheritDiveArtifactsAndRecomposeFinal() {
        stubPriorVersion();
        stubPool();

        service.link(DATE, swapVerdict(), List.of(triggerEvent()));

        ArgumentCaptor<List<MarketTopRankRow>> ranksCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).insertVersion(any(), ranksCaptor.capture());
        MarketTopRankRow first = rankOf(ranksCaptor.getValue(), 1);
        // 直继承：dive_detail/method/generation 沿用前版本行（零重析）
        assertThat(first.generation()).isEqualTo("FULL");
        assertThat(first.diveMethod()).isEqualTo("LLM");
        assertThat(first.diveDetailJson()).isEqualTo(FULL_DETAIL);
        // final 重合成（mt-v1）：total 55 + 继承 diveScore 100 → max(55, 0.8×55+0.2×100) = 64
        assertThat(first.finalScore()).isEqualTo(64.0);
        assertThat(first.totalScore()).isEqualTo(55.0);
    }

    @Test
    void link_onlyEntrantWithoutFullTodayGetsOneSupplementDive() {
        stubPriorVersion();
        stubPool();
        DeepDiveOutcome outcome =
                DeepDiveOutcome.llm(
                        new com.info.platform.domain.markettop.DeepDiveOutputParser.Parsed(
                                "订单饱满",
                                List.of(
                                        new com.info.platform.domain.markettop.DeepDiveOutputParser
                                                .Entry(
                                                "产能扩张",
                                                List.of(
                                                        new com.info.platform.domain.markettop
                                                                .Citation("EVENT", 101)))),
                                List.of(),
                                List.of()));
        when(deepDiveService.analyze(any(DeepDiveInput.class)))
                .thenReturn(new DiveResult(outcome, false, false, 0, "v1.0"));
        service.link(DATE, swapVerdict(), List.of(triggerEvent()));

        // 仅新入榜（11）补一次深析——不变成员零重析（成本护栏语义）
        verify(deepDiveService).analyze(any(DeepDiveInput.class));
        ArgumentCaptor<List<MarketTopRankRow>> ranksCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).insertVersion(any(), ranksCaptor.capture());
        MarketTopRankRow entrant = rankOf(ranksCaptor.getValue(), 11);
        assertThat(entrant.generation()).isEqualTo("FULL");
        assertThat(entrant.diveMethod()).isEqualTo("LLM");
        // final = max(70, 0.8×70 + 0.2×diveScore)（diveScore = 25+10+0+5 = 40 → 64 < 70）
        assertThat(entrant.finalScore()).isEqualTo(70.0);
        assertThat(entrant.changeType()).isEqualTo("NEW");
    }

    @Test
    void link_diveCapped_fallsBackToFactorOnly() {
        stubPriorVersion();
        stubPool();
        when(deepDiveService.analyze(any(DeepDiveInput.class))).thenReturn(DiveResult.capped());

        service.link(DATE, swapVerdict(), List.of(triggerEvent()));

        ArgumentCaptor<List<MarketTopRankRow>> ranksCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).insertVersion(any(), ranksCaptor.capture());
        MarketTopRankRow entrant = rankOf(ranksCaptor.getValue(), 11);
        // 兜底：FACTOR_ONLY 入榜页面明示（榜单恒产出）
        assertThat(entrant.generation()).isEqualTo("FACTOR_ONLY");
        assertThat(entrant.finalScore()).isEqualTo(70.0);
    }

    @Test
    void link_guardNotNormal_skipsDiveEntirely() {
        stubPriorVersion();
        stubPool();
        when(guardService.currentLevel()).thenReturn(GuardLevel.DEGRADED);

        service.link(DATE, swapVerdict(), List.of(triggerEvent()));

        verify(deepDiveService, never()).analyze(any());
        ArgumentCaptor<List<MarketTopRankRow>> ranksCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).insertVersion(any(), ranksCaptor.capture());
        assertThat(rankOf(ranksCaptor.getValue(), 11).generation()).isEqualTo("FACTOR_ONLY");
    }

    @Test
    void link_noSameDayVersion_diffBaseFallsBackToYesterday() {
        when(repository.maxVersion(DATE.toString())).thenReturn(0);
        when(repository.find(DATE.toString(), 1)).thenReturn(Optional.empty());
        when(repository.findPreviousTop(DATE.toString()))
                .thenReturn(
                        List.of(
                                new PrevSubject(1, "SH000001", "标的1", 2),
                                new PrevSubject(9, "SH000009", "标的9", 5)));
        stubPool();

        service.link(DATE, swapVerdict(), List.of(triggerEvent()));

        ArgumentCaptor<List<MarketTopRankRow>> ranksCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).insertVersion(any(), ranksCaptor.capture());
        // 昨日基准：1 从 2 → 10 位 = DOWN；9 从 5 → 2 位 = UP；新面孔 11 → NEW
        assertThat(rankOf(ranksCaptor.getValue(), 1).changeType()).isEqualTo("DOWN");
        assertThat(rankOf(ranksCaptor.getValue(), 1).prevRank()).isEqualTo(2);
        assertThat(rankOf(ranksCaptor.getValue(), 9).changeType()).isEqualTo("UP");
        assertThat(rankOf(ranksCaptor.getValue(), 11).changeType()).isEqualTo("NEW");
    }

    @Test
    void link_batchCarriesIncrementalFunnelStats() {
        stubPriorVersion();
        stubPool();

        service.link(DATE, swapVerdict(), List.of(triggerEvent()));

        ArgumentCaptor<MarketTopBatchRow> batchCaptor =
                ArgumentCaptor.forClass(MarketTopBatchRow.class);
        verify(repository).insertVersion(batchCaptor.capture(), anyList());
        JsonNode funnel = parse(batchCaptor.getValue().funnelStatsJson());
        assertThat(funnel.path("topSize").asInt()).isEqualTo(10);
        assertThat(funnel.path("source").asText()).isEqualTo("incremental");
    }

    private static MarketTopRankRow rankOf(List<MarketTopRankRow> rows, long subjectId) {
        return rows.stream().filter(row -> row.subjectId() == subjectId).findFirst().orElseThrow();
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
