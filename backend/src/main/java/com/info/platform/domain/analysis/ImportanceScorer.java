package com.info.platform.domain.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L2 重要性预筛打分器（领域纯函数，M15 T122，方案 §4.4 公式）。
 *
 * <p>公式：{@code score = sourceWeight(category) + Σ strongTrigger 命中 × 2.0 + Σ mediumTrigger 命中 × 1.0
 * + subjectBonus（命中标的池公司计一次）}；{@code score >= threshold}（缺省 2.5）命中预筛 → 进 L2 候选。参数全可配 （{@code
 * pipeline.l2} 键，页面热改），缺省值与种子同源（方案 §4.8 键表）。
 *
 * <p>命中判定基于标题+摘要拼接文本的包含匹配；同一触发词多处出现只计一次（规则信号去重——「降准」标题摘要双现不双计）。
 */
public final class ImportanceScorer {

    private ImportanceScorer() {}

    /** 打分参数（pipeline.l2 文档的消费形态；缺省值见 {@link #defaults()}）。 */
    public record ScorerParams(
            Map<String, Double> sourceWeights,
            List<String> strongTriggers,
            List<String> mediumTriggers,
            double subjectBonus,
            double threshold) {

        /** 强触发词权重（方案 §4.4：×2.0）。 */
        public static final double STRONG_WEIGHT = 2.0;

        /** 中触发词权重（方案 §4.4：×1.0）。 */
        public static final double MEDIUM_WEIGHT = 1.0;
    }

    /**
     * 打分（纯函数，无 IO）。
     *
     * @param title 标题（null 视作空）
     * @param summary 摘要（null 视作空）
     * @param sourceCategory 源类别（快讯/媒体/政策/宏观/国际/自建；未知类别回落权重 1.0）
     * @param subjectMatched 标的池回联命中（≥1 命中即计一次 subjectBonus）
     * @param params 打分参数
     * @return 重要性分（≥0）
     */
    public static double score(
            String title,
            String summary,
            String sourceCategory,
            boolean subjectMatched,
            ScorerParams params) {
        String text = (title == null ? "" : title) + "\n" + (summary == null ? "" : summary);
        double score = sourceWeightOf(sourceCategory, params);
        score += ScorerParams.STRONG_WEIGHT * countHits(text, params.strongTriggers());
        score += ScorerParams.MEDIUM_WEIGHT * countHits(text, params.mediumTriggers());
        if (subjectMatched) {
            score += params.subjectBonus();
        }
        return score;
    }

    /** 是否命中预筛阈值（score ≥ threshold）。 */
    public static boolean hitsThreshold(double score, ScorerParams params) {
        return score >= params.threshold();
    }

    /** 缺省参数（与 PipelineRuntimeConfigSeeder 种子同源维护）。 */
    public static ScorerParams defaults() {
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("政策", 2.0);
        weights.put("宏观", 2.0);
        weights.put("快讯", 1.5);
        weights.put("媒体", 1.0);
        weights.put("国际", 1.0);
        weights.put("自建", 1.0);
        return new ScorerParams(
                Map.copyOf(weights),
                List.of(
                        "业绩预告", "预增", "预亏", "并购", "重组", "收购", "回购", "增持", "减持", "重大合同", "中标", "处罚",
                        "立案", "降准", "降息", "关税", "管制", "突破", "获批"),
                List.of(
                        "签约", "合作", "投产", "上调", "下调", "涨价", "降价", "新高", "新低", "停牌", "复牌", "辞职",
                        "聘任", "上线", "发布"),
                1.5,
                2.5);
    }

    private static double sourceWeightOf(String sourceCategory, ScorerParams params) {
        return params.sourceWeights().getOrDefault(sourceCategory, 1.0);
    }

    /** 命中触发词数（词级去重：同一词多处出现计一次）。 */
    private static int countHits(String text, List<String> triggers) {
        int hits = 0;
        for (String trigger : triggers) {
            if (trigger != null && !trigger.isBlank() && text.contains(trigger)) {
                hits++;
            }
        }
        return hits;
    }
}
