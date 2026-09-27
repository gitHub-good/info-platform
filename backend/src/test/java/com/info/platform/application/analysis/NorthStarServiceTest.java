package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.info.platform.application.analysis.PipelineStatusView.TodayView;
import com.info.platform.application.feed.FeedDashboardService;
import com.info.platform.application.feed.FeedDashboardView;
import com.info.platform.application.recommendation.RecommendationQueryService;
import com.info.platform.application.recommendation.RecommendationStatsView;
import com.info.platform.domain.analysis.GuardLevel;
import com.info.platform.domain.analysis.IndustryCategory;
import com.info.platform.domain.analysis.NewsAnalysisRepository;
import com.info.platform.domain.feed.SourceDailyStats;
import com.info.platform.domain.feed.SourceDailyStatsRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 北极星聚合服务单元测试（M18 T158，REQ 拍板四 ns-v1）：mock 全部数据面端口 + 固定时钟——
 * 六指标聚合口径（与既有端点同源对账）、达标线判定边界（恰等值语义）、样本不足校准条款与空数据日。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NorthStarServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-26T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ZoneId ZONE_SH = ZoneId.of("Asia/Shanghai");
    private static final String TODAY = "2026-09-26"; // Asia/Shanghai 本地日
    private static final String TODAY_START_ISO =
            LocalDate.parse(TODAY).atStartOfDay(ZONE_SH).toInstant().toString();
    private static final String WEEK_FROM = "2026-09-20"; // 含当日 7 天窗

    private static final long USER_ID = 1L;
    private static final String COST_BASIS = "cost-v2:m18-30src";

    @Mock private FeedDashboardService dashboardService;
    @Mock private NewsAnalysisRepository analysisRepository;
    @Mock private SourceDailyStatsRepository statsRepository;
    @Mock private PipelineStatusService pipelineStatusService;
    @Mock private RecommendationQueryService recommendationQueryService;

    private NorthStarService service;

    @BeforeEach
    void setUp() {
        service =
                new NorthStarService(
                        dashboardService,
                        analysisRepository,
                        statsRepository,
                        pipelineStatusService,
                        recommendationQueryService,
                        CLOCK);
    }

    // —— 测试数据工厂 ——

    private static FeedDashboardView.LatencyView latency(long p50, long p90, long sample) {
        return new FeedDashboardView.LatencyView(
                p50, p90, sample, FeedDashboardView.LATENCY_BASIS, List.of());
    }

    private static FeedDashboardView.SourceRowView row(
            long sourceId, boolean enabled, boolean deleted) {
        return new FeedDashboardView.SourceRowView(
                sourceId,
                "src_" + sourceId,
                "源" + sourceId,
                "分类",
                "preset",
                15,
                enabled,
                true,
                deleted,
                null,
                10,
                5,
                0,
                0,
                100,
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                "ok",
                false);
    }

    /** 31 行现役源（enabled 非 deleted）。 */
    private static List<FeedDashboardView.SourceRowView> rows31() {
        return LongStream.rangeClosed(1, 31).mapToObj(i -> row(i, true, false)).toList();
    }

    private static FeedDashboardView dashboard(
            long todayNew,
            FeedDashboardView.LatencyView latency,
            List<FeedDashboardView.SourceRowView> rows) {
        return new FeedDashboardView(
                new FeedDashboardView.GlobalView(todayNew, 30, 31, 1, latency),
                rows,
                List.of(),
                0L); // T211：failuresHiddenRecovered 缺省 0（北极星口径不消费失败列表）
    }

    private static SourceDailyStats stat(
            long sourceId, String date, long poll, long fail, long newCount) {
        return new SourceDailyStats(null, sourceId, date, poll, fail, newCount, 0, NOW, NOW);
    }

    /** 31 源 × 7 天：除 badSourceId 当日行替换为指定 poll/fail 外全部 10 轮 0 败（每源日增 10 条）。 */
    private static List<SourceDailyStats> week31Sources(
            long badSourceId, long badPoll, long badFail) {
        List<SourceDailyStats> out = new ArrayList<>();
        for (long sourceId = 1; sourceId <= 31; sourceId++) {
            for (int i = 0; i < 7; i++) {
                String date = LocalDate.parse(WEEK_FROM).plusDays(i).toString();
                if (sourceId == badSourceId && TODAY.equals(date)) {
                    out.add(stat(sourceId, date, badPoll, badFail, 10));
                } else {
                    out.add(stat(sourceId, date, 10, 0, 10));
                }
            }
        }
        return out;
    }

    private static PipelineStatusView pipelineStatus(long todayCostMicros, long budgetMicros) {
        return new PipelineStatusView(
                "NEWS_PIPELINE",
                GuardLevel.NORMAL,
                TodayView.empty(),
                null,
                todayCostMicros,
                budgetMicros,
                Math.round(budgetMicros * 0.6),
                Math.round(budgetMicros * 0.9),
                1100,
                COST_BASIS,
                "coverage-v2");
    }

    private static RecommendationStatsView adopt(long delivered, long exposed, long adopted) {
        long exposure = delivered + exposed;
        return new RecommendationStatsView(
                TODAY,
                delivered,
                exposed,
                adopted,
                exposure == 0 ? null : (double) adopted / exposure,
                RecommendationStatsView.BASIS);
    }

    private void stubBaselineBoard() {
        when(dashboardService.dashboard())
                .thenReturn(dashboard(2100, latency(240_000, 600_000, 120), rows31()));
        when(analysisRepository.findDistinctMainCategorySince(TODAY_START_ISO))
                .thenReturn(List.of());
        when(analysisRepository.countL1DoneSince(TODAY_START_ISO)).thenReturn(0L);
        when(statsRepository.findSince(WEEK_FROM)).thenReturn(week31Sources(-1, 0, 0));
        when(pipelineStatusService.status()).thenReturn(pipelineStatus(0, 2_600_000));
        when(recommendationQueryService.stats(USER_ID, null)).thenReturn(adopt(0, 0, 0));
    }

    // —— 聚合口径：区块数字 = 既有端点同源（对账断言，REQ 故事 3 场景 2） ——

    @Test
    void 六指标聚合与达标判定_全达标形态() {
        stubBaselineBoard();
        // 覆盖率 28 命中 + 2 容器（容器不计分子）
        List<String> categories =
                new ArrayList<>(List.copyOf(IndustryCategory.SW_INDUSTRIES).subList(0, 28));
        categories.add("宏观");
        categories.add("国际");
        when(analysisRepository.findDistinctMainCategorySince(TODAY_START_ISO))
                .thenReturn(categories);
        when(analysisRepository.countL1DoneSince(TODAY_START_ISO)).thenReturn(500L);
        when(pipelineStatusService.status()).thenReturn(pipelineStatus(1_000_000, 2_600_000));
        when(recommendationQueryService.stats(USER_ID, null))
                .thenReturn(adopt(20, 30, 18)); // 50 曝光 36%

        NorthStarView view = service.northStar(USER_ID);

        assertThat(view.basis()).isEqualTo("ns-v1");
        assertThat(view.generatedAt()).isEqualTo(NOW.toString());
        // 感知延迟：与大盘端点同值同口径（增量轮 v1）
        assertThat(view.latency().p50Millis()).isEqualTo(240_000);
        assertThat(view.latency().p90Millis()).isEqualTo(600_000);
        assertThat(view.latency().sampleCount()).isEqualTo(120);
        assertThat(view.latency().basis()).isEqualTo(FeedDashboardView.LATENCY_BASIS);
        assertThat(view.latency().status()).isEqualTo(NorthStarView.STATUS_MET); // ≤5min
        // 覆盖率：28/31 ≥90%，容器不进分子
        assertThat(view.coverage().hitIndustries()).isEqualTo(28);
        assertThat(view.coverage().totalIndustries()).isEqualTo(31);
        assertThat(view.coverage().coverageRatio())
                .isCloseTo(28.0 / 31, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(view.coverage().classifiedToday()).isEqualTo(500);
        assertThat(view.coverage().status()).isEqualTo(NorthStarView.STATUS_MET);
        // 稳定源：31 源 7 天成功率 100% ≥95%
        assertThat(view.stableSources().stableCount()).isEqualTo(31);
        assertThat(view.stableSources().enabledCount()).isEqualTo(31);
        assertThat(view.stableSources().status()).isEqualTo(NorthStarView.STATUS_MET); // ≥30
        // 日净入库：今日 2100 / 7 天均值 310 → 可判定未达标（如实）
        assertThat(view.dailyIntake().todayNew()).isEqualTo(2100);
        assertThat(view.dailyIntake().avg7d()).isEqualTo(310.0);
        assertThat(view.dailyIntake().status()).isEqualTo(NorthStarView.STATUS_NOT_MET);
        // 采纳率：36% 且曝光 50 ≥30 张
        assertThat(view.adoptRate().adoptRate())
                .isCloseTo(0.36, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(view.adoptRate().exposure()).isEqualTo(50);
        assertThat(view.adoptRate().adopted()).isEqualTo(18);
        assertThat(view.adoptRate().status()).isEqualTo(NorthStarView.STATUS_MET);
        // 成本护栏两线：占比 38.5% ≤60%、单条 ≈476 微元 ≤20000
        assertThat(view.costGuard().todayCostMicros()).isEqualTo(1_000_000);
        assertThat(view.costGuard().budgetMicros()).isEqualTo(2_600_000);
        assertThat(view.costGuard().usageRatio())
                .isCloseTo(1.0 / 2.6, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(view.costGuard().perItemMicros())
                .isCloseTo(1_000_000.0 / 2100, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(view.costGuard().costBasis()).isEqualTo(COST_BASIS);
        assertThat(view.costGuard().status()).isEqualTo(NorthStarView.STATUS_MET);
        // 迷你趋势：7 天升序全 310
        assertThat(view.intakeTrend()).hasSize(7);
        assertThat(view.intakeTrend().get(0).date()).isEqualTo(WEEK_FROM);
        assertThat(view.intakeTrend().get(6).date()).isEqualTo(TODAY);
        assertThat(view.intakeTrend()).allMatch(p -> p.count() == 310);
    }

    // —— 达标线判定边界（恰等值语义） ——

    @Test
    void 感知延迟边界_恰5分钟达标_超1毫秒未达标_零样本走校准条款() {
        stubBaselineBoard();

        when(dashboardService.dashboard())
                .thenReturn(dashboard(100, latency(300_000, 900_000, 10), rows31()));
        assertThat(service.northStar(USER_ID).latency().status())
                .isEqualTo(NorthStarView.STATUS_MET);

        when(dashboardService.dashboard())
                .thenReturn(dashboard(100, latency(300_001, 900_000, 10), rows31()));
        assertThat(service.northStar(USER_ID).latency().status())
                .isEqualTo(NorthStarView.STATUS_NOT_MET);

        // 零样本：p50/p90 为 null（与 0 可区分，大盘契约同款）
        when(dashboardService.dashboard())
                .thenReturn(
                        dashboard(
                                100,
                                new FeedDashboardView.LatencyView(
                                        null, null, 0, FeedDashboardView.LATENCY_BASIS, List.of()),
                                rows31()));
        NorthStarView.LatencyCard zero = service.northStar(USER_ID).latency();
        assertThat(zero.p50Millis()).isNull();
        assertThat(zero.p90Millis()).isNull();
        assertThat(zero.status()).isEqualTo(NorthStarView.STATUS_INSUFFICIENT);
    }

    @Test
    void 覆盖率边界_28行业达标_27行业未达标_零归类走校准条款() {
        stubBaselineBoard();
        when(analysisRepository.countL1DoneSince(TODAY_START_ISO)).thenReturn(100L);
        List<String> sw = List.copyOf(IndustryCategory.SW_INDUSTRIES);

        when(analysisRepository.findDistinctMainCategorySince(TODAY_START_ISO))
                .thenReturn(sw.subList(0, 28));
        assertThat(service.northStar(USER_ID).coverage().status())
                .isEqualTo(NorthStarView.STATUS_MET);

        when(analysisRepository.findDistinctMainCategorySince(TODAY_START_ISO))
                .thenReturn(sw.subList(0, 27)); // 27/31 ≈ 87.1% < 90%
        assertThat(service.northStar(USER_ID).coverage().status())
                .isEqualTo(NorthStarView.STATUS_NOT_MET);

        when(analysisRepository.findDistinctMainCategorySince(TODAY_START_ISO))
                .thenReturn(List.of());
        when(analysisRepository.countL1DoneSince(TODAY_START_ISO)).thenReturn(0L);
        assertThat(service.northStar(USER_ID).coverage().status())
                .isEqualTo(NorthStarView.STATUS_INSUFFICIENT);
    }

    @Test
    void 稳定源边界_恰30达标_94成功率不计稳_停用与归档源不计数() {
        stubBaselineBoard();
        // 源 1 当日替换为 60 轮 4 败 → 窗口 120 轮 4 败 = 96.7% 仍稳定（边界上沿）
        when(statsRepository.findSince(WEEK_FROM)).thenReturn(week31Sources(1, 60, 4));
        assertThat(service.northStar(USER_ID).stableSources().stableCount()).isEqualTo(31);

        // 源 1 当日 60 轮 7 败 → 120 轮 7 败 ≈ 94.2% <95% → 稳定 30 恰达标
        when(statsRepository.findSince(WEEK_FROM)).thenReturn(week31Sources(1, 60, 7));
        NorthStarView.StableSourcesCard thirty = service.northStar(USER_ID).stableSources();
        assertThat(thirty.stableCount()).isEqualTo(30);
        assertThat(thirty.status()).isEqualTo(NorthStarView.STATUS_MET);

        // 源 2 同样 94.2% → 稳定 29 未达标
        List<SourceDailyStats> twoBad = week31Sources(1, 60, 7);
        twoBad.removeIf(s -> s.sourceId() == 2 && TODAY.equals(s.statDate()));
        twoBad.add(stat(2, TODAY, 60, 7, 10));
        when(statsRepository.findSince(WEEK_FROM)).thenReturn(twoBad);
        NorthStarView.StableSourcesCard twentyNine = service.northStar(USER_ID).stableSources();
        assertThat(twentyNine.stableCount()).isEqualTo(29);
        assertThat(twentyNine.status()).isEqualTo(NorthStarView.STATUS_NOT_MET);

        // 应抓轮为 0 的源不计稳（无样本不虚计）
        List<SourceDailyStats> silent = week31Sources(-1, 0, 0);
        silent.removeIf(s -> s.sourceId() == 3);
        when(statsRepository.findSince(WEEK_FROM)).thenReturn(silent);
        assertThat(service.northStar(USER_ID).stableSources().stableCount()).isEqualTo(30);

        // enabledCount 只计启用未删源
        when(dashboardService.dashboard())
                .thenReturn(
                        dashboard(
                                100,
                                latency(240_000, 600_000, 10),
                                List.of(
                                        row(1, true, false),
                                        row(2, false, false),
                                        row(3, true, true))));
        assertThat(service.northStar(USER_ID).stableSources().enabledCount()).isEqualTo(1);
    }

    @Test
    void 采纳率边界_曝光不足30张走校准条款_恰30达标_低于未达标() {
        stubBaselineBoard();

        when(recommendationQueryService.stats(USER_ID, null)).thenReturn(adopt(29, 0, 29));
        assertThat(service.northStar(USER_ID).adoptRate().status())
                .isEqualTo(NorthStarView.STATUS_INSUFFICIENT); // 样本不足，不判达标

        when(recommendationQueryService.stats(USER_ID, null)).thenReturn(adopt(30, 0, 9));
        assertThat(service.northStar(USER_ID).adoptRate().status())
                .isEqualTo(NorthStarView.STATUS_MET); // 恰 30%

        when(recommendationQueryService.stats(USER_ID, null)).thenReturn(adopt(31, 0, 9));
        assertThat(service.northStar(USER_ID).adoptRate().status())
                .isEqualTo(NorthStarView.STATUS_NOT_MET); // 29%

        when(recommendationQueryService.stats(USER_ID, null)).thenReturn(adopt(0, 0, 0));
        NorthStarView.AdoptRateCard none = service.northStar(USER_ID).adoptRate();
        assertThat(none.adoptRate()).isNull();
        assertThat(none.exposure()).isZero();
        assertThat(none.status()).isEqualTo(NorthStarView.STATUS_INSUFFICIENT);
    }

    @Test
    void 成本护栏两线_占比恰60达标_单条恰20000微元达标_超线未达标_零入库单条null() {
        stubBaselineBoard();
        when(dashboardService.dashboard())
                .thenReturn(dashboard(1000, latency(240_000, 600_000, 10), rows31()));

        // 占比恰 60%（1,560,000 / 2,600,000）+ 单条 1560 微元 → 两线全过
        when(pipelineStatusService.status()).thenReturn(pipelineStatus(1_560_000, 2_600_000));
        assertThat(service.northStar(USER_ID).costGuard().status())
                .isEqualTo(NorthStarView.STATUS_MET);

        // 占比 61% → 未达标
        when(pipelineStatusService.status()).thenReturn(pipelineStatus(1_586_000, 2_600_000));
        assertThat(service.northStar(USER_ID).costGuard().status())
                .isEqualTo(NorthStarView.STATUS_NOT_MET);

        // 单条恰 20000 微元（20,000,000 / 1000 条；预算放大保占比面安全）→ 达标
        when(pipelineStatusService.status()).thenReturn(pipelineStatus(20_000_000, 100_000_000));
        assertThat(service.northStar(USER_ID).costGuard().status())
                .isEqualTo(NorthStarView.STATUS_MET);

        // 单条 20100 微元超线 → 未达标
        when(pipelineStatusService.status()).thenReturn(pipelineStatus(20_100_000, 100_000_000));
        assertThat(service.northStar(USER_ID).costGuard().status())
                .isEqualTo(NorthStarView.STATUS_NOT_MET);

        // 今日零入库 → 单条成本 null（占比面单独判定，不因无分母误判）
        when(dashboardService.dashboard())
                .thenReturn(dashboard(0, latency(240_000, 600_000, 10), rows31()));
        when(pipelineStatusService.status()).thenReturn(pipelineStatus(500_000, 2_600_000));
        NorthStarView.CostGuardCard zeroIntake = service.northStar(USER_ID).costGuard();
        assertThat(zeroIntake.perItemMicros()).isNull();
        assertThat(zeroIntake.status()).isEqualTo(NorthStarView.STATUS_MET);
    }

    @Test
    void 空数据日_样本类指标走校准条款_规模类可判定_趋势7天零填充不抛异常() {
        when(dashboardService.dashboard()).thenReturn(dashboard(0, latency(0, 0, 0), List.of()));
        when(analysisRepository.findDistinctMainCategorySince(TODAY_START_ISO))
                .thenReturn(List.of());
        when(analysisRepository.countL1DoneSince(TODAY_START_ISO)).thenReturn(0L);
        when(statsRepository.findSince(WEEK_FROM)).thenReturn(List.of());
        when(pipelineStatusService.status()).thenReturn(pipelineStatus(0, 2_600_000));
        when(recommendationQueryService.stats(USER_ID, null)).thenReturn(adopt(0, 0, 0));

        NorthStarView view = service.northStar(USER_ID);

        assertThat(view.latency().status()).isEqualTo(NorthStarView.STATUS_INSUFFICIENT);
        assertThat(view.coverage().status()).isEqualTo(NorthStarView.STATUS_INSUFFICIENT);
        assertThat(view.stableSources().stableCount()).isZero();
        assertThat(view.stableSources().status())
                .isEqualTo(NorthStarView.STATUS_NOT_MET); // 0 < 30 可判定
        assertThat(view.dailyIntake().avg7d()).isZero();
        assertThat(view.dailyIntake().status()).isEqualTo(NorthStarView.STATUS_NOT_MET);
        assertThat(view.adoptRate().status()).isEqualTo(NorthStarView.STATUS_INSUFFICIENT);
        assertThat(view.costGuard().usageRatio()).isZero();
        assertThat(view.costGuard().perItemMicros()).isNull();
        assertThat(view.intakeTrend()).hasSize(7);
        assertThat(view.intakeTrend()).allMatch(p -> p.count() == 0);
    }

    @Test
    void 趋势窗口_缺行日期零填充且升序_窗口外日期防御性不计入() {
        stubBaselineBoard();
        // 窗口首日 100 条 + 当日 50 条；窗外日（09-19）行防御性排除
        when(statsRepository.findSince(WEEK_FROM))
                .thenReturn(
                        List.of(
                                stat(1, WEEK_FROM, 10, 0, 100),
                                stat(1, TODAY, 10, 0, 50),
                                stat(1, "2026-09-19", 10, 0, 999)));

        NorthStarView view = service.northStar(USER_ID);

        assertThat(view.intakeTrend()).extracting(NorthStarView.IntakePoint::date).isSorted();
        assertThat(view.intakeTrend()).hasSize(7);
        assertThat(view.intakeTrend().get(0).count()).isEqualTo(100);
        assertThat(view.intakeTrend().get(1).count()).isZero(); // 缺行日 0 填充
        assertThat(view.intakeTrend().get(6).count()).isEqualTo(50);
        assertThat(view.dailyIntake().avg7d())
                .isCloseTo(150.0 / 7, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(view.intakeTrend()).allMatch(p -> p.count() <= 100); // 窗外 999 不入趋势
    }
}
