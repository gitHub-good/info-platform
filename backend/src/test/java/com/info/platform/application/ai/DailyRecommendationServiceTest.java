package com.info.platform.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.domain.ai.BriefContent;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.TopRecommendation;
import com.info.platform.domain.common.UserContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DailyRecommendationService 单测（T23 受理/轮询/解析 + T29 相关性兜底排序）。AAA 结构。
 *
 * <p>mock AIBriefService（createBrief +
 * getBrief）、DailyRecommendationContextBuilder（buildPoolMetrics）与
 * RecommendationPersonalizer（画像）；评分器用真实实例（纯函数，确定性）。轮询参数用极小值使超时兜底毫秒级完成。
 */
class DailyRecommendationServiceTest {

    private static final long USER_ID = 1L;
    private static final long TASK_ID = 7L;

    private AIBriefService aiBriefService;
    private DailyRecommendationContextBuilder contextBuilder;
    private RecommendationPersonalizer personalizer;
    private DailyRecommendationService service;

    private final BriefContent dailyContent =
            new BriefContent(
                    "今日推荐",
                    List.of(),
                    "中性",
                    "理由",
                    "关注",
                    List.of(),
                    "AI 生成，非投资建议",
                    List.of(
                            new TopRecommendation("SH600519", "贵州茅台", "信息面活跃", 2),
                            new TopRecommendation("SZ000858", "五粮液", "公告密集", 1),
                            new TopRecommendation("SH600036", "招商银行", "异动", 3)));

    @BeforeEach
    void setUp() {
        aiBriefService = mock(AIBriefService.class);
        contextBuilder = mock(DailyRecommendationContextBuilder.class);
        personalizer = mock(RecommendationPersonalizer.class);
        // 极小轮询参数：超时兜底用例毫秒级完成；评分器用真实实例（纯函数）
        service =
                new DailyRecommendationService(
                        aiBriefService,
                        contextBuilder,
                        personalizer,
                        new RecommendationRelevanceScorer(),
                        10L,
                        50L);
        when(aiBriefService.createBrief(any(), any())).thenReturn(TASK_ID);
        when(personalizer.buildProfile(anyLong())).thenReturn(UserInterestProfile.EMPTY);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void generateDaily_done_returnsTop5SortedByRank() {
        // Arrange：当日简报已完成，content 含 topRecommend（rank 乱序）
        when(aiBriefService.getBrief(TASK_ID))
                .thenReturn(new AIBriefView(1, dailyContent, List.of("http://f"), "AI 生成，非投资建议"));

        // Act
        DailyRecommendationResult result = service.generateDaily(USER_ID);

        // Assert：调 createBrief(null, DAILY_RECOMMEND) + Top5 按 rank 升序 + fallback=false
        verify(aiBriefService).createBrief(null, BriefType.DAILY_RECOMMEND);
        assertThat(result.status()).isEqualTo(DailyRecommendationResult.STATUS_DONE);
        assertThat(result.fallback()).isFalse();
        assertThat(result.topRecommend()).hasSize(3);
        assertThat(result.topRecommend().get(0).subjectCode()).isEqualTo("SZ000858"); // rank=1
        assertThat(result.topRecommend().get(1).subjectCode()).isEqualTo("SH600519"); // rank=2
        assertThat(result.topRecommend().get(2).subjectCode()).isEqualTo("SH600036"); // rank=3
        assertThat(result.disclaimer()).isEqualTo("AI 生成，非投资建议");
    }

    @Test
    void generateDaily_pendingThenDone_pollsAndReturnsTop5() {
        // Arrange：首次查询处理中（status=0），第二次完成（含 topRecommend）
        when(aiBriefService.getBrief(TASK_ID))
                .thenReturn(new AIBriefView(0, null, null, BriefContent.DEFAULT_DISCLAIMER))
                .thenReturn(new AIBriefView(1, dailyContent, List.of(), "AI 生成，非投资建议"));

        // Act
        DailyRecommendationResult result = service.generateDaily(USER_ID);

        // Assert：轮询命中第二次 → DONE Top5
        assertThat(result.status()).isEqualTo(DailyRecommendationResult.STATUS_DONE);
        assertThat(result.topRecommend()).hasSize(3);
    }

    @Test
    void generateDaily_failed_emptyProfile_fallsBackToActivityRanking() {
        // Arrange：LLM 失败 + 新用户空画像（边界：退化为既有活跃度排序）
        when(aiBriefService.getBrief(TASK_ID))
                .thenReturn(new AIBriefView(2, null, null, BriefContent.DEFAULT_DISCLAIMER));
        when(contextBuilder.buildPoolMetrics(USER_ID))
                .thenReturn(
                        List.of(
                                new PoolMetric("SH600519", "贵州茅台", 3.0, 1, 2), // 活跃度 7
                                new PoolMetric("SZ000858", "五粮液", 1.0, 0, 0))); // 活跃度 1

        // Act
        DailyRecommendationResult result = service.generateDaily(USER_ID);

        // Assert：规则兜底，按活跃度降序 + rank 1 起递增 + 理由含综合分（可标注）
        assertThat(result.status()).isEqualTo(DailyRecommendationResult.STATUS_FALLBACK);
        assertThat(result.fallback()).isTrue();
        assertThat(result.topRecommend()).hasSize(2);
        assertThat(result.topRecommend().get(0).subjectCode()).isEqualTo("SH600519");
        assertThat(result.topRecommend().get(0).rank()).isEqualTo(1);
        assertThat(result.topRecommend().get(1).subjectCode()).isEqualTo("SZ000858");
        assertThat(result.topRecommend().get(1).rank()).isEqualTo(2);
        assertThat(result.topRecommend().get(0).reason()).contains("信息面活跃").contains("综合分7.0");
    }

    @Test
    void generateDaily_failed_personalized_relevanceOrderingWithHitFactors() {
        // Arrange：LLM 失败 + 画像（SZ000858 已读+订阅，活跃度低；SH600519 活跃度高但无个性化命中）
        when(aiBriefService.getBrief(TASK_ID))
                .thenReturn(new AIBriefView(2, null, null, BriefContent.DEFAULT_DISCLAIMER));
        when(contextBuilder.buildPoolMetrics(USER_ID))
                .thenReturn(
                        List.of(
                                new PoolMetric("SH600519", "贵州茅台", 3.0, 1, 2, 100L, "白酒"), // 7.0
                                new PoolMetric("SZ000858", "五粮液", 0.5, 0, 0, 200L, "白酒"))); // 0.5
        when(personalizer.buildProfile(USER_ID))
                .thenReturn(
                        new UserInterestProfile(
                                List.of(),
                                List.of(
                                        new UserInterestProfile.SubscribedSubject(
                                                200L, "SZ000858", "五粮液")),
                                List.of(
                                        new UserInterestProfile.SubjectReadStat(
                                                200L,
                                                "SZ000858",
                                                "五粮液",
                                                3,
                                                LocalDate.of(2026, 9, 21),
                                                3.0))));

        // Act
        DailyRecommendationResult result = service.generateDaily(USER_ID);

        // Assert：SZ000858 相关分 0.5+6.0+10.0=16.5 > SH600519 7.0 → 升至首位且理由带命中因子
        assertThat(result.topRecommend().get(0).subjectCode()).isEqualTo("SZ000858");
        assertThat(result.topRecommend().get(0).reason())
                .contains("个性化相关")
                .contains("已读热度+10.0")
                .contains("标的订阅+6.0")
                .contains("综合分16.5");
        assertThat(result.topRecommend().get(1).subjectCode()).isEqualTo("SH600519");
    }

    @Test
    void generateDaily_timeoutAlwaysPending_fallsBack() {
        // Arrange：始终处理中（status=0）→ maxWait 到期 → 规则兜底
        when(aiBriefService.getBrief(TASK_ID))
                .thenReturn(new AIBriefView(0, null, null, BriefContent.DEFAULT_DISCLAIMER));
        when(contextBuilder.buildPoolMetrics(USER_ID))
                .thenReturn(List.of(new PoolMetric("SH600519", "贵州茅台", 2.0, 0, 0)));

        // Act
        DailyRecommendationResult result = service.generateDaily(USER_ID);

        // Assert：超时降级为规则兜底（对齐 §5 降级预案）
        assertThat(result.status()).isEqualTo(DailyRecommendationResult.STATUS_FALLBACK);
        assertThat(result.topRecommend()).hasSize(1);
    }

    @Test
    void generateDaily_emptyPool_returnsEmptyStatus() {
        // Arrange：LLM 失败 + 自选池空 → 空提示
        when(aiBriefService.getBrief(TASK_ID))
                .thenReturn(new AIBriefView(2, null, null, BriefContent.DEFAULT_DISCLAIMER));
        when(contextBuilder.buildPoolMetrics(USER_ID)).thenReturn(List.of());

        // Act
        DailyRecommendationResult result = service.generateDaily(USER_ID);

        // Assert：status=3 空池，Top5 空，免责恒附
        assertThat(result.status()).isEqualTo(DailyRecommendationResult.STATUS_EMPTY);
        assertThat(result.topRecommend()).isEmpty();
        assertThat(result.fallback()).isFalse();
        assertThat(result.disclaimer()).isEqualTo("AI 生成，非投资建议");
    }

    @Test
    void generateDaily_doneButEmptyTopRecommend_fallsBack() {
        // Arrange：完成但 LLM 未输出 topRecommend（空数组）→ 规则兜底
        BriefContent emptyTop =
                new BriefContent(
                        "今日推荐", List.of(), "中性", "理由", "关注", List.of(), "AI 生成，非投资建议", List.of());
        when(aiBriefService.getBrief(TASK_ID))
                .thenReturn(new AIBriefView(1, emptyTop, List.of(), "AI 生成，非投资建议"));
        when(contextBuilder.buildPoolMetrics(USER_ID))
                .thenReturn(List.of(new PoolMetric("SH600519", "贵州茅台", 1.5, 0, 0)));

        // Act
        DailyRecommendationResult result = service.generateDaily(USER_ID);

        // Assert：topRecommend 空降级规则兜底
        assertThat(result.status()).isEqualTo(DailyRecommendationResult.STATUS_FALLBACK);
        assertThat(result.topRecommend()).hasSize(1);
    }

    @Test
    void generateDaily_clearsUserContextAfterReturn() {
        // Arrange：generateDaily 在 createBrief 前置 UserContext，finally 必清空（防线程池复用串味）
        UserContext.set(new UserContext.Principal(999L, "stale"));
        when(aiBriefService.getBrief(TASK_ID))
                .thenReturn(new AIBriefView(2, null, null, BriefContent.DEFAULT_DISCLAIMER));
        when(contextBuilder.buildPoolMetrics(anyLong())).thenReturn(List.of());

        // Act
        service.generateDaily(USER_ID);

        // Assert：返回后 ThreadLocal 已清空（finally clear），无残留串味
        assertThat(UserContext.get()).isNull();
        verify(aiBriefService).createBrief(null, BriefType.DAILY_RECOMMEND);
    }

    // ==================== readDaily（P1-5a feed 只读，修前红：feed 原走 generateDaily 阻塞）
    // ====================

    @Test
    void readDaily_noBriefToday_returnsPendingWithoutTriggeringGeneration() {
        // Arrange：当日无任务（feed 只读：不受理、不触发生成）
        when(aiBriefService.findTodayBrief(null, BriefType.DAILY_RECOMMEND))
                .thenReturn(Optional.empty());

        // Act
        DailyRecommendationResult result = service.readDaily(USER_ID);

        // Assert：pending 占位（Top5 空 + recommendationPending=true），零副作用
        assertThat(result.status()).isEqualTo(DailyRecommendationResult.STATUS_PENDING);
        assertThat(result.recommendationPending()).isTrue();
        assertThat(result.topRecommend()).isEmpty();
        verify(aiBriefService, never()).createBrief(any(), any());
        verify(aiBriefService, never()).getBrief(anyLong());
    }

    @Test
    void readDaily_briefInFlight_returnsPendingWithoutPolling() {
        // Arrange：当日任务 PENDING（在途生成中）——feed 不轮询等待（修前 generateDaily 最长阻塞 30s）
        when(aiBriefService.findTodayBrief(null, BriefType.DAILY_RECOMMEND))
                .thenReturn(
                        Optional.of(
                                new AIBriefView(0, null, null, BriefContent.DEFAULT_DISCLAIMER)));

        // Act
        DailyRecommendationResult result = service.readDaily(USER_ID);

        // Assert：pending 占位，不受理、不轮询
        assertThat(result.status()).isEqualTo(DailyRecommendationResult.STATUS_PENDING);
        assertThat(result.recommendationPending()).isTrue();
        verify(aiBriefService, never()).createBrief(any(), any());
        verify(aiBriefService, never()).getBrief(anyLong());
    }

    @Test
    void readDaily_briefDone_returnsCachedTop5() {
        // Arrange：当日简报已完成（幂等缓存命中直返）
        when(aiBriefService.findTodayBrief(null, BriefType.DAILY_RECOMMEND))
                .thenReturn(
                        Optional.of(new AIBriefView(1, dailyContent, List.of(), "AI 生成，非投资建议")));

        // Act
        DailyRecommendationResult result = service.readDaily(USER_ID);

        // Assert：正常消费已完成简报（Top5 按 rank 升序），pending=false
        assertThat(result.status()).isEqualTo(DailyRecommendationResult.STATUS_DONE);
        assertThat(result.recommendationPending()).isFalse();
        assertThat(result.topRecommend()).hasSize(3);
        assertThat(result.topRecommend().get(0).subjectCode()).isEqualTo("SZ000858");
    }

    @Test
    void readDaily_briefFailed_fallsBackToRuleRanking() {
        // Arrange：当日简报 FAILED → 规则兜底（并行池指标，快速）
        when(aiBriefService.findTodayBrief(null, BriefType.DAILY_RECOMMEND))
                .thenReturn(
                        Optional.of(
                                new AIBriefView(2, null, null, BriefContent.DEFAULT_DISCLAIMER)));
        when(contextBuilder.buildPoolMetrics(USER_ID))
                .thenReturn(List.of(new PoolMetric("SH600519", "贵州茅台", 2.0, 0, 0)));

        // Act
        DailyRecommendationResult result = service.readDaily(USER_ID);

        // Assert：兜底排序非 pending
        assertThat(result.status()).isEqualTo(DailyRecommendationResult.STATUS_FALLBACK);
        assertThat(result.recommendationPending()).isFalse();
        assertThat(result.topRecommend()).hasSize(1);
    }
}
