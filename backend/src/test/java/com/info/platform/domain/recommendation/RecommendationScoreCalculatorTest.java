package com.info.platform.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.recommendation.RecommendationScoreCalculator.ScoreParams;
import org.junit.jupiter.api.Test;

/**
 * recscore-v1 算术单测（T131，方案 §4.4 / REQ 拍板一冻结系数）：level×imp 基础分全组合、画像系数上下界 [1.0, 1.25]、热度归一与封顶、 主题命中
 * 0.6、空画像 = 1.0、画像不越级（P3 满画像 &lt; P2 基础分）、LOW 防御归零。 AAA 结构，纯函数零依赖。
 */
class RecommendationScoreCalculatorTest {

    private final ScoreParams params = ScoreParams.defaults();

    // ---- 基础分（levelCoef × impCoef） ----

    @Test
    void calculate_baseScores_levelTimesImportance() {
        assertThat(calculate(RecLevel.P1, Importance.HIGH)).isEqualTo(6.0);
        assertThat(calculate(RecLevel.P1, Importance.MEDIUM)).isEqualTo(6.0 / 2.0);
        assertThat(calculate(RecLevel.P2, Importance.HIGH)).isEqualTo(4.0);
        assertThat(calculate(RecLevel.P2, Importance.MEDIUM)).isEqualTo(2.0);
        assertThat(calculate(RecLevel.P3, Importance.HIGH)).isEqualTo(2.0);
        assertThat(calculate(RecLevel.P3, Importance.MEDIUM)).isEqualTo(1.0);
    }

    @Test
    void calculate_lowImportance_defensiveZero() {
        // LOW 永不触发（红线在触发门槛前置过滤）——本函数防御性归零
        assertThat(calculate(RecLevel.P1, Importance.LOW)).isZero();
    }

    // ---- 画像系数 ----

    @Test
    void profileCoef_emptyProfile_exactlyOne() {
        // 空画像合法态：无热度无主题命中 → 1.0（排序不降级）
        assertThat(RecommendationScoreCalculator.profileCoef(0.0, false, params)).isEqualTo(1.0);
        assertThat(RecommendationScoreCalculator.profileCoef(0.0, false, params))
                .isCloseTo(1.0, within(1e-9));
    }

    @Test
    void profileCoef_themeHit_fixedPointSixBoost() {
        // 主题词命中 = 0.6 信号 → 1 + 0.25×0.6 = 1.15
        assertThat(RecommendationScoreCalculator.profileCoef(0.0, true, params))
                .isCloseTo(1.15, within(1e-9));
    }

    @Test
    void profileCoef_heatNormalizedByCapAndSaturates() {
        // heat=2.5 → norm 0.5 → 1.125；heat≥5 → 封顶 norm 1 → 1.25（5 次今日阅读封顶语义）
        assertThat(RecommendationScoreCalculator.profileCoef(2.5, false, params))
                .isCloseTo(1.125, within(1e-9));
        assertThat(RecommendationScoreCalculator.profileCoef(5.0, false, params))
                .isCloseTo(1.25, within(1e-9));
        assertThat(RecommendationScoreCalculator.profileCoef(50.0, false, params))
                .isCloseTo(1.25, within(1e-9));
    }

    @Test
    void profileCoef_heatTakesMaxOverThemeHit() {
        // max(heat_norm, theme)：热度更高取热度 → 1 + 0.25×0.8 = 1.2
        assertThat(RecommendationScoreCalculator.profileCoef(4.0, true, params))
                .isCloseTo(1.2, within(1e-9));
    }

    @Test
    void profileCoef_boundsClamped() {
        // 上下界夹取：负热度 → 1.0；越界配置不越 1+α 上限
        assertThat(RecommendationScoreCalculator.profileCoef(-3.0, false, params)).isEqualTo(1.0);
        ScoreParams aggressive = new ScoreParams(3, 2, 1, 2, 1, 0.9, 5.0, 5.0, "test");
        assertThat(RecommendationScoreCalculator.profileCoef(9.9, true, aggressive))
                .isEqualTo(1.9); // 1 + 0.9×min(max(...),1) = 1.9 = 1+α
    }

    // ---- 画像只加权不越级 ----

    @Test
    void calculate_profileNeverCrossesLevelOrdering() {
        // 方案 §4.4「画像加成不改变层级×重要性的主序」：同重要度下，低层级满画像不越高层级基础分——
        // P3 HIGH 满画像 2.5 < P2 HIGH 基础 4.0；P2 HIGH 满画像 5.0 < P1 HIGH 基础 6.0；
        // 跨重要度不翻转：P1 MEDIUM 满画像 3.75 < P2 HIGH 基础 4.0
        double p3Full =
                RecommendationScoreCalculator.calculate(
                        RecLevel.P3, Importance.HIGH, 5.0, true, params);
        double p2Base =
                RecommendationScoreCalculator.calculate(
                        RecLevel.P2, Importance.HIGH, 0.0, false, params);
        double p2Full =
                RecommendationScoreCalculator.calculate(
                        RecLevel.P2, Importance.HIGH, 5.0, true, params);
        double p1HighBase =
                RecommendationScoreCalculator.calculate(
                        RecLevel.P1, Importance.HIGH, 0.0, false, params);
        double p1MediumFull =
                RecommendationScoreCalculator.calculate(
                        RecLevel.P1, Importance.MEDIUM, 5.0, true, params);
        assertThat(p3Full).isLessThan(p2Base);
        assertThat(p2Full).isLessThan(p1HighBase);
        assertThat(p1MediumFull).isLessThan(p2Base);
    }

    // ---- basis 版本串 ----

    @Test
    void defaults_basisCarriesRecscoreV1VersionString() {
        assertThat(params.basis())
                .isEqualTo("recscore-v1:lvl=3|2|1;imp=2|1;pf=1+0.25*max(heat/5,theme=0.6)");
    }

    private double calculate(RecLevel level, Importance importance) {
        return RecommendationScoreCalculator.calculate(level, importance, 0.0, false, params);
    }
}
