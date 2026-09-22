package com.info.platform.application.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.application.ai.DailyRecommendationResult;
import com.info.platform.application.ai.DailyRecommendationService;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.SourceAdapter;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.domain.ai.TopRecommendation;
import com.info.platform.domain.policy.AiTendency;
import com.info.platform.domain.policy.PolicyItem;
import com.info.platform.domain.policy.PolicyRepository;
import com.info.platform.domain.subscription.Subscription;
import com.info.platform.domain.subscription.SubscriptionChannel;
import com.info.platform.domain.subscription.SubscriptionRepository;
import com.info.platform.domain.subscription.SubscriptionStatus;
import com.info.platform.domain.subscription.SubscriptionType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * FeedService 单元测试（T27）：mock
 * SubscriptionRepository/SubjectRepository/PolicyRepository/SourceAdapter/
 * DailyRecommendationService，复用真实 {@link FeedMatcher}（验证集成）。AAA 结构，覆盖主路径/退订降噪/空订阅/ 分页/源降级。
 *
 * <p>不依赖真实 DB/HTTP/LLM（对齐 04 测试规范 FIRST）。SourceAdapter 按 sourceCode 装配（mock 标识 ANNOUNCE/NEWS）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedServiceTest {

    private static final long ME = 1L;
    private static final long MOUTAI_ID = 600519L;
    private static final Instant NOW = Instant.parse("2026-09-21T08:00:00Z");

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubjectRepository subjectRepository;
    @Mock private PolicyRepository policyRepository;
    @Mock private DailyRecommendationService dailyRecommendationService;
    @Mock private SourceAdapter announceAdapter;
    @Mock private SourceAdapter newsAdapter;

    private FeedService service;

    @BeforeEach
    void setUp() {
        when(announceAdapter.sourceCode()).thenReturn(SourceCode.ANNOUNCE);
        when(newsAdapter.sourceCode()).thenReturn(SourceCode.NEWS);
        service =
                new FeedService(
                        subscriptionRepository,
                        subjectRepository,
                        policyRepository,
                        dailyRecommendationService,
                        new FeedMatcher(),
                        List.of(announceAdapter, newsAdapter));
    }

    // ---- 主路径：订阅命中 + 每日推荐 + 分页 ----

    @Test
    void getPersonalFeed_matchesBySubjectAndTopic_mergesDailyRec_sortedDesc() {
        // Arrange：活跃订阅 = 标的(茅台) + 主题(白酒)
        Subscription subjectSub = sub(10L, SubscriptionType.SUBJECT, "600519");
        Subscription topicSub = sub(11L, SubscriptionType.TOPIC, "白酒");
        when(subscriptionRepository.findByOwnerIdCursor(eq(ME), any(), any(), anyInt()))
                .thenReturn(List.of(subjectSub, topicSub));
        when(subjectRepository.findById(MOUTAI_ID))
                .thenReturn(Optional.of(subject(MOUTAI_ID, "SH600519", "贵州茅台", "白酒")));
        // 公告 fetched-for 茅台（命中标的订阅）
        when(announceAdapter.fetch(any(Subject.class)))
                .thenReturn(
                        SourceResult.ok(
                                SourceCode.ANNOUNCE,
                                MOUTAI_ID,
                                Map.of(
                                        "items",
                                        List.of(
                                                Map.of(
                                                        "externalId",
                                                        "a1",
                                                        "title",
                                                        "2026年半年度报告",
                                                        "publishedAt",
                                                        "2026-09-15",
                                                        "category",
                                                        "财务报告",
                                                        "url",
                                                        "https://ex/ann/1"))),
                                "公告",
                                NOW));
        // 新闻 MISSING（不阻断）
        when(newsAdapter.fetch(any(Subject.class)))
                .thenReturn(SourceResult.missing(SourceCode.NEWS, MOUTAI_ID, "新闻源(mock)"));
        // 政策标题含「白酒」（命中主题订阅）；relatedIndustries 为空 → 不命中标的订阅（行业无交集），
        // 故主题订阅独占该政策命中，标的订阅独占公告命中——两条匹配路径分别验证
        when(policyRepository.findRecent(anyInt(), any(), any(), anyInt()))
                .thenReturn(List.of(policy(1L, "国务院关于白酒产业的意见", List.of())));
        when(dailyRecommendationService.generateDaily(ME))
                .thenReturn(
                        new DailyRecommendationResult(
                                DailyRecommendationResult.STATUS_DONE,
                                List.of(new TopRecommendation("SH600036", "招商银行", "信息面活跃", 1)),
                                "AI 生成，非投资建议",
                                false));

        // Act
        FeedListView view = service.getPersonalFeed(ME, null);

        // Assert：3 条 = 1 推荐(now) + 1 政策(09-20) + 1 公告(09-15)，按 publishedAt 倒序
        assertThat(view.items()).hasSize(3);
        assertThat(view.nextCursor()).isNull();
        assertThat(view.items().get(0).type()).isEqualTo(FeedItemType.RECOMMENDATION);
        assertThat(view.items().get(0).matchReason()).isEqualTo("每日推荐");
        assertThat(view.items().get(1).type()).isEqualTo(FeedItemType.POLICY);
        assertThat(view.items().get(1).matchReason()).isEqualTo("主题订阅:白酒");
        assertThat(view.items().get(2).type()).isEqualTo(FeedItemType.ANNOUNCE);
        assertThat(view.items().get(2).matchReason()).isEqualTo("标的订阅:贵州茅台");
        assertThat(view.items().get(2).id()).isEqualTo(3L);
    }

    // ---- 退订降噪（PRD 故事 5 场景 3）----

    @Test
    void getPersonalFeed_unsubscribedOnly_returnsRecommendationsOnly() {
        // Arrange：仅一条已退订标的订阅 → 活跃订阅为空 → 短路返回每日推荐
        Subscription unsubscribed =
                Subscription.reconstruct(
                        10L,
                        ME,
                        SubscriptionType.SUBJECT,
                        "600519",
                        SubscriptionChannel.IN_APP,
                        SubscriptionStatus.UNSUBSCRIBED,
                        0L,
                        NOW,
                        NOW);
        when(subscriptionRepository.findByOwnerIdCursor(eq(ME), eq(null), eq(null), anyInt()))
                .thenReturn(List.of(unsubscribed));
        when(dailyRecommendationService.generateDaily(ME))
                .thenReturn(
                        new DailyRecommendationResult(
                                DailyRecommendationResult.STATUS_DONE,
                                List.of(new TopRecommendation("SH600036", "招商银行", "活跃", 1)),
                                "AI 生成，非投资建议",
                                false));

        // Act
        FeedListView view = service.getPersonalFeed(ME, null);

        // Assert：退订订阅不取内容、不匹配，仅每日推荐入流
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).type()).isEqualTo(FeedItemType.RECOMMENDATION);
        verify(subjectRepository, never()).findById(anyLong());
        verify(policyRepository, never()).findRecent(anyInt(), any(), any(), anyInt());
        verify(announceAdapter, never()).fetch(any(Subject.class));
    }

    @Test
    void getPersonalFeed_noSubscriptions_returnsRecommendationsOnly() {
        // Arrange：无任何订阅 → 仅每日推荐
        when(subscriptionRepository.findByOwnerIdCursor(eq(ME), eq(null), eq(null), anyInt()))
                .thenReturn(List.of());
        when(dailyRecommendationService.generateDaily(ME))
                .thenReturn(
                        new DailyRecommendationResult(
                                DailyRecommendationResult.STATUS_DONE,
                                List.of(new TopRecommendation("SH600036", "招商银行", "活跃", 1)),
                                "AI 生成，非投资建议",
                                false));

        // Act
        FeedListView view = service.getPersonalFeed(ME, null);

        // Assert
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).type()).isEqualTo(FeedItemType.RECOMMENDATION);
        verify(policyRepository, never()).findRecent(anyInt(), any(), any(), anyInt());
    }

    // ---- 游标分页（id > cursor LIMIT 20）----

    @Test
    void getPersonalFeed_moreThan20_returnsFirstPageWithNextCursor() {
        // Arrange：标的订阅 + 公告返回 25 条（全部命中标的订阅）+ 1 推荐 = 26 条
        when(subscriptionRepository.findByOwnerIdCursor(eq(ME), eq(null), eq(null), anyInt()))
                .thenReturn(List.of(sub(10L, SubscriptionType.SUBJECT, "600519")));
        when(subjectRepository.findById(MOUTAI_ID))
                .thenReturn(Optional.of(subject(MOUTAI_ID, "SH600519", "贵州茅台", "白酒")));
        when(announceAdapter.fetch(any(Subject.class)))
                .thenReturn(
                        SourceResult.ok(
                                SourceCode.ANNOUNCE, MOUTAI_ID, announceItems(25), "公告", NOW));
        when(newsAdapter.fetch(any(Subject.class)))
                .thenReturn(SourceResult.missing(SourceCode.NEWS, MOUTAI_ID, "新闻源(mock)"));
        when(policyRepository.findRecent(anyInt(), any(), any(), anyInt())).thenReturn(List.of());
        when(dailyRecommendationService.generateDaily(ME))
                .thenReturn(
                        new DailyRecommendationResult(
                                DailyRecommendationResult.STATUS_DONE,
                                List.of(new TopRecommendation("SH600036", "招商银行", "活跃", 1)),
                                "AI 生成，非投资建议",
                                false));

        // Act：首页 cursor=null → 取前 20
        FeedListView first = service.getPersonalFeed(ME, null);

        // Assert：满页 20 + nextCursor=20（末条合成 id）
        assertThat(first.items()).hasSize(20);
        assertThat(first.nextCursor()).isEqualTo(20L);
        // 推荐(now) 排首位
        assertThat(first.items().get(0).type()).isEqualTo(FeedItemType.RECOMMENDATION);

        // Act：第二页 cursor=20 → 取剩余 6
        FeedListView second = service.getPersonalFeed(ME, 20L);
        assertThat(second.items()).hasSize(6);
        assertThat(second.nextCursor()).isNull();
    }

    // ---- 源降级（MISSING/FAILED 不阻断）----

    @Test
    void getPersonalFeed_sourceFailedOrMissing_returnsDailyRecOnly() {
        // Arrange：标的订阅，公告 FAILED + 新闻 MISSING + 无政策 → 0 命中，仅每日推荐
        when(subscriptionRepository.findByOwnerIdCursor(eq(ME), eq(null), eq(null), anyInt()))
                .thenReturn(List.of(sub(10L, SubscriptionType.SUBJECT, "600519")));
        when(subjectRepository.findById(MOUTAI_ID))
                .thenReturn(Optional.of(subject(MOUTAI_ID, "SH600519", "贵州茅台", "白酒")));
        when(announceAdapter.fetch(any(Subject.class)))
                .thenReturn(SourceResult.failed(SourceCode.ANNOUNCE, MOUTAI_ID, "公告源(mock)"));
        when(newsAdapter.fetch(any(Subject.class)))
                .thenReturn(SourceResult.missing(SourceCode.NEWS, MOUTAI_ID, "新闻源(mock)"));
        when(policyRepository.findRecent(anyInt(), any(), any(), anyInt())).thenReturn(List.of());
        when(dailyRecommendationService.generateDaily(ME))
                .thenReturn(
                        new DailyRecommendationResult(
                                DailyRecommendationResult.STATUS_DONE,
                                List.of(new TopRecommendation("SH600036", "招商银行", "活跃", 1)),
                                "AI 生成，非投资建议",
                                false));

        // Act
        FeedListView view = service.getPersonalFeed(ME, null);

        // Assert：源降级不阻断，仅每日推荐入流
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).type()).isEqualTo(FeedItemType.RECOMMENDATION);
    }

    // ---- fixtures ----

    private static Subscription sub(Long id, SubscriptionType type, String key) {
        return Subscription.reconstruct(
                id,
                ME,
                type,
                key,
                SubscriptionChannel.IN_APP,
                SubscriptionStatus.SUBSCRIBED,
                0L,
                NOW,
                NOW);
    }

    private static Subject subject(Long id, String code, String name, String industry) {
        return Subject.reconstruct(
                id,
                SubjectCode.of(code),
                Market.A_SHARE,
                SubjectType.STOCK,
                name,
                Map.of(),
                industry,
                SubjectStatus.ENABLED,
                0L,
                null,
                null);
    }

    private static PolicyItem policy(long id, String title, List<String> industries) {
        return PolicyItem.reconstruct(
                id,
                title,
                "国务院",
                LocalDate.of(2026, 9, 20),
                null,
                industries,
                AiTendency.UNJUDGED,
                "https://gov/" + id,
                NOW,
                NOW);
    }

    /** 构造 N 条公告 item Map（externalId/title/publishedAt/category/url，同 subjectId 归属）。 */
    private static Map<String, Object> announceItems(int n) {
        List<Map<String, Object>> items = new ArrayList<>(n);
        IntStream.range(0, n)
                .forEach(
                        i ->
                                items.add(
                                        Map.of(
                                                "externalId",
                                                "a" + i,
                                                "title",
                                                "公告" + i,
                                                "publishedAt",
                                                "2026-09-15",
                                                "category",
                                                "财务报告",
                                                "url",
                                                "https://ex/ann/" + i)));
        return Map.of("items", List.copyOf(items));
    }
}
