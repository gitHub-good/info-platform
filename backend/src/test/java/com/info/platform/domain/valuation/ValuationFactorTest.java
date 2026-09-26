package com.info.platform.domain.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * F5 估值水平单测（T170，方案 §4.2 + ADR-0058 裁决 5）：横截面升序百分位（低 PE → 低 pct → 高分）、PE→PB 回退链、 缺数中性 50、并列同位、N=1
 * 边界、≤0 视为缺数。
 */
class ValuationFactorTest {

    @Test
    void lowestPe_scoresHundred() {
        ValuationFactor.Result result =
                ValuationFactor.compute(5.0, null, List.of(5.0, 10.0, 20.0), List.of());

        assertThat(result.basis()).isEqualTo("PE");
        assertThat(result.pe()).isEqualTo(5.0);
        assertThat(result.pct()).isZero();
        assertThat(result.score()).isEqualTo(100.0);
        assertThat(result.neutral()).isFalse();
    }

    @Test
    void middlePe_scoresFifty() {
        // pe=10 在 [5,10,20]：rank2 → pct=100×(2−1)/(3−1)=50 → F5=50
        ValuationFactor.Result result =
                ValuationFactor.compute(10.0, null, List.of(5.0, 10.0, 20.0), List.of());

        assertThat(result.pct()).isEqualTo(50.0);
        assertThat(result.score()).isEqualTo(50.0);
    }

    @Test
    void highestPe_scoresZero() {
        ValuationFactor.Result result =
                ValuationFactor.compute(20.0, null, List.of(5.0, 10.0, 20.0), List.of());

        assertThat(result.pct()).isEqualTo(100.0);
        assertThat(result.score()).isZero();
    }

    @Test
    void peMissing_fallsBackToPb() {
        ValuationFactor.Result result =
                ValuationFactor.compute(null, 1.0, List.of(8.0, 12.0), List.of(1.0, 2.0));

        assertThat(result.basis()).isEqualTo("PB");
        assertThat(result.pb()).isEqualTo(1.0);
        assertThat(result.pct()).isZero(); // 最低 PB
        assertThat(result.score()).isEqualTo(100.0);
    }

    @Test
    void nonPositivePe_treatedAsMissing_pbFallback() {
        // 亏损股 PE ≤0 存缺（Spike-A：PE 填充 76%）→ PB 回退链
        ValuationFactor.Result result =
                ValuationFactor.compute(-5.0, 2.0, List.of(8.0, -5.0), List.of(1.0, 2.0));

        assertThat(result.basis()).isEqualTo("PB");
        assertThat(result.pct()).isEqualTo(100.0); // pb 2.0 为截面最高
        assertThat(result.score()).isZero();
    }

    @Test
    void bothMissing_neutralFifty() {
        ValuationFactor.Result result =
                ValuationFactor.compute(null, null, List.of(8.0), List.of(1.0));

        assertThat(result.neutral()).isTrue();
        assertThat(result.score()).isEqualTo(50.0);
        assertThat(result.basis()).isNull();
    }

    @Test
    void nonPositivePb_alsoMissing() {
        ValuationFactor.Result result =
                ValuationFactor.compute(null, 0.0, List.of(), List.of(0.0, 1.0));

        assertThat(result.neutral()).isTrue();
        assertThat(result.score()).isEqualTo(50.0);
    }

    @Test
    void percentileTies_shareSamePosition() {
        // 并列 10：严格小于计数 → rank1 → pct0（两名并列最低同分；20 独占最高）
        ValuationFactor.Result tie =
                ValuationFactor.compute(10.0, null, List.of(10.0, 10.0, 20.0), List.of());
        ValuationFactor.Result top =
                ValuationFactor.compute(20.0, null, List.of(10.0, 10.0, 20.0), List.of());

        assertThat(tie.pct()).isZero();
        assertThat(top.pct()).isEqualTo(100.0);
    }

    @Test
    void singleSample_percentileZeroNotDivideByZero() {
        ValuationFactor.Result result = ValuationFactor.compute(8.0, null, List.of(8.0), List.of());

        assertThat(result.pct()).isZero();
        assertThat(result.score()).isEqualTo(100.0);
    }

    @Test
    void emptyCrossSection_pePresent_stillValued() {
        // 全市场截面空（当日无其他行）→ 单样本口径（rank1/pct0），不误判缺数
        ValuationFactor.Result result = ValuationFactor.compute(8.0, 1.0, List.of(), List.of());

        assertThat(result.basis()).isEqualTo("PE");
        assertThat(result.score()).isCloseTo(100.0, within(1e-9));
    }
}
