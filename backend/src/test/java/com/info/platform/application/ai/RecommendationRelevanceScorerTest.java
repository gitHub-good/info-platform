package com.info.platform.application.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RecommendationRelevanceScorer 单测（T29）：评分构成（订阅标的/主题命中/已读热度/活跃度）/ 理由可解释性 / 新用户空画像回退 / 热度饱和上限。AAA
 * 结构。
 *
 * <p>纯函数组件，直调无 mock。
 */
class RecommendationRelevanceScorerTest {

    private final RecommendationRelevanceScorer scorer = new RecommendationRelevanceScorer();

    @Test
    void score_personalized_accumulatesAllFactorsAndExplainableReason() {
        // Arrange：标的已订阅 + 行业命中主题「半导体」 + 已读热度 3.0（饱和）+ 活跃度 3.0
        PoolMetric metric = new PoolMetric("SH600519", "北方华创", 1.0, 1, 0, 100L, "半导体设备");
        UserInterestProfile profile =
                new UserInterestProfile(
                        List.of("半导体"),
                        List.of(
                                new UserInterestProfile.SubscribedSubject(
                                        100L, "SH600519", "北方华创")),
                        List.of(
                                new UserInterestProfile.SubjectReadStat(
                                        100L,
                                        "SH600519",
                                        "北方华创",
                                        3,
                                        LocalDate.of(2026, 9, 22),
                                        3.0)));

        // Act
        List<RecommendationRelevanceScorer.ScoredSubject> scored =
                scorer.score(List.of(metric), profile);

        // Assert：总分 = 3.0 活跃 + 6.0 标的订阅 + 4.0 主题 + 10.0 已读（饱和）；理由逐因子列明
        RecommendationRelevanceScorer.ScoredSubject s = scored.get(0);
        assertThat(s.relevanceScore()).isEqualTo(23.0);
        assertThat(s.readBonus()).isEqualTo(10.0);
        assertThat(s.subjectSubscribed()).isTrue();
        assertThat(s.themeKeyword()).isEqualTo("半导体");
        assertThat(s.reason())
                .contains("已读热度+10.0")
                .contains("标的订阅+6.0")
                .contains("命中主题「半导体」+4.0")
                .contains("综合分23.0");
    }

    @Test
    void score_readHeatBelowSaturation_linearlyScaled() {
        // Arrange：热度 1.5（饱和点 3.0 的一半）→ 已读加分 5.0
        PoolMetric metric = new PoolMetric("SH600519", "贵州茅台", 0.0, 0, 0, 100L, "白酒");
        UserInterestProfile profile =
                new UserInterestProfile(
                        List.of(),
                        List.of(),
                        List.of(
                                new UserInterestProfile.SubjectReadStat(
                                        100L,
                                        "SH600519",
                                        "贵州茅台",
                                        2,
                                        LocalDate.of(2026, 9, 20),
                                        1.5)));

        // Act
        List<RecommendationRelevanceScorer.ScoredSubject> scored =
                scorer.score(List.of(metric), profile);

        // Assert：readBonus = 10.0 × (1.5/3.0) = 5.0
        assertThat(scored.get(0).readBonus()).isEqualTo(5.0);
        assertThat(scored.get(0).reason()).contains("已读热度+5.0");
    }

    @Test
    void score_emptyProfile_fallsBackToActivityOnly() {
        // Arrange：边界——新用户空画像（无订阅无阅读），兼容五参构造（subjectId/industry null）
        PoolMetric metric = new PoolMetric("SH600519", "贵州茅台", 3.0, 1, 2);

        // Act
        List<RecommendationRelevanceScorer.ScoredSubject> scored =
                scorer.score(List.of(metric), UserInterestProfile.EMPTY);

        // Assert：退化为既有活跃度口径（|3.0|+1×2+2=7.0），理由含活跃度与综合分、无个性化因子
        assertThat(scored.get(0).relevanceScore()).isEqualTo(7.0);
        assertThat(scored.get(0).readBonus()).isZero();
        assertThat(scored.get(0).reason())
                .startsWith("信息面活跃：涨跌幅3.0%，公告1条，新闻2条")
                .contains("综合分7.0")
                .doesNotContain("个性化相关");
    }

    @Test
    void score_themeMatchesNameOrCode_notOnlyIndustry() {
        // Arrange：主题词命中标的名称而非行业
        PoolMetric metric = new PoolMetric("SZ300750", "宁德时代", 0.0, 0, 0, 200L, "电池");
        UserInterestProfile profile =
                new UserInterestProfile(List.of("宁德时代"), List.of(), List.of());

        // Act
        List<RecommendationRelevanceScorer.ScoredSubject> scored =
                scorer.score(List.of(metric), profile);

        // Assert：名称命中主题同样加分
        assertThat(scored.get(0).themeKeyword()).isEqualTo("宁德时代");
        assertThat(scored.get(0).relevanceScore()).isEqualTo(4.0);
    }

    @Test
    void score_noSubjectId_personalizationSignalsIgnoredSafely() {
        // Arrange：兼容五参构造的指标（subjectId null）+ 有阅读/订阅的画像——不能 NPE、不能误命中
        PoolMetric metric = new PoolMetric("SH600519", "贵州茅台", 2.0, 0, 0);
        UserInterestProfile profile =
                new UserInterestProfile(
                        List.of("白酒"),
                        List.of(
                                new UserInterestProfile.SubscribedSubject(
                                        100L, "SH600519", "贵州茅台")),
                        List.of(
                                new UserInterestProfile.SubjectReadStat(
                                        100L,
                                        "SH600519",
                                        "贵州茅台",
                                        5,
                                        LocalDate.of(2026, 9, 22),
                                        5.0)));

        // Act
        List<RecommendationRelevanceScorer.ScoredSubject> scored =
                scorer.score(List.of(metric), profile);

        // Assert：subjectId null → 订阅/已读不命中；行业 null 但名称「贵州茅台」不含「白酒」→ 主题不命中
        assertThat(scored.get(0).relevanceScore()).isEqualTo(2.0);
        assertThat(scored.get(0).reason()).doesNotContain("个性化相关");
    }

    @Test
    void score_nullProfile_treatedAsEmpty() {
        // Arrange：异常防御——画像 null（装配降级路径传 null 而非 EMPTY）
        PoolMetric metric = new PoolMetric("SH600519", "贵州茅台", 1.0, 0, 0, 100L, "白酒");

        // Act
        List<RecommendationRelevanceScorer.ScoredSubject> scored =
                scorer.score(List.of(metric), null);

        // Assert：按空画像处理不抛
        assertThat(scored.get(0).relevanceScore()).isEqualTo(1.0);
    }
}
