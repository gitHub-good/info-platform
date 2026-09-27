package com.info.platform.application.markettop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.analysis.PipelineGuardService;
import com.info.platform.application.markettop.DeepDiveService.DiveResult;
import com.info.platform.application.markettop.IndustryMemberBackfillService.BackfillReport;
import com.info.platform.application.markettop.MarketTopService.GenerationReport;
import com.info.platform.domain.markettop.DeepDiveInput;
import com.info.platform.domain.markettop.DeepDiveOutcome;
import com.info.platform.domain.markettop.DeepDiveOutputParser.Parsed;
import com.info.platform.domain.markettop.MarketTopRepository;
import com.info.platform.domain.markettop.MarketTopRepository.MarketTopBatchRow;
import com.info.platform.domain.markettop.MarketTopRepository.MarketTopRankRow;
import com.info.platform.domain.markettop.RankDiffer.PrevSubject;
import com.info.platform.domain.valuation.FactorSnapshotRepository;
import com.info.platform.domain.valuation.FactorSnapshotRepository.PoolRow;
import com.info.platform.domain.valuation.MarketDailySnapshotRepository;
import com.info.platform.domain.valuation.ValuationParams;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * MarketTopService 单测（M21 T183，方案 §4.6 四阶段编排 + §6 测试要点，LLM 全 Mock 零外呼）： 阶段编排全链落库 / 层数断言中止不落库 /
 * 触顶降级三档（单次失败模板兜底·连续失败中止·成本触顶停剩余——degraded 榜单仍产出） / 版本化追加与幂等（同日重跑 version+1、items 逐字段一致） / 快照日守卫跳过
 * / RankDiffer 接线 / 深析输入白名单源组装。
 */
@ExtendWith(MockitoExtension.class)
class MarketTopServiceTest {

    private static final LocalDate RANK_DATE = LocalDate.of(2026, 9, 22);

    private static final String RANK_DATE_TEXT = "2026-09-22";

    /** 15 行快照（> 池 10，满足严格层数链）。 */
    private static final int ROWS = 15;

    /** 池 10 / 深析 10（diversion 于缺省 300/40——读侧无值域校验，测试夹具直造）。 */
    private static final MarketTopConfig CONFIG = new MarketTopConfig(10, 10, 0.30, 100_000L, 0.80);

    private static final String DETAIL_JSON =
            """
            {"catalyst":{"raw":0.75,"entries":[
              {"eventId":101,"summary":"签订重大合同","eventDate":"2026-09-18","direction":"BULLISH",
               "importance":"HIGH","coef":0.75}]},
             "fundamental":{"raw":0.2,"entries":[
              {"eventId":102,"summary":"业绩预增","eventDate":"2026-09-20","direction":"BULLISH",
               "importance":"MEDIUM","coef":0.35}]},
             "risk":{"raw":0.1,"entries":[
              {"eventId":103,"summary":"监管问询","eventDate":"2026-09-21","direction":"BEARISH",
               "importance":"MEDIUM","coef":0.3}]},
             "conduction":{"assoc":[]},"valuation":{"basis":null}}
            """;

    @Mock private IndustryMemberBackfillService backfillService;

    @Mock private FactorSnapshotRepository snapshotRepository;

    @Mock private MarketDailySnapshotRepository marketRepository;

    @Mock private DeepDiveNewsStore newsStore;

    @Mock private DeepDiveService deepDiveService;

    @Mock private MarketTopRepository repository;

    @Mock private MarketTopConfigSettings configSettings;

    @Mock private PipelineGuardService guardService;

    private MarketTopService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-22T10:00:00Z"), ZoneOffset.UTC);
        service =
                new MarketTopService(
                        backfillService,
                        snapshotRepository,
                        marketRepository,
                        newsStore,
                        deepDiveService,
                        repository,
                        configSettings,
                        guardService,
                        objectMapper,
                        clock,
                        0L);
        lenient().when(configSettings.current()).thenReturn(CONFIG);
        lenient()
                .when(snapshotRepository.findLatestSnapshotDate())
                .thenReturn(Optional.of(RANK_DATE_TEXT));
        lenient().when(snapshotRepository.findPoolRowsByDate(RANK_DATE_TEXT)).thenReturn(rows());
        lenient()
                .when(backfillService.backfillIfBelowFloor())
                .thenReturn(new BackfillReport(5221, 4900, 4900, false, 0, 0));
        lenient().when(repository.maxVersion(anyString())).thenReturn(0);
        lenient().when(repository.findPreviousTop(anyString())).thenReturn(List.of());
        lenient().when(snapshotRepository.findH24Heat()).thenReturn(List.of());
        lenient().when(marketRepository.findByDate(anyString())).thenReturn(Map.of());
        lenient()
                .when(newsStore.findRelatedNews(anyString(), anyString(), anyInt()))
                .thenReturn(List.of());
        lenient()
                .when(newsStore.findIndustryNews(anyString(), anyString(), anyInt()))
                .thenReturn(List.of());
        lenient()
                .when(deepDiveService.analyze(any(DeepDiveInput.class)))
                .thenReturn(new DiveResult(llmOutcome(), false, false, 0, "v1.0"));
        lenient().when(guardService.todaySceneCostMicros(anyString())).thenReturn(120_000L);
    }

    private static List<PoolRow> rows() {
        List<PoolRow> rows = new ArrayList<>();
        for (long id = 1; id <= ROWS; id++) {
            rows.add(
                    new PoolRow(
                            id,
                            "SH" + id,
                            "标的" + id,
                            id == 1 ? "消费电子" : null,
                            60.0 - id,
                            10.0 + id,
                            20.0,
                            5.0,
                            50.0,
                            50.0 + ROWS - id,
                            id <= 5,
                            DETAIL_JSON,
                            ValuationParams.defaults().basis(),
                            "2026-09-2" + (id % 10)));
        }
        return rows;
    }

    private static DeepDiveOutcome llmOutcome() {
        Parsed parsed =
                new Parsed(
                        "论点：事件催化驱动",
                        List.of(
                                new com.info.platform.domain.markettop.DeepDiveOutputParser.Entry(
                                        "签订重大合同",
                                        List.of(
                                                new com.info.platform.domain.markettop.Citation(
                                                        "EVENT", 101))),
                                new com.info.platform.domain.markettop.DeepDiveOutputParser.Entry(
                                        "行业景气",
                                        List.of(
                                                new com.info.platform.domain.markettop.Citation(
                                                        "EVENT", 101)))),
                        List.of(
                                new com.info.platform.domain.markettop.DeepDiveOutputParser.Entry(
                                        "竞争加剧",
                                        List.of(
                                                new com.info.platform.domain.markettop.Citation(
                                                        "EVENT", 103))),
                                new com.info.platform.domain.markettop.DeepDiveOutputParser.Entry(
                                        "估值偏高",
                                        List.of(
                                                new com.info.platform.domain.markettop.Citation(
                                                        "EVENT", 103)))),
                        List.of());
        return DeepDiveOutcome.llm(parsed);
    }

    // ---- 阶段编排全链 ----

    @Test
    void generate_happyPath_fullChainPersistsVersion() {
        GenerationReport report = service.generate(RANK_DATE);

        assertThat(report.status()).isEqualTo(GenerationReport.STATUS_SUCCESS);
        assertThat(report.version()).isEqualTo(1);
        assertThat(report.topSize()).isEqualTo(10);
        assertThat(report.degraded()).isFalse();

        ArgumentCaptor<MarketTopBatchRow> batchCaptor =
                ArgumentCaptor.forClass(MarketTopBatchRow.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketTopRankRow>> ranksCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).insertVersion(batchCaptor.capture(), ranksCaptor.capture());

        MarketTopBatchRow batch = batchCaptor.getValue();
        assertThat(batch.rankDate()).isEqualTo(RANK_DATE_TEXT);
        assertThat(batch.version()).isEqualTo(1);
        assertThat(batch.triggerSource()).isEqualTo("DAILY");
        assertThat(batch.snapshotDate()).isEqualTo(RANK_DATE_TEXT);
        assertThat(batch.degraded()).isFalse();
        assertThat(batch.diveCostMicros()).isEqualTo(120_000L);
        assertThat(batch.diveLlmCalls()).isEqualTo(10);
        assertThat(batch.promptVersion()).isEqualTo("v1.0");
        assertThat(batch.basis())
                .isEqualTo(
                        "mt-v1:final=max(total,0.8*total+0.2*dive);dive=25|10x3|10x2|5x5;"
                                + "pool=10;dive=10;cap=0.30");
        assertThat(batch.funnelStatsJson())
                .contains("\"snapshotRows\":15")
                .contains("\"poolSize\":10")
                .contains("\"divePlanned\":10")
                .contains("\"diveDone\":10")
                .contains("\"topSize\":10");

        List<MarketTopRankRow> ranks = ranksCaptor.getValue();
        assertThat(ranks).hasSize(10);
        assertThat(ranks.get(0).rankNo()).isEqualTo(1);
        assertThat(ranks.get(0).subjectId()).isEqualTo(1L); // 最高总分
        assertThat(ranks.get(0).changeType()).isEqualTo("NEW"); // 首日全 NEW
        assertThat(ranks.get(0).generation()).isEqualTo("FULL");
        assertThat(ranks.get(0).diveMethod()).isEqualTo("LLM");
        assertThat(ranks.get(0).percentile()).isEqualTo(100.0);
        assertThat(ranks.get(0).evidenceCount()).isEqualTo(3);
        assertThat(ranks.get(0).computedAt()).isEqualTo("2026-09-22T10:00:00Z");
    }

    @Test
    void generate_deepDiveInputAssembledFromWhitelistSources() {
        when(snapshotRepository.findH24Heat())
                .thenReturn(
                        List.of(
                                new com.info.platform.domain.valuation.HeatRow("电子", 88.0),
                                new com.info.platform.domain.valuation.HeatRow("银行", 66.0)));
        when(marketRepository.findByDate(RANK_DATE_TEXT)).thenReturn(Map.of(1L, marketRow()));
        when(newsStore.findRelatedNews(anyString(), anyString(), anyInt()))
                .thenReturn(
                        List.of(
                                new DeepDiveInput.NewsFact(
                                        901L, "机器人产业政策出台", "2026-09-20T08:00:00Z", "财联社")));

        service.generate(RANK_DATE);

        ArgumentCaptor<DeepDiveInput> captor = ArgumentCaptor.forClass(DeepDiveInput.class);
        verify(deepDiveService, org.mockito.Mockito.atLeastOnce()).analyze(captor.capture());
        DeepDiveInput first = captor.getAllValues().get(0); // dive 候选首只 = subjectId 1

        assertThat(first.subject().code()).isEqualTo("SH1");
        assertThat(first.subject().industry()).isEqualTo("电子"); // 消费电子 → swPrimaryOf → 电子
        // Top 依据事件 = catalyst + risk 条目按贡献降序（§4.4.2——fundamental 条目不进 topEvents）
        assertThat(first.topEvents()).hasSize(2);
        assertThat(first.topEvents().get(0).eventId()).isEqualTo(101L); // coef 降序
        assertThat(first.relatedNews())
                .extracting(DeepDiveInput.NewsFact::newsId)
                .containsExactly(901L);
        assertThat(first.marketSnapshot()).containsKeys("close", "pctChange", "pe");
        assertThat(first.totalEventCount()).isEqualTo(3);
        assertThat(first.eventWindowDays()).isEqualTo(10);
        assertThat(first.industryHeatRank()).isEqualTo(1); // 电子 24h 热度第 1
        // 引用白名单 = 输入 id 集（EVENT 101/103 ∪ NEWS 901——白名单源即输入，防两处组装漂移）
        assertThat(first.citationWhitelist())
                .extracting(c -> c.type() + ":" + c.id())
                .containsExactlyInAnyOrder("EVENT:101", "EVENT:103", "NEWS:901");
    }

    // ---- 幂等与版本化追加 ----

    @Test
    void generate_sameDayRerun_versionIncrementedItemsIdentical() {
        service.generate(RANK_DATE);
        when(repository.maxVersion(RANK_DATE_TEXT)).thenReturn(1); // 第二轮读到既有 v1

        service.generate(RANK_DATE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketTopRankRow>> ranksCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository, org.mockito.Mockito.times(2))
                .insertVersion(any(MarketTopBatchRow.class), ranksCaptor.capture());
        List<List<MarketTopRankRow>> versions = ranksCaptor.getAllValues();

        // version 递增追加不覆盖：v1 与 v2 items 逐字段一致（version 字段除外——同输入同榜单幂等红线）
        List<MarketTopRankRow> v1 = versions.get(0);
        List<MarketTopRankRow> v2 = versions.get(1);
        assertThat(v1).hasSameSizeAs(v2);
        for (int i = 0; i < v1.size(); i++) {
            MarketTopRankRow first = v1.get(i);
            MarketTopRankRow second = withVersion(v2.get(i), first.version());
            assertThat(second).isEqualTo(first);
        }
        assertThat(v2.get(0).version()).isEqualTo(2);
    }

    private static MarketTopRankRow withVersion(MarketTopRankRow row, int version) {
        return new MarketTopRankRow(
                row.rankDate(),
                version,
                row.rankNo(),
                row.subjectId(),
                row.subjectCode(),
                row.subjectName(),
                row.totalScore(),
                row.finalScore(),
                row.percentile(),
                row.breakthrough(),
                row.generation(),
                row.diveMethod(),
                row.diveSummary(),
                row.diveDetailJson(),
                row.evidenceCount(),
                row.lastEventDate(),
                row.prevRank(),
                row.changeType(),
                row.basis(),
                row.computedAt());
    }

    // ---- 快照日守卫 ----

    @Test
    void generate_snapshotGuardSkipsWhenNoSnapshotForDate() {
        when(snapshotRepository.findLatestSnapshotDate())
                .thenReturn(Optional.of("2026-09-21")); // 当日快照未出

        GenerationReport report = service.generate(RANK_DATE);

        assertThat(report.skipped()).isTrue();
        assertThat(report.reason()).contains("2026-09-21");
        verify(backfillService, never()).backfillIfBelowFloor();
        verify(repository, never()).insertVersion(any(), any());
    }

    // ---- 降级三档 ----

    @Test
    void generate_costCapDegradedRankStillProduced() {
        // 之③：成本触顶停剩余——榜单恒产出（factor_only 全量）
        when(deepDiveService.analyze(any(DeepDiveInput.class))).thenReturn(DiveResult.capped());

        GenerationReport report = service.generate(RANK_DATE);

        assertThat(report.status()).isEqualTo(GenerationReport.STATUS_SUCCESS);
        assertThat(report.degraded()).isTrue();
        assertThat(report.degradedReason()).isEqualTo(MarketTopService.DEGRADED_COST_CAP);
        assertThat(report.topSize()).isEqualTo(10);
        assertThat(report.diveDone()).isZero();
        assertThat(report.diveSkipped()).isEqualTo(10);

        ArgumentCaptor<MarketTopBatchRow> batchCaptor =
                ArgumentCaptor.forClass(MarketTopBatchRow.class);
        verify(repository).insertVersion(batchCaptor.capture(), any());
        assertThat(batchCaptor.getValue().degraded()).isTrue();
        assertThat(batchCaptor.getValue().degradedReason()).isEqualTo("COST_CAP");
        assertThat(batchCaptor.getValue().diveLlmCalls()).isZero();
    }

    @Test
    void generate_consecutiveLlmFailuresAbortsRemaining() {
        // 之②：前 5 只调用失败（当前标的模板兜底）→ 第 5 只后中止剩余（factor_only）
        when(deepDiveService.analyze(any(DeepDiveInput.class)))
                .thenReturn(
                        new DiveResult(
                                DeepDiveOutcome.template("模板文案近10日 3 条关联事件。"),
                                true,
                                false,
                                0,
                                "v1.0"));

        GenerationReport report = service.generate(RANK_DATE);

        assertThat(report.degraded()).isTrue();
        assertThat(report.degradedReason()).isEqualTo(MarketTopService.DEGRADED_LLM_FAILURE);
        assertThat(report.diveTemplate()).isEqualTo(5);
        assertThat(report.diveSkipped()).isEqualTo(5);
        assertThat(report.topSize()).isEqualTo(10); // 榜单仍产出
    }

    @Test
    void generate_singleFailureTemplateFallbackNoAbort() {
        // 之①：单次失败模板兜底不重试；后续恢复 → 不触发中止、不降级
        when(deepDiveService.analyze(any(DeepDiveInput.class)))
                .thenReturn(
                        new DiveResult(DeepDiveOutcome.template("模板文案"), true, false, 0, "v1.0"))
                .thenReturn(new DiveResult(llmOutcome(), false, false, 0, "v1.0"));

        GenerationReport report = service.generate(RANK_DATE);

        assertThat(report.degraded()).isFalse();
        assertThat(report.diveTemplate()).isEqualTo(1);
        assertThat(report.diveDone()).isEqualTo(9);
        assertThat(report.diveSkipped()).isZero();
    }

    // ---- 层数断言中止 ----

    @Test
    void generate_layerAssertionViolated_persistsNothing() {
        // 池吞全量（poolSize ≥ 快照行数）→ 严格链快照行>池违反 → FAILED 中止落库
        when(configSettings.current())
                .thenReturn(new MarketTopConfig(ROWS, 10, 0.30, 100_000L, 0.80));

        GenerationReport report = service.generate(RANK_DATE);

        assertThat(report.status()).isEqualTo(GenerationReport.STATUS_FAILED);
        assertThat(report.reason()).contains("漏斗层数断言违反");
        verify(repository, never()).insertVersion(any(), any());
    }

    // ---- RankDiffer 接线 ----

    @Test
    void generate_previousDayDiff_writesChangeAndDropped() {
        when(repository.findPreviousTop(RANK_DATE_TEXT))
                .thenReturn(
                        List.of(
                                new PrevSubject(2L, "SH2", "标的2", 1),
                                new PrevSubject(1L, "SH1", "标的1", 2),
                                new PrevSubject(99L, "SH99", "昨日跌出标的", 3)));

        service.generate(RANK_DATE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketTopRankRow>> ranksCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<MarketTopBatchRow> batchCaptor =
                ArgumentCaptor.forClass(MarketTopBatchRow.class);
        verify(repository).insertVersion(batchCaptor.capture(), ranksCaptor.capture());

        Map<Long, MarketTopRankRow> bySubject = new LinkedHashMap<>();
        for (MarketTopRankRow rank : ranksCaptor.getValue()) {
            bySubject.put(rank.subjectId(), rank);
        }
        // subjectId 1 昨日第 2 → 今日第 1（UP）；subjectId 2 昨日第 1 → 今日第 2（DOWN）；99 跌出
        assertThat(bySubject.get(1L).changeType()).isEqualTo("UP");
        assertThat(bySubject.get(1L).prevRank()).isEqualTo(2);
        assertThat(bySubject.get(2L).changeType()).isEqualTo("DOWN");
        assertThat(batchCaptor.getValue().droppedSubjectsJson())
                .contains("SH99")
                .contains("prevRank\":3");
    }

    private static MarketDailySnapshotRepository.MarketDailyRow marketRow() {
        return new MarketDailySnapshotRepository.MarketDailyRow(
                1L, RANK_DATE_TEXT, 12.34, 2.1, 3.2, null, null, 45.6, null, "tencent", null);
    }
}
