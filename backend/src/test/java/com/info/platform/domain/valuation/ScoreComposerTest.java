package com.info.platform.domain.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * ScoreComposer 单测（T171，方案 §4.2 总分与标签）：归一合成（÷权和——配置未归一自动归一）、F5 权重 0 不进基线、
 * 「有突破」三阈值判定（可配、含边界）、同输入同权重同分（幂等红线）。纯函数——重复调用零漂移。
 */
class ScoreComposerTest {

    private static final ValuationParams DEFAULTS = ValuationParams.defaults();

    @Test
    void weightedMean_defaultWeights() {
        // (0.4×80 + 0.2×60 + 0.2×70 + 0.2×90 + 0.0×100) / 1.0 = 76.0
        ScoreComposer.Composed composed = ScoreComposer.compose(80, 60, 70, 90, 100, DEFAULTS);

        assertThat(composed.totalScore()).isEqualTo(76.0);
    }

    @Test
    void unnormalizedWeights_autoNormalized_sameTotal() {
        // 权和 2.0（未归一）→ 除以权和后同分（配置无需强制归一，§4.2）
        ValuationParams unnormalized =
                DEFAULTS.withWCatalyst(0.8)
                        .withWConduction(0.4)
                        .withWFundamental(0.4)
                        .withWRisk(0.4)
                        .withWValuation(0.0);

        assertThat(ScoreComposer.compose(80, 60, 70, 90, 100, unnormalized).totalScore())
                .isEqualTo(76.0);
    }

    @Test
    void zeroWeightValuation_excludedFromBaseline() {
        // F5 权重 0：估值维无论 0/50/100 总分不变（ADR-0058 裁决 5：默认不污染四维 Must 链）
        double withHigh = ScoreComposer.compose(80, 60, 70, 90, 100, DEFAULTS).totalScore();
        double withLow = ScoreComposer.compose(80, 60, 70, 90, 0, DEFAULTS).totalScore();

        assertThat(withHigh).isEqualTo(withLow);
        // 启用后即生效（权重页面化可一键启用语义）
        ValuationParams enabled = DEFAULTS.withWCatalyst(0.3).withWValuation(0.1);
        assertThat(ScoreComposer.compose(80, 60, 70, 90, 100, enabled).totalScore())
                .isCloseTo(0.3 * 80 + 0.2 * 60 + 0.2 * 70 + 0.2 * 90 + 0.1 * 100, within(1e-9));
    }

    @Test
    void breakthrough_threeThresholds_allRequired() {
        // 恰在阈值（60/50/80）→ 真；任一维掉到阈值下 → 假
        assertThat(ScoreComposer.compose(60, 50, 0, 80, 50, DEFAULTS).breakthrough()).isTrue();
        assertThat(ScoreComposer.compose(59.9, 50, 0, 80, 50, DEFAULTS).breakthrough()).isFalse();
        assertThat(ScoreComposer.compose(60, 49.9, 0, 80, 50, DEFAULTS).breakthrough()).isFalse();
        assertThat(ScoreComposer.compose(60, 50, 0, 79.9, 50, DEFAULTS).breakthrough()).isFalse();
        // F3/F5 不参与标签判定（三阈值语义）
        assertThat(ScoreComposer.compose(60, 50, 100, 80, 100, DEFAULTS).breakthrough()).isTrue();
    }

    @Test
    void breakthrough_thresholdsConfigurable() {
        ValuationParams raised =
                DEFAULTS.withBtCatalystMin(70).withBtConductionMin(60).withBtRiskMin(90);

        assertThat(ScoreComposer.compose(65, 55, 0, 85, 50, DEFAULTS).breakthrough()).isTrue();
        assertThat(ScoreComposer.compose(65, 55, 0, 85, 50, raised).breakthrough()).isFalse();
    }

    @Test
    void sameInputsAndWeights_sameScore_idempotent() {
        // 幂等红线（§6）：同输入 + 同权重 → 同分同标签（重复调用逐位相等）
        ScoreComposer.Composed first = ScoreComposer.compose(76.92, 100, 100, 100, 50, DEFAULTS);
        ScoreComposer.Composed second = ScoreComposer.compose(76.92, 100, 100, 100, 50, DEFAULTS);

        assertThat(first).isEqualTo(second);
        // 原式 90.768 → 落库/展示粒度 round1 = 90.8（两轮逐位相等即零漂移）
        assertThat(first.totalScore())
                .isCloseTo(0.4 * 76.92 + 0.2 * 100 + 0.2 * 100 + 0.2 * 100, within(0.05));
    }

    @Test
    void totalScore_roundedToOneDecimal() {
        // 0.4×76.92+20+20+20 = 90.768 → 90.8（展示与落库同粒度）
        assertThat(ScoreComposer.compose(76.92, 100, 100, 100, 50, DEFAULTS).totalScore())
                .isEqualTo(90.8);
    }

    @Test
    void zeroWeightSum_defensiveZero() {
        ValuationParams allZero =
                new ValuationParams(0, 0, 0, 0, 0, 10, 30, 5.0, 3.0, 1.5, 60, 50, 80);

        assertThat(ScoreComposer.compose(80, 60, 70, 90, 100, allZero).totalScore()).isZero();
    }

    @Test
    void fromBasis_parsesWeightsBack() {
        // weight_basis 指纹串 → 权重复现（详情端点按当时权重展示的解析锚，ADR-0058 裁决 4）
        ValuationParams parsed =
                ValuationParams.fromBasis(
                        "vs-v1:w=0.50|0.10|0.20|0.20|0.00;win=15|45;hl=7.0;k=4.0|2.0;bt=65|40|75");

        assertThat(parsed.wCatalyst()).isEqualTo(0.50);
        assertThat(parsed.wConduction()).isEqualTo(0.10);
        assertThat(parsed.wFundamental()).isEqualTo(0.20);
        assertThat(parsed.wRisk()).isEqualTo(0.20);
        assertThat(parsed.wValuation()).isEqualTo(0.00);
    }

    @Test
    void fromBasis_malformed_fallsBackToDefaults() {
        assertThat(ValuationParams.fromBasis(null)).isEqualTo(ValuationParams.defaults());
        assertThat(ValuationParams.fromBasis("garbage")).isEqualTo(ValuationParams.defaults());
    }
}
