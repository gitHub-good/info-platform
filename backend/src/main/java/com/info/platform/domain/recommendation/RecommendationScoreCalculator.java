package com.info.platform.domain.recommendation;

import com.info.platform.domain.analysis.Importance;

/**
 * recscore-v1 综合分计算器（领域纯函数，M16 方案 §4.4 / REQ 拍板一冻结系数；ADR-0045 basis 版本串先例）。
 *
 * <p>公式：{@code recscore = levelCoef × impCoef × profileCoef}
 *
 * <ul>
 *   <li>levelCoef：P1=3.0 / P2=2.0 / P3=1.0（{@link RecLevel#coefficient()}，REQ 拍板一冻结值）；
 *   <li>impCoef：HIGH=2.0 / MEDIUM=1.0（LOW 永不触发——触发门槛在关联引擎前置过滤，本函数对 LOW 按 0 计防御）；
 *   <li>profileCoef = 1 + α × max(normSubjectHeat, themeHitScore) ∈ [1.0, 1.25]： α=0.25（画像上限加成
 *       25%——只调序不翻天）；normSubjectHeat = min(maxHeat/heatCap, 1) （热度源 =
 *       UserInterestProfile.readStats，heat=Σ0.5^(距今天数/7)）；themeHitScore=0.6（主题词命中）。 下界 1
 *       保证无画像用户排序不降级；上界 1.25 保证画像只加权不越级——层级×重要性主序（P1 6.0/3.0 &gt; P2 4.0/2.0 &gt; P3
 *       2.0/1.0）不被画像翻转（方案 §4.4）。
 * </ul>
 *
 * <p>参数全部 {@code recommendation.score} 可配（页面热改），basis 版本化留档卡片行。
 */
public final class RecommendationScoreCalculator {

    private RecommendationScoreCalculator() {}

    /** recscore-v1 参数（recommendation.score 文档的消费形态；缺省值与 Seeder 种子同源）。 */
    public record ScoreParams(
            double levelP1,
            double levelP2,
            double levelP3,
            double impHigh,
            double impMedium,
            double profileAlpha,
            double profileThemeHit,
            double profileHeatCap,
            String basis) {

        /** 缺省参数（方案 §4.4/§4.9 冻结值；与 RecommendationRuntimeConfigSeeder 种子同源维护）。 */
        public static ScoreParams defaults() {
            return new ScoreParams(
                    3.0,
                    2.0,
                    1.0,
                    2.0,
                    1.0,
                    0.25,
                    0.6,
                    5.0,
                    "recscore-v1:lvl=3|2|1;imp=2|1;pf=1+0.25*max(heat/5,theme=0.6)");
        }
    }

    /**
     * 计算综合分（纯函数）。
     *
     * @param level 关联层级
     * @param importance 事件重要度（LOW = 触发门槛外，按 0 计防御——正常链路不会进入）
     * @param maxSubjectHeat 标的区标的的最大已读热度（heat=Σ0.5^(距今天数/7)）
     * @param themeHit 主题词是否命中事件（标题/摘要 contains 或行业别名反查）
     * @param params 参数
     * @return recscore-v1 综合分（≥0）
     */
    public static double calculate(
            RecLevel level,
            Importance importance,
            double maxSubjectHeat,
            boolean themeHit,
            ScoreParams params) {
        double levelCoef =
                switch (level) {
                    case P1 -> params.levelP1();
                    case P2 -> params.levelP2();
                    case P3 -> params.levelP3();
                };
        double impCoef =
                switch (importance) {
                    case HIGH -> params.impHigh();
                    case MEDIUM -> params.impMedium();
                    case LOW -> 0.0; // LOW 永不触发（红线）——防御性归零
                };
        return levelCoef * impCoef * profileCoef(maxSubjectHeat, themeHit, params);
    }

    /** 画像系数 ∈ [1.0, 1.25]（缺省参数下；参数热改越界时仍夹在 [1, 1+α]——只加权不越级的机制护栏）。 */
    public static double profileCoef(double maxSubjectHeat, boolean themeHit, ScoreParams params) {
        double normHeat =
                params.profileHeatCap() <= 0
                        ? 0.0
                        : Math.min(Math.max(maxSubjectHeat, 0) / params.profileHeatCap(), 1.0);
        double signal = Math.max(normHeat, themeHit ? params.profileThemeHit() : 0.0);
        double alpha = Math.max(0, params.profileAlpha());
        double coef = 1.0 + alpha * Math.min(Math.max(signal, 0.0), 1.0);
        return Math.max(1.0, Math.min(coef, 1.0 + alpha)); // 上下界夹取（空画像=1.0）
    }
}
