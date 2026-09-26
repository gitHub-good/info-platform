package com.info.platform.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 走向判断规则信号层单测（M17 T146，REQ 拍板六 / 故事 2 场景 4）：信号矩阵（升温/降温/平稳阈值 ±20%）、置信度 trend-v1 锁定映射（高 =
 * 强度 ≥50% 且事件密度 ≥3 且密度环比上升；中 = 信号达成；低 = 平稳/弱信号）、prev=0 记 100 口径、basis 版本串。
 */
class TrendSignalCalculatorTest {

    private static TrendSignalCalculator.TrendInput input(
            double weekScore, double prevScore, long weekEvents, long prevWeekEvents, long policy) {
        return new TrendSignalCalculator.TrendInput(
                "银行", weekScore, prevScore, weekEvents, prevWeekEvents, policy);
    }

    @Test
    @DisplayName("信号矩阵：≥+20% 升温 / ≤-20% 降温 / 区间内平稳 / 双 0 平稳")
    void signalMatrix_thresholds() {
        assertThat(TrendSignalCalculator.compute(input(120, 100, 1, 1, 0)).signal())
                .isEqualTo(TrendSignalCalculator.TrendSignal.HEATING);
        assertThat(TrendSignalCalculator.compute(input(80, 100, 1, 1, 0)).signal())
                .isEqualTo(TrendSignalCalculator.TrendSignal.COOLING);
        assertThat(TrendSignalCalculator.compute(input(105, 100, 1, 1, 0)).signal())
                .isEqualTo(TrendSignalCalculator.TrendSignal.STABLE);
        assertThat(TrendSignalCalculator.compute(input(0, 0, 0, 0, 0)).signal())
                .isEqualTo(TrendSignalCalculator.TrendSignal.STABLE);
        // 边界恰达阈值即成信号
        assertThat(TrendSignalCalculator.compute(input(120, 100, 1, 1, 0)).deltaPct())
                .isEqualTo(20.0);
    }

    @Test
    @DisplayName("prev=0 记 100（沿 heat-v1 deltaPctOf 口径）")
    void deltaPct_prevZeroRecords100() {
        assertThat(TrendSignalCalculator.compute(input(30, 0, 1, 0, 0)).deltaPct())
                .isEqualTo(100.0);
    }

    @Test
    @DisplayName("置信度映射：高=强度≥50% 且密度≥3 且环比上升")
    void confidence_highRequiresStrongDeltaAndDensityUp() {
        assertThat(
                        TrendSignalCalculator.compute(input(200, 100, 3, 1, 0)).confidence())
                .isEqualTo(TrendSignalCalculator.TrendConfidence.HIGH);
        // 密度未超阈值 → 中
        assertThat(TrendSignalCalculator.compute(input(200, 100, 2, 1, 0)).confidence())
                .isEqualTo(TrendSignalCalculator.TrendConfidence.MEDIUM);
        // 密度未环比上升 → 中
        assertThat(TrendSignalCalculator.compute(input(200, 100, 4, 4, 0)).confidence())
                .isEqualTo(TrendSignalCalculator.TrendConfidence.MEDIUM);
        // 强度不足（20%~50%）→ 中
        assertThat(TrendSignalCalculator.compute(input(130, 100, 3, 1, 0)).confidence())
                .isEqualTo(TrendSignalCalculator.TrendConfidence.MEDIUM);
    }

    @Test
    @DisplayName("置信度映射：平稳/弱信号 → 低；降温方向对称")
    void confidence_lowForStableAndSymmetricForCooling() {
        assertThat(TrendSignalCalculator.compute(input(105, 100, 3, 1, 0)).confidence())
                .isEqualTo(TrendSignalCalculator.TrendConfidence.LOW);
        assertThat(TrendSignalCalculator.compute(input(40, 100, 4, 2, 0)).confidence())
                .isEqualTo(TrendSignalCalculator.TrendConfidence.HIGH);
        assertThat(TrendSignalCalculator.compute(input(40, 100, 4, 2, 0)).signal())
                .isEqualTo(TrendSignalCalculator.TrendSignal.COOLING);
    }

    @Test
    @DisplayName("口径版本串锁定 trend-v1（沿 heat-v1/recscore-v1/adopt-v1 先例）")
    void basis_lockedVersionString() {
        assertThat(TrendSignalCalculator.BASIS).isEqualTo("trend-v1");
    }

    @Test
    @DisplayName("信号与置信度中文展示名（渲染面）")
    void displayNames() {
        assertThat(TrendSignalCalculator.TrendSignal.HEATING.displayName()).isEqualTo("升温");
        assertThat(TrendSignalCalculator.TrendSignal.COOLING.displayName()).isEqualTo("降温");
        assertThat(TrendSignalCalculator.TrendSignal.STABLE.displayName()).isEqualTo("平稳");
        assertThat(TrendSignalCalculator.TrendConfidence.HIGH.displayName()).isEqualTo("高");
        assertThat(TrendSignalCalculator.TrendConfidence.LOW.displayName()).isEqualTo("低");
    }
}
