package com.info.platform.application.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.valuation.FactorSnapshotRepository;
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
        service = new ValueScoreQueryService(repository, marketRepository);
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
}
