package com.info.platform.application.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 推荐相关性评分器（应用层，T29）：自选池指标 + 用户画像 → 相关性综合分与可解释推荐理由。
 *
 * <p>纯函数组件（无仓储/时钟依赖，画像热度已由 {@link RecommendationPersonalizer} 按时间衰减算好）， 供 {@link
 * DailyRecommendationService} 规则兜底路径排序；AI 路径经 prompt 上下文（v1.1 模板）获得同源画像。
 *
 * <h2>评分构成（对齐 PRD 场景 2「推荐内容与用户订阅主题/已读标的相关性」）</h2>
 *
 * <pre>relevanceScore = activityScore           // 既有信息面活跃度：|涨跌幅| + 公告×2 + 新闻
 *                 + subjectSubBonus          // 标的订阅命中 +6.0（用户显式订阅的标的）
 *                 + themeBonus               // 订阅主题命中 +4.0（行业/名称/代码含任一主题词）
 *                 + readBonus                // 已读热度加分 ≤10.0（热度 3.0 饱和，线性折算）</pre>
 *
 * <h2>推荐理由（人工标注口径的可验证载体）</h2>
 *
 * 个性化命中时逐因子列明（如「已读热度+6.7；标的订阅+6.0；命中主题「半导体」+4.0；信息面活跃…；综合分20.9」），
 * 标注员可据「是否命中订阅主题/订阅标的/已读标的」逐条判定；空画像退化为既有活跃度理由 + 综合分。
 */
@Component
public class RecommendationRelevanceScorer {

    /** 标的订阅命中加分（用户显式订阅 = 最强相关信号）。 */
    static final double SUBJECT_SUB_BONUS = 6.0;

    /** 订阅主题命中加分（行业/名称/代码含任一主题词）。 */
    static final double THEME_BONUS = 4.0;

    /** 已读热度加分上限（防止高频阅读单一标的垄断排序）。 */
    static final double READ_BONUS_MAX = 10.0;

    /** 已读热度饱和点：热度达 3.0（约 3 次近期阅读）即拿满加分。 */
    static final double READ_HEAT_FULL = 3.0;

    /**
     * 逐标的评分（保持入参顺序）。
     *
     * @param metrics 自选池指标
     * @param profile 用户画像（空画像 → 纯活跃度分，新用户回退）
     */
    public List<ScoredSubject> score(List<PoolMetric> metrics, UserInterestProfile profile) {
        UserInterestProfile safe = profile == null ? UserInterestProfile.EMPTY : profile;
        Set<Long> subscribedIds = safe.subscribedSubjectIds();
        Map<Long, UserInterestProfile.SubjectReadStat> readStats = safe.readStatsBySubjectId();
        List<ScoredSubject> scored = new ArrayList<>(metrics.size());
        for (PoolMetric metric : metrics) {
            scored.add(scoreOne(metric, safe, subscribedIds, readStats));
        }
        return scored;
    }

    /** 单标的评分：个性化加分逐项计算并落入理由（可解释、可标注）。 */
    private ScoredSubject scoreOne(
            PoolMetric metric,
            UserInterestProfile profile,
            Set<Long> subscribedIds,
            Map<Long, UserInterestProfile.SubjectReadStat> readStats) {
        boolean subjectSubscribed =
                metric.subjectId() != null && subscribedIds.contains(metric.subjectId());
        String themeKeyword = firstThemeHit(metric, profile.themeKeywords());
        double readBonus = readBonusOf(metric, readStats);
        double total =
                metric.activityScore()
                        + (subjectSubscribed ? SUBJECT_SUB_BONUS : 0.0)
                        + (themeKeyword != null ? THEME_BONUS : 0.0)
                        + readBonus;
        return new ScoredSubject(
                metric,
                readBonus,
                subjectSubscribed,
                themeKeyword,
                total,
                reasonOf(metric, readBonus, subjectSubscribed, themeKeyword, total));
    }

    /** 已读热度加分：heat 3.0 饱和线性折算（≤10.0）；无已读记录为 0。 */
    private static double readBonusOf(
            PoolMetric metric, Map<Long, UserInterestProfile.SubjectReadStat> readStats) {
        if (metric.subjectId() == null) {
            return 0.0;
        }
        UserInterestProfile.SubjectReadStat stat = readStats.get(metric.subjectId());
        if (stat == null || stat.heat() <= 0) {
            return 0.0;
        }
        double ratio = Math.min(1.0, stat.heat() / READ_HEAT_FULL);
        return round1(READ_BONUS_MAX * ratio);
    }

    /** 首个命中的订阅主题词（行业/名称/代码 contains，大小写不敏感）；无命中 null。 */
    private static String firstThemeHit(PoolMetric metric, List<String> themeKeywords) {
        for (String keyword : themeKeywords) {
            if (containsIgnoreCase(metric.industry(), keyword)
                    || containsIgnoreCase(metric.subjectName(), keyword)
                    || containsIgnoreCase(metric.subjectCode(), keyword)) {
                return keyword;
            }
        }
        return null;
    }

    /** 推荐理由：个性化命中逐因子列明（标注可判定）；空画像退化为活跃度理由 + 综合分。 */
    private static String reasonOf(
            PoolMetric metric,
            double readBonus,
            boolean subjectSubscribed,
            String themeKeyword,
            double total) {
        StringBuilder factors = new StringBuilder();
        if (readBonus > 0) {
            factors.append("已读热度+").append(readBonus).append("；");
        }
        if (subjectSubscribed) {
            factors.append("标的订阅+").append(SUBJECT_SUB_BONUS).append("；");
        }
        if (themeKeyword != null) {
            factors.append("命中主题「")
                    .append(themeKeyword)
                    .append("」+")
                    .append(THEME_BONUS)
                    .append("；");
        }
        String activity =
                "信息面活跃：涨跌幅"
                        + metric.changePct()
                        + "%，公告"
                        + metric.announceCount()
                        + "条，新闻"
                        + metric.newsCount()
                        + "条";
        if (factors.isEmpty()) {
            return activity + "（综合分" + round1(total) + "）";
        }
        return "个性化相关：" + factors + activity + "；综合分" + round1(total);
    }

    /** 大小写不敏感 contains（null 安全）。 */
    private static boolean containsIgnoreCase(String haystack, String needle) {
        if (haystack == null || haystack.isBlank() || needle == null || needle.isBlank()) {
            return false;
        }
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    /** 保留 1 位小数（理由展示稳定，防浮点尾巴干扰标注）。 */
    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /**
     * 单标的评分结果（值对象）。
     *
     * @param metric 原始指标
     * @param readBonus 已读热度加分（0~10.0）
     * @param subjectSubscribed 是否命中标的订阅
     * @param themeKeyword 命中的订阅主题词（无命中 null）
     * @param relevanceScore 相关性综合分（活跃度 + 个性化加分）
     * @param reason 推荐理由（逐因子可解释，人工标注载体）
     */
    public record ScoredSubject(
            PoolMetric metric,
            double readBonus,
            boolean subjectSubscribed,
            String themeKeyword,
            double relevanceScore,
            String reason) {}
}
