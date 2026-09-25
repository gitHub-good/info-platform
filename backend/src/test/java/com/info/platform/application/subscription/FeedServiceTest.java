package com.info.platform.application.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        when(dailyRecommendationService.readDaily(ME))
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
        // 推荐项无文本命中词 → 空列表（T43 keywords 契约）
        assertThat(view.items().get(0).keywords()).isEmpty();
        assertThat(view.items().get(1).type()).isEqualTo(FeedItemType.POLICY);
        assertThat(view.items().get(1).matchReason()).isEqualTo("主题订阅:白酒");
        // 主题订阅命中词 = subKey（标题必现，可高亮）
        assertThat(view.items().get(1).keywords()).containsExactly("白酒");
        assertThat(view.items().get(2).type()).isEqualTo(FeedItemType.ANNOUNCE);
        assertThat(view.items().get(2).matchReason()).isEqualTo("标的订阅:贵州茅台");
        // 标的订阅命中词 = 标的展示名（文本未必出现，前端仅出现时高亮）
        assertThat(view.items().get(2).keywords()).containsExactly("贵州茅台");
        assertThat(view.items().get(2).id()).isEqualTo(3L);
    }

    // ---- P1-5a 回归（修前红）：feed 不阻塞等待每日简报生成、只读缓存 ----

    @Test
    void getPersonalFeed_briefNotTerminal_returnsFastWithPendingFlag() {
        // Arrange：无订阅 + 当日简报在途（readDaily 返回 pending）；generateDaily 若被调用则模拟 30s 轮询阻塞 400ms
        when(subscriptionRepository.findByOwnerIdCursor(eq(ME), eq(null), eq(null), anyInt()))
                .thenReturn(List.of());
        when(dailyRecommendationService.readDaily(ME))
                .thenReturn(
                        new DailyRecommendationResult(
                                DailyRecommendationResult.STATUS_PENDING,
                                List.of(),
                                "AI 生成，非投资建议",
                                false,
                                true));
        when(dailyRecommendationService.generateDaily(ME))
                .thenAnswer(
                        inv -> {
                            Thread.sleep(400);
                            return new DailyRecommendationResult(
                                    DailyRecommendationResult.STATUS_DONE,
                                    List.of(new TopRecommendation("SH600036", "招商银行", "活跃", 1)),
                                    "AI 生成，非投资建议",
                                    false);
                        });

        // Act
        long start = System.nanoTime();
        FeedListView view = service.getPersonalFeed(ME, null);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        // Assert：feed 只读缓存不阻塞（修前红：mock generateDaily 阻塞 400ms 时实测 elapsed ≥400ms），
        // 未终态不等待不受理，透出 recommendationPending 占位标志且无推荐条目
        assertThat(elapsedMillis).as("feed 装配耗时应远小于简报生成阻塞时长").isLessThan(250L);
        assertThat(view.recommendationPending()).isTrue();
        assertThat(view.items()).isEmpty();
        verify(dailyRecommendationService, never()).generateDaily(anyLong());
    }

    @Test
    void getPersonalFeed_briefTerminal_mergesRecommendationEntries() {
        // Arrange：无订阅 + 当日简报已完成（readDaily 命中缓存 Top5）
        when(subscriptionRepository.findByOwnerIdCursor(eq(ME), eq(null), eq(null), anyInt()))
                .thenReturn(List.of());
        when(dailyRecommendationService.readDaily(ME))
                .thenReturn(
                        new DailyRecommendationResult(
                                DailyRecommendationResult.STATUS_DONE,
                                List.of(new TopRecommendation("SH600036", "招商银行", "活跃", 1)),
                                "AI 生成，非投资建议",
                                false,
                                false));

        // Act
        FeedListView view = service.getPersonalFeed(ME, null);

        // Assert：已完成简报正常进推荐条目，pending=false
        assertThat(view.recommendationPending()).isFalse();
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).type()).isEqualTo(FeedItemType.RECOMMENDATION);
        assertThat(view.items().get(0).subjectCode()).isEqualTo("SH600036");
    }

    @Test
    void getPersonalFeed_secondPage_doesNotTriggerDailyGeneration() {
        // Arrange：标的订阅 + 25 条公告 + 推荐就绪（翻页走 readDaily，不重复触发 generateDaily）
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
        when(dailyRecommendationService.readDaily(ME))
                .thenReturn(
                        new DailyRecommendationResult(
                                DailyRecommendationResult.STATUS_DONE,
                                List.of(new TopRecommendation("SH600036", "招商银行", "活跃", 1)),
                                "AI 生成，非投资建议",
                                false,
                                false));

        // Act：首页 + 第二页
        service.getPersonalFeed(ME, null);
        service.getPersonalFeed(ME, 20L);

        // Assert：翻页不重复触发简报生成（每页只读一次当日缓存）
        verify(dailyRecommendationService, never()).generateDaily(anyLong());
        verify(dailyRecommendationService, times(2)).readDaily(ME);
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
        when(dailyRecommendationService.readDaily(ME))
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
        when(dailyRecommendationService.readDaily(ME))
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
        when(dailyRecommendationService.readDaily(ME))
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
        when(dailyRecommendationService.readDaily(ME))
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

    // ---- M11 / REQ-20260925-08：contentId 稳定标识契约（四不变量）----

    @Test
    void getPersonalFeed_contentId_typePrefixedPerType() {
        // Arrange：主路径同款三类型场景（公告 externalId / 政策 policyId / 推荐 subjectCode）
        arrangeSubjectFeedWithOneAnnounceOnePolicy();

        // Act
        FeedListView view = service.getPersonalFeed(ME, null);

        // Assert：形态 {type}:{源稳定 id}——类型前缀可辨（防跨类型条目撞 FEED 去重键）
        assertThat(view.items()).hasSize(3);
        assertThat(view.items().get(0).contentId()).isEqualTo("recommendation:SH600036");
        assertThat(view.items().get(1).contentId()).isEqualTo("policy:1");
        assertThat(view.items().get(2).contentId()).isEqualTo("announce:a1");
    }

    @Test
    void getPersonalFeed_contentId_stableAcrossRequests_neverCursorId() {
        // Arrange：25 条公告；两次请求之间源新增 1 条更新公告 → 游标 id 整体漂移（ADR-0019 场景）
        when(subscriptionRepository.findByOwnerIdCursor(eq(ME), any(), any(), anyInt()))
                .thenReturn(List.of(sub(10L, SubscriptionType.SUBJECT, "600519")));
        when(subjectRepository.findById(MOUTAI_ID))
                .thenReturn(Optional.of(subject(MOUTAI_ID, "SH600519", "贵州茅台", "白酒")));
        when(newsAdapter.fetch(any(Subject.class)))
                .thenReturn(SourceResult.missing(SourceCode.NEWS, MOUTAI_ID, "新闻源(mock)"));
        when(policyRepository.findRecent(anyInt(), any(), any(), anyInt())).thenReturn(List.of());
        when(dailyRecommendationService.readDaily(ME)).thenReturn(doneRec());
        when(announceAdapter.fetch(any(Subject.class)))
                .thenReturn(
                        SourceResult.ok(
                                SourceCode.ANNOUNCE, MOUTAI_ID, announceItems(25), "公告", NOW));

        // Act：请求一（无新公告）
        FeedListView first = service.getPersonalFeed(ME, null);
        // 请求二：源新增一条更新的公告（externalId=anew、publishedAt 更晚）→ 排序靠前，游标 id 整体后移
        when(announceAdapter.fetch(any(Subject.class)))
                .thenReturn(
                        SourceResult.ok(
                                SourceCode.ANNOUNCE,
                                MOUTAI_ID,
                                announceItemsWithNewerExtra(),
                                "公告",
                                NOW));

        FeedListView second = service.getPersonalFeed(ME, null);

        // Assert：同一条目（a5）两次请求 contentId 相同、游标 id 漂移、contentId 不等于任何游标 id
        FeedItem firstA5 = findByContentId(first, "announce:a05");
        FeedItem secondA5 = findByContentId(second, "announce:a05");
        assertThat(firstA5).isNotNull();
        assertThat(secondA5).isNotNull();
        assertThat(secondA5.id()).isNotEqualTo(firstA5.id()); // 游标漂移实证
        assertThat(secondA5.contentId()).isEqualTo(firstA5.contentId()); // 稳定标识不漂移
        assertThat(firstA5.contentId()).isNotEqualTo(String.valueOf(firstA5.id()));
        assertThat(secondA5.contentId()).isNotEqualTo(String.valueOf(secondA5.id()));
    }

    @Test
    void getPersonalFeed_contentId_overlongSourceId_clampedTo200() {
        // Arrange：源 externalId 超长（250）→ 透出前防御截断至 200（对齐 readingEvent contentRef 校验上限）
        when(subscriptionRepository.findByOwnerIdCursor(eq(ME), any(), any(), anyInt()))
                .thenReturn(List.of(sub(10L, SubscriptionType.SUBJECT, "600519")));
        when(subjectRepository.findById(MOUTAI_ID))
                .thenReturn(Optional.of(subject(MOUTAI_ID, "SH600519", "贵州茅台", "白酒")));
        when(newsAdapter.fetch(any(Subject.class)))
                .thenReturn(SourceResult.missing(SourceCode.NEWS, MOUTAI_ID, "新闻源(mock)"));
        when(policyRepository.findRecent(anyInt(), any(), any(), anyInt())).thenReturn(List.of());
        when(dailyRecommendationService.readDaily(ME)).thenReturn(doneRec());
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
                                                        "x".repeat(250),
                                                        "title",
                                                        "超长标识公告",
                                                        "publishedAt",
                                                        "2026-09-15",
                                                        "category",
                                                        "财务报告",
                                                        "url",
                                                        "https://ex/ann/long"))),
                                "公告",
                                NOW));

        // Act
        FeedListView view = service.getPersonalFeed(ME, null);

        // Assert：contentId 长度恰为 200 且保留类型前缀
        FeedItem announce =
                view.items().stream()
                        .filter(i -> i.type() == FeedItemType.ANNOUNCE)
                        .findFirst()
                        .orElseThrow();
        assertThat(announce.contentId()).hasSize(200);
        assertThat(announce.contentId()).startsWith("announce:");
    }

    @Test
    void getPersonalFeed_contentId_sourceIdMissing_nullNotCursorFallback() {
        // Arrange：公告源缺 externalId（Map 无该键）→ contentId=null（禁用合成游标 id 兜底，ADR-0019 教训）
        when(subscriptionRepository.findByOwnerIdCursor(eq(ME), any(), any(), anyInt()))
                .thenReturn(List.of(sub(10L, SubscriptionType.SUBJECT, "600519")));
        when(subjectRepository.findById(MOUTAI_ID))
                .thenReturn(Optional.of(subject(MOUTAI_ID, "SH600519", "贵州茅台", "白酒")));
        when(newsAdapter.fetch(any(Subject.class)))
                .thenReturn(SourceResult.missing(SourceCode.NEWS, MOUTAI_ID, "新闻源(mock)"));
        when(policyRepository.findRecent(anyInt(), any(), any(), anyInt())).thenReturn(List.of());
        when(dailyRecommendationService.readDaily(ME)).thenReturn(doneRec());
        when(announceAdapter.fetch(any(Subject.class)))
                .thenReturn(
                        SourceResult.ok(
                                SourceCode.ANNOUNCE,
                                MOUTAI_ID,
                                Map.of(
                                        "items",
                                        List.of(
                                                Map.of(
                                                        "title",
                                                        "无标识公告",
                                                        "publishedAt",
                                                        "2026-09-15",
                                                        "category",
                                                        "财务报告",
                                                        "url",
                                                        "https://ex/ann/noid"))),
                                "公告",
                                NOW));

        // Act
        FeedListView view = service.getPersonalFeed(ME, null);

        // Assert：该条 contentId=null（前端据此不埋点），推荐条目 contentId 照常派生
        FeedItem announce =
                view.items().stream()
                        .filter(i -> i.type() == FeedItemType.ANNOUNCE)
                        .findFirst()
                        .orElseThrow();
        assertThat(announce.contentId()).isNull();
        assertThat(announce.id()).isEqualTo(2L); // 游标 id 照常回填（不拿去做 contentId）
        assertThat(view.items().get(0).contentId()).isEqualTo("recommendation:SH600036");
    }

    // ---- fixtures ----

    /** 主路径场景：标的订阅 + 主题订阅（白酒）+ 1 公告（externalId=a1）+ 1 政策（id=1，标题命中主题）+ 推荐就绪（SH600036）。 */
    private void arrangeSubjectFeedWithOneAnnounceOnePolicy() {
        when(subscriptionRepository.findByOwnerIdCursor(eq(ME), any(), any(), anyInt()))
                .thenReturn(
                        List.of(
                                sub(10L, SubscriptionType.SUBJECT, "600519"),
                                sub(11L, SubscriptionType.TOPIC, "白酒")));
        when(subjectRepository.findById(MOUTAI_ID))
                .thenReturn(Optional.of(subject(MOUTAI_ID, "SH600519", "贵州茅台", "白酒")));
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
        when(newsAdapter.fetch(any(Subject.class)))
                .thenReturn(SourceResult.missing(SourceCode.NEWS, MOUTAI_ID, "新闻源(mock)"));
        when(policyRepository.findRecent(anyInt(), any(), any(), anyInt()))
                .thenReturn(List.of(policy(1L, "国务院关于白酒产业的意见", List.of())));
        when(dailyRecommendationService.readDaily(ME)).thenReturn(doneRec());
    }

    /** 每日推荐就绪结果（Top1：SH600036）。 */
    private static DailyRecommendationResult doneRec() {
        return new DailyRecommendationResult(
                DailyRecommendationResult.STATUS_DONE,
                List.of(new TopRecommendation("SH600036", "招商银行", "活跃", 1)),
                "AI 生成，非投资建议",
                false);
    }

    private static FeedItem findByContentId(FeedListView view, String contentId) {
        return view.items().stream()
                .filter(i -> contentId.equals(i.contentId()))
                .findFirst()
                .orElse(null);
    }

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

    /** 25 条基础公告 + 1 条更新的公告（externalId=anew、publishedAt 更晚，模拟两次请求之间新内容到达）。 */
    private static Map<String, Object> announceItemsWithNewerExtra() {
        List<Map<String, Object>> items = new ArrayList<>(announceItemList(25));
        items.add(
                Map.of(
                        "externalId",
                        "anew",
                        "title",
                        "新到达公告",
                        "publishedAt",
                        "2026-09-16",
                        "category",
                        "财务报告",
                        "url",
                        "https://ex/ann/new"));
        return Map.of("items", List.copyOf(items));
    }

    /** 构造 N 条公告 item Map（externalId/title/publishedAt/category/url，同 subjectId 归属）。 */
    private static Map<String, Object> announceItems(int n) {
        return Map.of("items", List.copyOf(announceItemList(n)));
    }

    private static List<Map<String, Object>> announceItemList(int n) {
        List<Map<String, Object>> items = new ArrayList<>(n);
        IntStream.range(0, n)
                .forEach(
                        i ->
                                items.add(
                                        Map.of(
                                                "externalId",
                                                // 零填充两位：同日发布时排序副键（contentId）字典序 == 数值序，
                                                // 页内条目顺序可预期（分页/游标漂移断言依赖）
                                                String.format("a%02d", i),
                                                "title",
                                                "公告" + i,
                                                "publishedAt",
                                                "2026-09-15",
                                                "category",
                                                "财务报告",
                                                "url",
                                                "https://ex/ann/" + i)));
        return items;
    }
}
