package com.info.platform.application.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.valuation.FactorSnapshotRepository;
import com.info.platform.domain.valuation.FactorSnapshotRow;
import com.info.platform.domain.valuation.MarketDailySnapshotRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ValueScoreQueryService 单测（T170 coverage 对账 + T171 value-score 详情）：缺省日期取最新快照日、非法日期 30088、 标的评分
 * 404/30086、百分位查询层算（rank = 严格大于 + 1，并列同名次）、五维分解按当时权重（weight_basis 回读）、免责常驻。
 */
class ValueScoreQueryServiceTest {

    private FactorSnapshotRepository repository;
    private MarketDailySnapshotRepository marketRepository;
    private ValueScoreQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(FactorSnapshotRepository.class);
        marketRepository = mock(MarketDailySnapshotRepository.class);
        service = new ValueScoreQueryService(repository, marketRepository, new ObjectMapper());
    }

    @Test
    void coverage_fullSnapshot() {
        when(repository.findLatestSnapshotDate()).thenReturn(Optional.of("2026-09-21"));
        when(repository.countActiveSubjects()).thenReturn(5221L);
        when(repository.countByDate("2026-09-21")).thenReturn(5221L);
        when(marketRepository.countByDate("2026-09-21")).thenReturn(5189L);
        when(repository.countFlagged("2026-09-21", "NO_MARKET_DATA")).thenReturn(32L);
        when(repository.countFlagged("2026-09-21", "NO_ASSOC_INDUSTRY")).thenReturn(2901L);
        when(repository.countFlagged("2026-09-21", "NO_VALUATION_DATA")).thenReturn(0L);
        when(repository.countFlagged("2026-09-21", "ST_RISK")).thenReturn(201L);

        ValueScoreQueryService.CoverageView view = service.coverage(null);

        assertThat(view.snapshotDate()).isEqualTo("2026-09-21");
        assertThat(view.activeSubjects()).isEqualTo(5221);
        assertThat(view.snapshotRows()).isEqualTo(5221);
        assertThat(view.missingCount()).isZero();
        assertThat(view.coverageRate()).isEqualTo(100.0);
        assertThat(view.marketDataRows()).isEqualTo(5189);
        assertThat(view.flagCounts())
                .containsEntry("NO_MARKET_DATA", 32L)
                .containsEntry("NO_ASSOC_INDUSTRY", 2901L)
                .containsEntry("ST_RISK", 201L)
                .containsEntry("NO_VALUATION_DATA", 0L);
    }

    @Test
    void coverage_explicitDate_partialSnapshot() {
        when(repository.countActiveSubjects()).thenReturn(1000L);
        when(repository.countByDate("2026-09-18")).thenReturn(957L);
        when(marketRepository.countByDate("2026-09-18")).thenReturn(900L);
        when(repository.countFlagged(anyString(), anyString())).thenReturn(0L);

        ValueScoreQueryService.CoverageView view = service.coverage("2026-09-18");

        assertThat(view.snapshotDate()).isEqualTo("2026-09-18");
        assertThat(view.missingCount()).isEqualTo(43);
        assertThat(view.coverageRate()).isEqualTo(95.7);
    }

    @Test
    void coverage_invalidDate_rejected() {
        assertThatThrownBy(() -> service.coverage("2026/09/18"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(ErrorCode.VALUE_SCORE_QUERY_INVALID));
        assertThatThrownBy(() -> service.coverage("2026-13-40"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(ErrorCode.VALUE_SCORE_QUERY_INVALID));
    }

    @Test
    void coverage_noSnapshotDate_zeroView() {
        // Job 未跑过：最新快照日空 → 空口径如实返回（0 行 0 覆盖，不伪造数据）
        when(repository.findLatestSnapshotDate()).thenReturn(Optional.empty());
        when(repository.countActiveSubjects()).thenReturn(5221L);
        when(repository.countFlagged(anyString(), anyString())).thenReturn(0L);

        ValueScoreQueryService.CoverageView view = service.coverage(null);

        assertThat(view.snapshotDate()).isNull();
        assertThat(view.snapshotRows()).isZero();
        assertThat(view.coverageRate()).isZero();
        assertThat(view.missingCount()).isEqualTo(5221);
    }

    @Test
    void coverage_snapshotRowsExceedActive_missingClampedToZero() {
        // 快照日后停用的标的：行数可能 > 当前活跃数 → missing 不为负
        when(repository.findLatestSnapshotDate()).thenReturn(Optional.of("2026-09-21"));
        when(repository.countActiveSubjects()).thenReturn(100L);
        when(repository.countByDate("2026-09-21")).thenReturn(120L);
        when(marketRepository.countByDate("2026-09-21")).thenReturn(0L);
        when(repository.countFlagged(anyString(), anyString())).thenReturn(0L);

        ValueScoreQueryService.CoverageView view = service.coverage(null);

        assertThat(view.missingCount()).isZero();
        assertThat(view.coverageRate()).isEqualTo(100.0);
    }

    @Test
    void coverage_zeroActiveSubjects_guard() {
        when(repository.findLatestSnapshotDate()).thenReturn(Optional.of("2026-09-21"));
        when(repository.countActiveSubjects()).thenReturn(0L);
        when(repository.countByDate("2026-09-21")).thenReturn(0L);
        when(marketRepository.countByDate("2026-09-21")).thenReturn(0L);
        when(repository.countFlagged(anyString(), anyString())).thenReturn(0L);

        ValueScoreQueryService.CoverageView view = service.coverage(null);

        assertThat(view.coverageRate()).isZero(); // 除零守卫
        assertThat(view.missingCount()).isZero();
        assertThat(view.flagCounts()).hasSize(4); // 四 canonical 键常驻（0 值不隐藏口径）
        assertThat(view.flagCounts().values()).allMatch(v -> v == 0L);
    }

    // ---- value-score（T171，方案 §4.7.1）----

    private static final String DETAIL_JSON =
            "{\"catalyst\":{\"raw\":10.0,\"entries\":[{\"eventId\":101,\"summary\":\"业绩预增\","
                    + "\"eventDate\":\"2026-09-22\",\"direction\":\"BULLISH\","
                    + "\"importance\":\"HIGH\",\"coef\":1.0,\"decay\":1.0}]},"
                    + "\"conduction\":{\"assoc\":[{\"industry\":\"银行\",\"heatH24\":812.4,"
                    + "\"heatNorm\":1.0,\"lastSeenAge\":0,\"source\":\"EVENT\"}]},"
                    + "\"fundamental\":{\"raw\":1.0,\"entries\":[]},"
                    + "\"risk\":{\"stFlag\":false,\"eventPenalty\":0.0,\"entries\":[]},"
                    + "\"valuation\":{\"basis\":\"PE\",\"pe\":17.37,\"pct\":62.1,\"pb\":6.15}}";

    private static FactorSnapshotRow snapshotRow(String basis) {
        return new FactorSnapshotRow(
                101L,
                "2026-09-21",
                76.9,
                100.0,
                100.0,
                100.0,
                37.9,
                90.8,
                true,
                DETAIL_JSON,
                "[\"ST_RISK\"]",
                basis,
                "2026-09-21T09:30:00Z");
    }

    @Test
    void valueScore_composedView_withQueryLayerPercentile() {
        when(repository.findLatestBySubject(101L))
                .thenReturn(
                        Optional.of(
                                snapshotRow(
                                        "vs-v1:w=0.40|0.20|0.20|0.20|0.00;win=10|30;hl=5.0;k=3.0|1.5;bt=60|50|80")));
        when(repository.countByDate("2026-09-21")).thenReturn(100L);
        when(repository.countScoreGreaterThan("2026-09-21", 90.8)).thenReturn(17L);

        ValueScoreQueryService.ScoreView view = service.valueScore(101L);

        assertThat(view.subjectId()).isEqualTo(101L);
        assertThat(view.snapshotDate()).isEqualTo("2026-09-21");
        assertThat(view.totalScore()).isEqualTo(90.8);
        assertThat(view.breakthrough()).isTrue();
        // rank = 17 + 1 = 18；percentile = round(100×(100−18)/99) = 83
        assertThat(view.rank()).isEqualTo(18L);
        assertThat(view.percentile()).isEqualTo(83L);
        assertThat(view.computedAt()).isEqualTo("2026-09-21T09:30:00Z");
        assertThat(view.disclaimer()).contains("不构成投资建议");
        assertThat(view.dataFlags()).containsExactly("ST_RISK");
        // 五维分解按当时权重（weight_basis 回读——历史快照不被当前配置改写）
        assertThat(view.factors()).hasSize(5);
        assertThat(view.factors().get(0).key()).isEqualTo("catalyst");
        assertThat(view.factors().get(0).name()).isEqualTo("事件催化");
        assertThat(view.factors().get(0).score()).isEqualTo(76.9);
        assertThat(view.factors().get(0).weight()).isEqualTo(0.40);
        assertThat(view.factors().get(4).key()).isEqualTo("valuation");
        assertThat(view.factors().get(4).weight()).isEqualTo(0.00);
        assertThat(view.factors().get(4).neutral()).isFalse(); // detail.basis=PE 非缺数
        // detail 原样透传（§4.5 契约，trace-v1 下钻原料）
        assertThat(view.detail().path("catalyst").path("entries").get(0).path("eventId").asLong())
                .isEqualTo(101L);
        assertThat(view.detail().path("valuation").path("basis").asText()).isEqualTo("PE");
    }

    @Test
    void valueScore_valuationNeutral_flaggedNeutral() {
        String neutralDetail = DETAIL_JSON.replace("\"basis\":\"PE\"", "\"basis\":null");
        FactorSnapshotRow row =
                snapshotRow(
                        "vs-v1:w=0.40|0.20|0.20|0.20|0.00;win=10|30;hl=5.0;k=3.0|1.5;bt=60|50|80");
        FactorSnapshotRow neutralRow =
                new FactorSnapshotRow(
                        row.subjectId(),
                        row.snapshotDate(),
                        row.fCatalyst(),
                        row.fConduction(),
                        row.fFundamental(),
                        row.fRisk(),
                        50.0,
                        row.totalScore(),
                        row.breakthrough(),
                        neutralDetail,
                        row.dataFlagsJson(),
                        row.weightBasis(),
                        row.computedAtIso());
        when(repository.findLatestBySubject(101L)).thenReturn(Optional.of(neutralRow));
        when(repository.countByDate("2026-09-21")).thenReturn(1L);
        when(repository.countScoreGreaterThan("2026-09-21", 90.8)).thenReturn(0L);

        ValueScoreQueryService.ScoreView view = service.valueScore(101L);

        assertThat(view.factors().get(4).neutral()).isTrue(); // 缺数中性态（前端弱化样式依据）
        assertThat(view.percentile()).isZero(); // 单行样本：rank1/N=1 → percentile 0（除零守卫）
        assertThat(view.rank()).isEqualTo(1L);
    }

    @Test
    void valueScore_topRank_percentileHundred_withTies() {
        // 并列最高：严格大于计数 0 → rank 1 → percentile 100（并列同名次）
        when(repository.findLatestBySubject(1L))
                .thenReturn(
                        Optional.of(
                                snapshotRow(
                                        "vs-v1:w=0.40|0.20|0.20|0.20|0.00;win=10|30;hl=5.0;k=3.0|1.5;bt=60|50|80")));
        when(repository.countByDate("2026-09-21")).thenReturn(5221L);
        when(repository.countScoreGreaterThan("2026-09-21", 90.8)).thenReturn(0L);

        assertThat(service.valueScore(1L).percentile()).isEqualTo(100L);
    }

    @Test
    void valueScore_noSnapshot_404_30086() {
        when(repository.findLatestBySubject(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.valueScore(999L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(ErrorCode.VALUE_SCORE_NOT_FOUND));
    }

    @Test
    void valueScore_weightsParsedFromHistoricalBasis_notCurrentConfig() {
        // 权重改过之后的旧快照：分解展示仍按当时权重（0.50|0.10|…——审计口径不被现值污染）
        when(repository.findLatestBySubject(101L))
                .thenReturn(
                        Optional.of(
                                snapshotRow(
                                        "vs-v1:w=0.50|0.10|0.20|0.20|0.00;win=10|30;hl=5.0;k=3.0|1.5;bt=60|50|80")));
        when(repository.countByDate("2026-09-21")).thenReturn(10L);
        when(repository.countScoreGreaterThan("2026-09-21", 90.8)).thenReturn(0L);

        ValueScoreQueryService.ScoreView view = service.valueScore(101L);

        assertThat(view.factors().get(0).weight()).isEqualTo(0.50);
        assertThat(view.factors().get(1).weight()).isEqualTo(0.10);
    }
}
