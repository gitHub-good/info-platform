package com.info.platform.application.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.info.platform.domain.subscription.Subscription;
import com.info.platform.domain.subscription.SubscriptionChannel;
import com.info.platform.domain.subscription.SubscriptionStatus;
import com.info.platform.domain.subscription.SubscriptionType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * FeedMatcher 单元测试（T27）：4 类 sub_type 命中逻辑（主题/标的/事件类型/政策主题）+ 退订过滤。
 *
 * <p>纯函数匹配器，无 Spring/仓储依赖，直 {@code new FeedMatcher()} 复用。AAA 结构，覆盖主路径/边界/退订降噪 （PRD 故事 5 场景
 * 3）。事件类型「异动走推送不入拉流」为已知限制（不构造异动内容断言）。
 */
class FeedMatcherTest {

    private static final Instant TS = Instant.parse("2026-09-20T00:00:00Z");
    private final FeedMatcher matcher = new FeedMatcher();

    private static Subscription sub(SubscriptionType type, String key, SubscriptionStatus status) {
        return Subscription.reconstruct(
                1L, 1L, type, key, SubscriptionChannel.IN_APP, status, 0L, TS, TS);
    }

    private static FeedContent announce(String id, String title, Long subjectId, String category) {
        return new FeedContent(
                FeedItemType.ANNOUNCE,
                id,
                title,
                "",
                TS,
                "公告",
                null,
                subjectId,
                "SH600519",
                "贵州茅台",
                List.of(),
                category);
    }

    private static FeedContent news(String id, String title, String summary, Long subjectId) {
        return new FeedContent(
                FeedItemType.NEWS,
                id,
                title,
                summary,
                TS,
                "新浪财经",
                null,
                subjectId,
                "SH600519",
                "贵州茅台",
                List.of(),
                null);
    }

    private static FeedContent policy(long id, String title, List<String> industries) {
        return new FeedContent(
                FeedItemType.POLICY,
                String.valueOf(id),
                title,
                "",
                TS,
                "国务院",
                null,
                null,
                null,
                null,
                industries,
                null);
    }

    private static Map<Long, SubjectRef> index(SubjectRef... refs) {
        Map<Long, SubjectRef> map = new java.util.HashMap<>();
        for (SubjectRef r : refs) {
            map.put(r.id(), r);
        }
        return map;
    }

    // ---- TOPIC 主题 ----

    @Test
    void topic_titleContainsKey_hits() {
        // Arrange：标题含主题词
        Subscription topic = sub(SubscriptionType.TOPIC, "半导体", SubscriptionStatus.SUBSCRIBED);
        FeedContent content = news("n1", "半导体国产替代加速", "", 600519L);

        // Act
        List<MatchedFeedContent> matched =
                matcher.match(List.of(topic), List.of(content), Map.of());

        // Assert
        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).matchReason()).isEqualTo("主题订阅:半导体");
        assertThat(matched.get(0).keywords()).containsExactly("半导体");
    }

    @Test
    void topic_summaryContainsKey_hits() {
        // Arrange：标题不含但摘要含
        Subscription topic = sub(SubscriptionType.TOPIC, "降准", SubscriptionStatus.SUBSCRIBED);
        FeedContent content = policy(1L, "国务院意见", List.of());

        // Act + Assert：政策摘要为空 → 不命中
        assertThat(matcher.match(List.of(topic), List.of(content), Map.of()))
                .as("政策 summary 为空，主题不命中")
                .isEmpty();

        // Arrange：摘要含主题词的公告 → 命中
        FeedContent withSummary = news("n2", "无关键标题", "央行宣布降准以稳增长", 600519L);
        List<MatchedFeedContent> matched =
                matcher.match(List.of(topic), List.of(withSummary), Map.of());
        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).matchReason()).isEqualTo("主题订阅:降准");
        assertThat(matched.get(0).keywords()).containsExactly("降准");
    }

    @Test
    void topic_noKeyInContent_noHit() {
        Subscription topic = sub(SubscriptionType.TOPIC, "白酒", SubscriptionStatus.SUBSCRIBED);
        FeedContent content = news("n1", "半导体国产替代加速", "", 600519L);
        assertThat(matcher.match(List.of(topic), List.of(content), Map.of())).isEmpty();
    }

    // ---- SUBJECT 标的 ----

    @Test
    void subject_announceBySubjectId_hits() {
        // Arrange：标的订阅 subKey=600519，公告 fetched-for 600519
        Subscription subjectSub =
                sub(SubscriptionType.SUBJECT, "600519", SubscriptionStatus.SUBSCRIBED);
        SubjectRef ref = new SubjectRef(600519L, "SH600519", "贵州茅台", "白酒");
        FeedContent content = announce("a1", "2026年半年度报告", 600519L, "财务报告");

        // Act
        List<MatchedFeedContent> matched =
                matcher.match(List.of(subjectSub), List.of(content), index(ref));

        // Assert
        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).matchReason()).isEqualTo("标的订阅:贵州茅台");
        assertThat(matched.get(0).keywords()).containsExactly("贵州茅台");
    }

    @Test
    void subject_subjectIdMismatch_noHit() {
        Subscription subjectSub =
                sub(SubscriptionType.SUBJECT, "600519", SubscriptionStatus.SUBSCRIBED);
        SubjectRef ref = new SubjectRef(600519L, "SH600519", "贵州茅台", "白酒");
        FeedContent content = announce("a1", "报告", 600036L, "财务报告"); // fetched-for 招行
        assertThat(matcher.match(List.of(subjectSub), List.of(content), index(ref))).isEmpty();
    }

    @Test
    void subject_policyByIndustry_hits() {
        // Arrange：政策关联行业含标的行业
        Subscription subjectSub =
                sub(SubscriptionType.SUBJECT, "600519", SubscriptionStatus.SUBSCRIBED);
        SubjectRef ref = new SubjectRef(600519L, "SH600519", "贵州茅台", "白酒");
        FeedContent content = policy(1L, "国务院关于白酒产业的意见", List.of("白酒", "食品饮料"));

        // Act + Assert
        List<MatchedFeedContent> matched =
                matcher.match(List.of(subjectSub), List.of(content), index(ref));
        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).matchReason()).isEqualTo("标的订阅:贵州茅台");
    }

    @Test
    void subject_policyIndustryNotMatch_noHit() {
        Subscription subjectSub =
                sub(SubscriptionType.SUBJECT, "600519", SubscriptionStatus.SUBSCRIBED);
        SubjectRef ref = new SubjectRef(600519L, "SH600519", "贵州茅台", "白酒");
        FeedContent content = policy(1L, "互联网监管意见", List.of("互联网"));
        assertThat(matcher.match(List.of(subjectSub), List.of(content), index(ref))).isEmpty();
    }

    @Test
    void subject_refMissing_noHit() {
        // Arrange：subjectIndex 不含该 subjectId（Subject 解析失败）→ 不命中
        Subscription subjectSub =
                sub(SubscriptionType.SUBJECT, "600519", SubscriptionStatus.SUBSCRIBED);
        FeedContent content = announce("a1", "报告", 600519L, "财务报告");
        assertThat(matcher.match(List.of(subjectSub), List.of(content), Map.of())).isEmpty();
    }

    // ---- EVENT_TYPE 事件类型 ----

    @Test
    void eventType_typeNameOrLabel_hits() {
        Subscription ev = sub(SubscriptionType.EVENT_TYPE, "公告", SubscriptionStatus.SUBSCRIBED);
        FeedContent content = announce("a1", "年度报告", 600519L, "财务报告");
        List<MatchedFeedContent> matched = matcher.match(List.of(ev), List.of(content), Map.of());
        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).matchReason()).isEqualTo("事件类型订阅:公告");
        // 按类型命中无文本关键词 → 空列表（序列化 []）
        assertThat(matched.get(0).keywords()).isEmpty();
    }

    @Test
    void eventType_announceCategoryContains_hits() {
        // Arrange：subKey=业绩预增，公告 category 含「业绩预增」
        Subscription ev = sub(SubscriptionType.EVENT_TYPE, "业绩预增", SubscriptionStatus.SUBSCRIBED);
        FeedContent content = announce("a1", "2026 业绩预告", 600519L, "业绩预增");
        assertThat(matcher.match(List.of(ev), List.of(content), Map.of())).hasSize(1);
    }

    @Test
    void eventType_newsNotMatchAnnounceKey_noHit() {
        // Arrange：subKey=公告，内容为新闻 → 不命中（类型标签不符且无 category）
        Subscription ev = sub(SubscriptionType.EVENT_TYPE, "公告", SubscriptionStatus.SUBSCRIBED);
        FeedContent content = news("n1", "某新闻", "", 600519L);
        assertThat(matcher.match(List.of(ev), List.of(content), Map.of())).isEmpty();
    }

    // ---- POLICY_THEME 政策主题 ----

    @Test
    void policyTheme_policyTitleContains_hits() {
        Subscription theme =
                sub(SubscriptionType.POLICY_THEME, "货币政策", SubscriptionStatus.SUBSCRIBED);
        FeedContent content = policy(1L, "央行继续实施稳健货币政策", List.of("银行"));
        List<MatchedFeedContent> matched =
                matcher.match(List.of(theme), List.of(content), Map.of());
        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).matchReason()).isEqualTo("政策主题订阅:货币政策");
        assertThat(matched.get(0).keywords()).containsExactly("货币政策");
    }

    @Test
    void policyTheme_nonPolicyContent_noHit() {
        Subscription theme =
                sub(SubscriptionType.POLICY_THEME, "茅台", SubscriptionStatus.SUBSCRIBED);
        FeedContent content = news("n1", "茅台业绩超预期", "", 600519L);
        assertThat(matcher.match(List.of(theme), List.of(content), Map.of())).isEmpty();
    }

    @Test
    void policyTheme_policyTitleNotMatch_noHit() {
        Subscription theme =
                sub(SubscriptionType.POLICY_THEME, "货币政策", SubscriptionStatus.SUBSCRIBED);
        FeedContent content = policy(1L, "互联网监管意见", List.of("互联网"));
        assertThat(matcher.match(List.of(theme), List.of(content), Map.of())).isEmpty();
    }

    // ---- 退订降噪（PRD 故事 5 场景 3）----

    @Test
    void unsubscribedSubscription_skipped() {
        // Arrange：主题订阅已退订（status=0）→ 即使内容命中也不入流
        Subscription unsubscribedTopic =
                sub(SubscriptionType.TOPIC, "半导体", SubscriptionStatus.UNSUBSCRIBED);
        FeedContent content = news("n1", "半导体国产替代加速", "", 600519L);

        // Act + Assert：退订订阅不参与匹配
        assertThat(matcher.match(List.of(unsubscribedTopic), List.of(content), Map.of())).isEmpty();
    }

    @Test
    void mixedSubscribedAndUnsubscribed_onlyActiveMatches() {
        // Arrange：一活跃一退订的同主题订阅，内容命中活跃那条
        Subscription active = sub(SubscriptionType.TOPIC, "半导体", SubscriptionStatus.SUBSCRIBED);
        Subscription inactive = sub(SubscriptionType.TOPIC, "半导体", SubscriptionStatus.UNSUBSCRIBED);
        FeedContent content = news("n1", "半导体国产替代加速", "", 600519L);

        // Act：active 在前，应命中 active
        List<MatchedFeedContent> matched =
                matcher.match(List.of(active, inactive), List.of(content), Map.of());

        // Assert：仅活跃订阅匹配（退订过滤），命中一次（去重）
        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).matchReason()).isEqualTo("主题订阅:半导体");
    }

    // ---- 边界：空集合 / 首命中 ----

    @Test
    void emptySubscriptions_noMatches() {
        FeedContent content = news("n1", "半导体", "", 600519L);
        assertThat(matcher.match(List.of(), List.of(content), Map.of())).isEmpty();
    }

    @Test
    void emptyContents_noMatches() {
        Subscription topic = sub(SubscriptionType.TOPIC, "半导体", SubscriptionStatus.SUBSCRIBED);
        assertThat(matcher.match(List.of(topic), List.of(), Map.of())).isEmpty();
    }

    @Test
    void firstMatchWins_oneEntryPerContent() {
        // Arrange：一条政策同时命中主题订阅与政策主题订阅 → 仅产出一条（首次命中）
        Subscription topic = sub(SubscriptionType.TOPIC, "白酒", SubscriptionStatus.SUBSCRIBED);
        Subscription theme =
                sub(SubscriptionType.POLICY_THEME, "白酒", SubscriptionStatus.SUBSCRIBED);
        FeedContent content = policy(1L, "国务院关于白酒产业的意见", List.of("白酒"));

        // Act
        List<MatchedFeedContent> matched =
                matcher.match(List.of(topic, theme), List.of(content), Map.of());

        // Assert：去重为一条，命中原因取首次命中（主题订阅在前）
        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).matchReason()).isEqualTo("主题订阅:白酒");
    }

    // ---- MatchedFeedContent 值对象不变量 ----

    @Test
    void matchedFeedContent_nullReason_throws() {
        // Arrange + Act + Assert：命中原因不可空（值对象不变量 fail-fast）
        assertThatThrownBy(
                        () -> new MatchedFeedContent(announce("a1", "报告", 600519L, "财务报告"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchedFeedContent_blankReason_throws() {
        // Arrange + Act + Assert：空白命中原因同样拒绝
        assertThatThrownBy(
                        () -> new MatchedFeedContent(announce("a1", "报告", 600519L, "财务报告"), "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchedFeedContent_nullKeywords_normalizedToEmpty() {
        // Arrange + Act：便捷/透传构造允许 null 关键词 → 归一化为空列表（响应恒为 []）
        MatchedFeedContent matched =
                new MatchedFeedContent(announce("a1", "报告", 600519L, "财务报告"), "事件类型订阅:公告", null);

        // Assert
        assertThat(matched.keywords()).isNotNull().isEmpty();
    }
}
