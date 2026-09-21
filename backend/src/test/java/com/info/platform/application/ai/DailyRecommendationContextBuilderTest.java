package com.info.platform.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.SourceAdapter;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.domain.subscription.Watchlist;
import com.info.platform.domain.subscription.WatchlistItem;
import com.info.platform.domain.subscription.WatchlistRepository;
import com.info.platform.domain.subscription.WatchlistStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DailyRecommendationContextBuilder 单测（T23）：自选池指标装配（行情活跃度 + 公告/新闻事件重要性）+ 上下文 Map 投影 + 降级容错。AAA 结构。
 *
 * <p>mock 全部外部依赖（WatchlistRepository/SubjectRepository/SourceAdapter），不依赖真实行情/公告/新闻源。复用
 * SourceResult 静态工厂构造取数结果。
 */
class DailyRecommendationContextBuilderTest {

    private static final long USER_ID = 1L;
    private static final long SUBJECT_ID_1 = 100L;
    private static final long SUBJECT_ID_2 = 200L;
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC);

    private WatchlistRepository watchlistRepository;
    private SubjectRepository subjectRepository;
    private SourceAdapter quoteAdapter;
    private SourceAdapter announceAdapter;
    private SourceAdapter newsAdapter;
    private DailyRecommendationContextBuilder builder;

    @BeforeEach
    void setUp() {
        watchlistRepository = mock(WatchlistRepository.class);
        subjectRepository = mock(SubjectRepository.class);
        quoteAdapter = mock(SourceAdapter.class);
        announceAdapter = mock(SourceAdapter.class);
        newsAdapter = mock(SourceAdapter.class);
        when(quoteAdapter.sourceCode()).thenReturn(SourceCode.QUOTE);
        when(announceAdapter.sourceCode()).thenReturn(SourceCode.ANNOUNCE);
        when(newsAdapter.sourceCode()).thenReturn(SourceCode.NEWS);
        builder =
                new DailyRecommendationContextBuilder(
                        watchlistRepository,
                        subjectRepository,
                        List.of(quoteAdapter, announceAdapter, newsAdapter),
                        CLOCK);
    }

    @Test
    void buildContext_normal_assemblesPoolMetricsAndPlaceholders() {
        // Arrange：自选池 2 只标的，行情/公告/新闻均 OK（Subject 无 equals，按 any() 统一 stub）
        when(watchlistRepository.findAllByOwnerId(USER_ID))
                .thenReturn(List.of(watchlistWith(SUBJECT_ID_1, SUBJECT_ID_2)));
        when(subjectRepository.findById(SUBJECT_ID_1))
                .thenReturn(Optional.of(subject(SUBJECT_ID_1, "SH600519", "贵州茅台")));
        when(subjectRepository.findById(SUBJECT_ID_2))
                .thenReturn(Optional.of(subject(SUBJECT_ID_2, "SZ000858", "五粮液")));
        when(quoteAdapter.fetch(any())).thenReturn(quoteResult(SUBJECT_ID_1, 1.2));
        when(announceAdapter.fetch(any())).thenReturn(announceResult(SUBJECT_ID_1, 2));
        when(newsAdapter.fetch(any())).thenReturn(newsResult(SUBJECT_ID_1, 5));

        // Act
        Map<String, String> ctx = builder.buildContext(USER_ID);

        // Assert：占位符齐全，poolSize/指标快照/订阅主题/今日
        assertThat(ctx.get("poolSize")).isEqualTo("2");
        assertThat(ctx.get("subjectsMetrics")).contains("SH600519", "贵州茅台", "公告2", "新闻5");
        assertThat(ctx.get("subjectsMetrics")).contains("SZ000858", "五粮液");
        assertThat(ctx.get("subscribedThemes")).isNotBlank();
        assertThat(ctx.get("today")).isEqualTo("2026-09-21");
    }

    @Test
    void buildPoolMetrics_normal_returnsMetricsWithActivityScore() {
        // Arrange
        when(watchlistRepository.findAllByOwnerId(USER_ID))
                .thenReturn(List.of(watchlistWith(SUBJECT_ID_1, SUBJECT_ID_2)));
        when(subjectRepository.findById(SUBJECT_ID_1))
                .thenReturn(Optional.of(subject(SUBJECT_ID_1, "SH600519", "贵州茅台")));
        when(subjectRepository.findById(SUBJECT_ID_2))
                .thenReturn(Optional.of(subject(SUBJECT_ID_2, "SZ000858", "五粮液")));
        when(quoteAdapter.fetch(any())).thenReturn(quoteResult(SUBJECT_ID_1, 3.0));
        when(announceAdapter.fetch(any())).thenReturn(announceResult(SUBJECT_ID_1, 1));
        when(newsAdapter.fetch(any())).thenReturn(newsResult(SUBJECT_ID_1, 2));

        // Act
        List<PoolMetric> metrics = builder.buildPoolMetrics(USER_ID);

        // Assert：去重保序 2 只；活跃度综合分 = |3.0| + 1*2 + 2*1 = 7.0
        assertThat(metrics).hasSize(2);
        assertThat(metrics.get(0).subjectCode()).isEqualTo("SH600519");
        assertThat(metrics.get(0).activityScore()).isEqualTo(7.0);
    }

    @Test
    void buildContext_emptyPool_returnsZerosAndNa() {
        // Arrange：无启用清单项
        when(watchlistRepository.findAllByOwnerId(USER_ID)).thenReturn(List.of());

        // Act
        Map<String, String> ctx = builder.buildContext(USER_ID);
        List<PoolMetric> metrics = builder.buildPoolMetrics(USER_ID);

        // Assert：poolSize=0、subjectsMetrics=暂无、指标空
        assertThat(ctx.get("poolSize")).isEqualTo("0");
        assertThat(ctx.get("subjectsMetrics")).isEqualTo("暂无");
        assertThat(metrics).isEmpty();
    }

    @Test
    void buildContext_sourceDegraded_metricsZeroNotBlocking() {
        // Arrange：行情 MISSING、公告 FAILED、新闻 OK——降级归 0，不阻断整池
        when(watchlistRepository.findAllByOwnerId(USER_ID))
                .thenReturn(List.of(watchlistWith(SUBJECT_ID_1)));
        when(subjectRepository.findById(SUBJECT_ID_1))
                .thenReturn(Optional.of(subject(SUBJECT_ID_1, "SH600519", "贵州茅台")));
        when(quoteAdapter.fetch(any()))
                .thenReturn(SourceResult.missing(SourceCode.QUOTE, SUBJECT_ID_1, "行情源"));
        when(announceAdapter.fetch(any()))
                .thenReturn(SourceResult.failed(SourceCode.ANNOUNCE, SUBJECT_ID_1, "公告源"));
        when(newsAdapter.fetch(any())).thenReturn(newsResult(SUBJECT_ID_1, 3));

        // Act
        List<PoolMetric> metrics = builder.buildPoolMetrics(USER_ID);

        // Assert：行情/公告降级归 0，新闻 3 条不丢
        assertThat(metrics).hasSize(1);
        assertThat(metrics.get(0).changePct()).isZero();
        assertThat(metrics.get(0).announceCount()).isZero();
        assertThat(metrics.get(0).newsCount()).isEqualTo(3);
    }

    @Test
    void buildContext_subjectNotFound_skipped() {
        // Arrange：标的不存在（已删）→ 跳过，不阻断整池
        when(watchlistRepository.findAllByOwnerId(USER_ID))
                .thenReturn(List.of(watchlistWith(SUBJECT_ID_1, SUBJECT_ID_2)));
        when(subjectRepository.findById(SUBJECT_ID_1)).thenReturn(Optional.empty());
        when(subjectRepository.findById(SUBJECT_ID_2))
                .thenReturn(Optional.of(subject(SUBJECT_ID_2, "SZ000858", "五粮液")));
        when(quoteAdapter.fetch(any())).thenReturn(quoteResult(SUBJECT_ID_2, 1.0));
        when(announceAdapter.fetch(any())).thenReturn(announceResult(SUBJECT_ID_2, 0));
        when(newsAdapter.fetch(any())).thenReturn(newsResult(SUBJECT_ID_2, 0));

        // Act
        List<PoolMetric> metrics = builder.buildPoolMetrics(USER_ID);

        // Assert：仅留存在的标的 1 只
        assertThat(metrics).hasSize(1);
        assertThat(metrics.get(0).subjectCode()).isEqualTo("SZ000858");
    }

    // ==================== fixtures ====================

    private static Watchlist watchlistWith(Long... subjectIds) {
        // 通过 reconstruct 注入清单项（create 不加项）
        java.util.List<WatchlistItem> items =
                java.util.Arrays.stream(subjectIds)
                        .map(
                                sid ->
                                        WatchlistItem.reconstruct(
                                                sid * 10,
                                                1L,
                                                sid,
                                                null,
                                                WatchlistStatus.ENABLED,
                                                0L,
                                                Instant.now(),
                                                Instant.now()))
                        .toList();
        return Watchlist.reconstruct(
                1L,
                USER_ID,
                "默认清单",
                null,
                WatchlistStatus.ENABLED,
                items,
                0L,
                Instant.now(),
                Instant.now());
    }

    private static Subject subject(long id, String code, String name) {
        return Subject.reconstruct(
                id,
                SubjectCode.of(code),
                Market.A_SHARE,
                SubjectType.STOCK,
                name,
                Map.of(),
                "白酒",
                SubjectStatus.ENABLED,
                0L,
                Instant.now(),
                Instant.now());
    }

    private static SourceResult quoteResult(long subjectId, double changePct) {
        return SourceResult.ok(
                SourceCode.QUOTE,
                subjectId,
                Map.of("changePct", changePct, "price", 1680.0),
                "行情源",
                Instant.now());
    }

    private static SourceResult announceResult(long subjectId, int count) {
        java.util.List<Map<String, Object>> items =
                java.util.stream.IntStream.range(0, count)
                        .mapToObj(i -> Map.<String, Object>of("title", "公告" + i))
                        .toList();
        return SourceResult.ok(
                SourceCode.ANNOUNCE, subjectId, Map.of("items", items), "公告源", Instant.now());
    }

    private static SourceResult newsResult(long subjectId, int count) {
        java.util.List<Map<String, Object>> items =
                java.util.stream.IntStream.range(0, count)
                        .mapToObj(i -> Map.<String, Object>of("title", "新闻" + i))
                        .toList();
        return SourceResult.ok(
                SourceCode.NEWS, subjectId, Map.of("items", items), "新闻源", Instant.now());
    }
}
