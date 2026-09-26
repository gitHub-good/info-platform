package com.info.platform.application.analysis;

import com.info.platform.application.feed.FeedDashboardService;
import com.info.platform.application.feed.FeedDashboardView;
import com.info.platform.application.recommendation.RecommendationQueryService;
import com.info.platform.application.recommendation.RecommendationStatsView;
import com.info.platform.domain.analysis.IndustryCategory;
import com.info.platform.domain.analysis.NewsAnalysisRepository;
import com.info.platform.domain.feed.SourceDailyStats;
import com.info.platform.domain.feed.SourceDailyStatsRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 北极星聚合服务（应用层，M18 T158，REQ 拍板四 ns-v1）：六指标一端点只读组装，零新写面、零 LLM 调用。
 *
 * <h2>同源对账（REQ 故事 3 场景 2）</h2>
 *
 * 区块数字不是新口径——感知延迟/今日入库/源启停直接复用 {@link FeedDashboardService#dashboard()} 聚合结果（构造上同值）； 采纳率复用 {@link
 * RecommendationQueryService#stats}（adopt-v1）；成本护栏复用 {@link PipelineStatusService#status()}
 * （cost-v2）；覆盖率由 news_analysis L1 日分布现算（distinct 申万命中 ÷ 31）；稳定源由 source_daily_stats 7 天窗
 * 现算（(poll−fail)/poll ≥95%）。快照机制裁量（REQ 任务表「每日一存 or 现算+留档」）：现算 + 留档——零新表零新
 * Job，验收快照走收口报告文档态（ADR-0057）。
 *
 * <h2>窗口语义（实时态）</h2>
 *
 * 延迟/覆盖率/采纳率/成本取<b>当日</b>（与大盘 30 秒刷新口径一致）；稳定源与日净入库均值取<b>含当日 7 天窗</b>； 样本不足项走
 * INSUFFICIENT（首跑校准条款），不硬凑不遮掩。
 */
@Service
public class NorthStarService {

    /** 统计日界（与 source_daily_stats.stat_date / 大盘同口径）。 */
    static final ZoneId STAT_ZONE = ZoneId.of("Asia/Shanghai");

    /** 稳定源观察窗（蓝图口径：周成功率 ≥95%）。 */
    static final int STABLE_WINDOW_DAYS = 7;

    /** 稳定源成功率阈值（0.95，蓝图北极星口径）。 */
    static final double STABLE_SUCCESS_RATE = 0.95;

    /** 稳定源达标线（≥30，蓝图）。 */
    static final int STABLE_TARGET = 30;

    /** 行业覆盖率达标线（≥0.90，蓝图；28/31 = 0.9032 为最小达标命中数）。 */
    static final double COVERAGE_TARGET = 0.90;

    /** 感知延迟 P50 目标（≤5 分钟，蓝图）。 */
    static final long LATENCY_P50_TARGET_MILLIS = 5 * 60_000L;

    /** 日净入库达标线（7 天日均 ≥2000，蓝图）。 */
    static final double INTAKE_TARGET = 2_000;

    /** 采纳率达标线（≥0.30，蓝图）。 */
    static final double ADOPT_TARGET = 0.30;

    /** 采纳率最小曝光样本（≥30 张曝光卡，蓝图验收线）。 */
    static final long ADOPT_MIN_EXPOSURE = 30;

    /** 成本占比降级线（≤0.60，cost-v2 两级比例不变）。 */
    static final double COST_USAGE_LIMIT = 0.60;

    /** 单条成本红线（≤0.02 元 = 20000 微元，M15 拍板四口径）。 */
    static final double COST_PER_ITEM_LIMIT_MICROS = 20_000;

    private final FeedDashboardService dashboardService;
    private final NewsAnalysisRepository analysisRepository;
    private final SourceDailyStatsRepository statsRepository;
    private final PipelineStatusService pipelineStatusService;
    private final RecommendationQueryService recommendationQueryService;
    private final Clock clock;

    public NorthStarService(
            FeedDashboardService dashboardService,
            NewsAnalysisRepository analysisRepository,
            SourceDailyStatsRepository statsRepository,
            PipelineStatusService pipelineStatusService,
            RecommendationQueryService recommendationQueryService,
            Clock clock) {
        this.dashboardService = dashboardService;
        this.analysisRepository = analysisRepository;
        this.statsRepository = statsRepository;
        this.pipelineStatusService = pipelineStatusService;
        this.recommendationQueryService = recommendationQueryService;
        this.clock = clock;
    }

    /** 六指标 + 7 天趋势组装（只读，无写副作用）。 */
    public NorthStarView northStar(long userId) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), STAT_ZONE);
        String todayStartIso = today.atStartOfDay(STAT_ZONE).toInstant().toString();
        FeedDashboardView dashboard = dashboardService.dashboard();
        List<SourceDailyStats> week = weekRollup(today);
        List<NorthStarView.IntakePoint> trend = intakeTrend(today, week);
        return new NorthStarView(
                NorthStarView.BASIS,
                clock.instant().toString(),
                latencyCard(dashboard),
                coverageCard(todayStartIso),
                stableSourcesCard(dashboard, week),
                dailyIntakeCard(dashboard, trend),
                adoptRateCard(userId),
                costGuardCard(dashboard),
                trend);
    }

    // —— 六指标卡 ——

    private NorthStarView.LatencyCard latencyCard(FeedDashboardView dashboard) {
        FeedDashboardView.LatencyView latency = dashboard.global().latency();
        String status;
        if (latency.sampleCount() == 0 || latency.p50Millis() == null) {
            status = NorthStarView.STATUS_INSUFFICIENT; // 零样本：首跑校准条款
        } else {
            status =
                    latency.p50Millis() <= LATENCY_P50_TARGET_MILLIS
                            ? NorthStarView.STATUS_MET
                            : NorthStarView.STATUS_NOT_MET;
        }
        return new NorthStarView.LatencyCard(
                latency.p50Millis(),
                latency.p90Millis(),
                latency.sampleCount(),
                status,
                latency.basis());
    }

    private NorthStarView.CoverageCard coverageCard(String todayStartIso) {
        List<String> categories = analysisRepository.findDistinctMainCategorySince(todayStartIso);
        long classifiedToday = analysisRepository.countL1DoneSince(todayStartIso);
        int hit = countSwHits(categories);
        int total = IndustryCategory.SW_INDUSTRIES.size();
        Double ratio = classifiedToday == 0 ? null : (double) hit / total;
        String status;
        if (classifiedToday == 0) {
            status = NorthStarView.STATUS_INSUFFICIENT;
        } else {
            status =
                    ratio >= COVERAGE_TARGET
                            ? NorthStarView.STATUS_MET
                            : NorthStarView.STATUS_NOT_MET;
        }
        return new NorthStarView.CoverageCard(ratio, hit, total, classifiedToday, status);
    }

    /** 申万 31 命中数（容器 4 与未知值不计分子，目录白名单把守）。 */
    private static int countSwHits(List<String> categories) {
        Set<String> distinct = new HashSet<>(categories);
        return (int) distinct.stream().filter(IndustryCategory::isSwIndustry).count();
    }

    private NorthStarView.StableSourcesCard stableSourcesCard(
            FeedDashboardView dashboard, List<SourceDailyStats> week) {
        Set<Long> enabledIds =
                dashboard.sources().stream()
                        .filter(row -> row.enabled() && !row.deleted())
                        .map(FeedDashboardView.SourceRowView::sourceId)
                        .collect(java.util.stream.Collectors.toSet());
        long stable =
                week.stream()
                        .filter(stats -> enabledIds.contains(stats.sourceId()))
                        .collect(java.util.stream.Collectors.groupingBy(SourceDailyStats::sourceId))
                        .values()
                        .stream()
                        .filter(NorthStarService::isStable)
                        .count();
        String status =
                stable >= STABLE_TARGET ? NorthStarView.STATUS_MET : NorthStarView.STATUS_NOT_MET;
        return new NorthStarView.StableSourcesCard(
                (int) stable, enabledIds.size(), STABLE_WINDOW_DAYS, status);
    }

    /** 周成功率 = (poll − fail) / poll ≥95% 且有应抓轮（零轮不虚计）。 */
    private static boolean isStable(List<SourceDailyStats> rows) {
        long poll = rows.stream().mapToLong(SourceDailyStats::pollCount).sum();
        long fail = rows.stream().mapToLong(SourceDailyStats::failCount).sum();
        return poll > 0 && (double) (poll - fail) / poll >= STABLE_SUCCESS_RATE;
    }

    private NorthStarView.DailyIntakeCard dailyIntakeCard(
            FeedDashboardView dashboard, List<NorthStarView.IntakePoint> trend) {
        long todayNew = dashboard.global().todayNewCount();
        double avg7d =
                trend.stream().mapToLong(NorthStarView.IntakePoint::count).sum()
                        / (double) STABLE_WINDOW_DAYS;
        String status =
                avg7d >= INTAKE_TARGET ? NorthStarView.STATUS_MET : NorthStarView.STATUS_NOT_MET;
        return new NorthStarView.DailyIntakeCard(todayNew, avg7d, status);
    }

    private NorthStarView.AdoptRateCard adoptRateCard(long userId) {
        RecommendationStatsView stats = recommendationQueryService.stats(userId, null);
        long exposure = stats.pushDelivered() + stats.viewExposed();
        String status;
        if (exposure < ADOPT_MIN_EXPOSURE) {
            status = NorthStarView.STATUS_INSUFFICIENT; // 曝光 <30 张：首跑校准条款
        } else {
            status =
                    stats.adoptRate() >= ADOPT_TARGET
                            ? NorthStarView.STATUS_MET
                            : NorthStarView.STATUS_NOT_MET;
        }
        return new NorthStarView.AdoptRateCard(
                stats.adoptRate(), exposure, stats.adopted(), status, stats.basis());
    }

    private NorthStarView.CostGuardCard costGuardCard(FeedDashboardView dashboard) {
        PipelineStatusView pipeline = pipelineStatusService.status();
        long budget = pipeline.budgetMicros();
        long cost = pipeline.todayCostMicros();
        double usage = budget <= 0 ? 0 : (double) cost / budget;
        long intake = dashboard.global().todayNewCount();
        Double perItem = intake <= 0 ? null : (double) cost / intake;
        String status = NorthStarView.STATUS_MET;
        if (usage > COST_USAGE_LIMIT) {
            status = NorthStarView.STATUS_NOT_MET;
        } else if (perItem != null && perItem > COST_PER_ITEM_LIMIT_MICROS) {
            status = NorthStarView.STATUS_NOT_MET;
        }
        return new NorthStarView.CostGuardCard(
                usage, cost, budget, perItem, pipeline.costBasis(), status);
    }

    // —— 7 天趋势 ——

    /** 含当日 7 天窗 rollup（升序日期序列 0 填充；窗外日期防御性不计入）。 */
    private List<NorthStarView.IntakePoint> intakeTrend(
            LocalDate today, List<SourceDailyStats> week) {
        LocalDate windowStart = today.minusDays(STABLE_WINDOW_DAYS - 1L);
        List<NorthStarView.IntakePoint> points = new ArrayList<>(STABLE_WINDOW_DAYS);
        for (int i = 0; i < STABLE_WINDOW_DAYS; i++) {
            LocalDate date = windowStart.plusDays(i);
            long count =
                    week.stream()
                            .filter(stats -> date.toString().equals(stats.statDate()))
                            .mapToLong(SourceDailyStats::newCount)
                            .sum();
            points.add(new NorthStarView.IntakePoint(date.toString(), count));
        }
        return List.copyOf(points);
    }

    private List<SourceDailyStats> weekRollup(LocalDate today) {
        String from = today.minusDays(STABLE_WINDOW_DAYS - 1L).toString();
        return statsRepository.findSince(from);
    }
}
