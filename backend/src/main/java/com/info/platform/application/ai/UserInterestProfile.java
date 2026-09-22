package com.info.platform.application.ai;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户兴趣画像（值对象，T29 推荐相关性优化）：订阅 + 近期阅读行为的个性化输入。
 *
 * <p>由 {@link RecommendationPersonalizer} 装配（订阅取 {@code subscription_config} 活跃订阅、阅读取 {@code
 * reading_event} 近 30 天留痕），消费方两处：
 *
 * <ul>
 *   <li>{@code DailyRecommendationContextBuilder}：投影为 LLM prompt 上下文占位符
 *       （subscribedThemes/subscribedSubjects/readingProfile）；
 *   <li>{@code RecommendationRelevanceScorer}：规则兜底路径的相关性评分（已读热度/订阅命中加分）。
 * </ul>
 *
 * <p>空画像（无订阅且无阅读，如新用户）是合法态——评分退化为既有活跃度排序（边界回退，不异常）。
 *
 * @param themeKeywords 订阅主题词（TOPIC + POLICY_THEME 活跃订阅 subKey 去重保序）
 * @param subscribedSubjects 标的订阅（SUBJECT 活跃订阅解析出的标的引用）
 * @param readStats 近 30 天已读标的统计（按热度降序；已读热度 = Σ 0.5^(距今天数/7)，半衰期 7 天）
 */
public record UserInterestProfile(
        List<String> themeKeywords,
        List<SubscribedSubject> subscribedSubjects,
        List<SubjectReadStat> readStats) {

    /** 空画像（新用户/装配降级）：无订阅、无阅读。 */
    public static final UserInterestProfile EMPTY =
            new UserInterestProfile(List.of(), List.of(), List.of());

    public UserInterestProfile {
        themeKeywords = themeKeywords == null ? List.of() : List.copyOf(themeKeywords);
        subscribedSubjects =
                subscribedSubjects == null ? List.of() : List.copyOf(subscribedSubjects);
        readStats = readStats == null ? List.of() : List.copyOf(readStats);
    }

    /** 是否含任何个性化信号（false = 空画像，评分退化为活跃度排序）。 */
    public boolean isPersonalized() {
        return !themeKeywords.isEmpty() || !subscribedSubjects.isEmpty() || !readStats.isEmpty();
    }

    /** 标的订阅 id 集合（评分侧 O(1) 命中判断）。 */
    public Set<Long> subscribedSubjectIds() {
        return subscribedSubjects.stream()
                .map(SubscribedSubject::subjectId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 已读统计按 subjectId 索引（评分侧 O(1) 取热度）。 */
    public Map<Long, SubjectReadStat> readStatsBySubjectId() {
        return readStats.stream()
                .collect(
                        Collectors.toUnmodifiableMap(
                                SubjectReadStat::subjectId, Function.identity()));
    }

    /** 标的订阅引用（subjectId + 展示名；上下文投影与评分命中共用）。 */
    public record SubscribedSubject(Long subjectId, String code, String name) {}

    /**
     * 单标的已读统计（近 30 天窗口）。
     *
     * @param subjectId 标的 id
     * @param code 标的代码（画像装配时解析，已删标的为 null）
     * @param name 标的名称
     * @param count 阅读次数
     * @param lastReadDate 最近阅读日期
     * @param heat 已读热度 = Σ 0.5^(距今天数/7)（时间衰减：一周前阅读权重减半）
     */
    public record SubjectReadStat(
            Long subjectId,
            String code,
            String name,
            int count,
            LocalDate lastReadDate,
            double heat) {}
}
