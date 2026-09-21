package com.info.platform.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.domain.ai.BriefContent;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.TopRecommendation;
import com.info.platform.domain.common.UserContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DailyRecommendationService 单测（T23）：受理触发（复用 T21 createBrief briefType=4）→ 轮询 → 解析 Top5 → 规则兜底 /
 * 空池提示。AAA 结构。
 *
 * <p>mock AIBriefService（createBrief + getBrief）与
 * DailyRecommendationContextBuilder（buildPoolMetrics），不依赖真实 LLM。 轮询参数用极小值（pollInterval=10ms /
 * maxWait=50ms）使超时兜底用例毫秒级完成（对齐 FIRST 快原则）。
 */
class DailyRecommendationServiceTest {

    private static final long USER_ID = 1L;
    private static final long TASK_ID = 7L;

    private AIBriefService aiBriefService;
    private DailyRecommendationContextBuilder contextBuilder;
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
        // 极小轮询参数：超时兜底用例毫秒级完成
        service = new DailyRecommendationService(aiBriefService, contextBuilder, 10L, 50L);
        when(aiBriefService.createBrief(any(), any())).thenReturn(TASK_ID);
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
    void generateDaily_failed_fallsBackToActivityRanking() {
        // Arrange：LLM 失败（status=2，无 content）→ 规则兜底
        when(aiBriefService.getBrief(TASK_ID))
                .thenReturn(new AIBriefView(2, null, null, BriefContent.DEFAULT_DISCLAIMER));
        when(contextBuilder.buildPoolMetrics(USER_ID))
                .thenReturn(
                        List.of(
                                new PoolMetric("SH600519", "贵州茅台", 3.0, 1, 2), // score 7
                                new PoolMetric("SZ000858", "五粮液", 1.0, 0, 0))); // score 1

        // Act
        DailyRecommendationResult result = service.generateDaily(USER_ID);

        // Assert：规则兜底，按活跃度综合分降序取前 5
        assertThat(result.status()).isEqualTo(DailyRecommendationResult.STATUS_FALLBACK);
        assertThat(result.fallback()).isTrue();
        assertThat(result.topRecommend()).hasSize(2);
        assertThat(result.topRecommend().get(0).subjectCode()).isEqualTo("SH600519"); // score 高
        assertThat(result.topRecommend().get(1).subjectCode()).isEqualTo("SZ000858");
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
}
