package com.info.platform.application.analysis;

import com.info.platform.domain.analysis.GuardLevel;
import com.info.platform.domain.analysis.NewsAnalysisRepository;
import com.info.platform.domain.analysis.NewsAnalysisRepository.L1SlaStats;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 管道状态服务（应用层，M15 T121 基础版 → T125 完整版，方案 §4.6/§4.8）：护栏面（level/预算/阈值/校准值）+ 当日各段计数 + SLA 口径 + 最近批窗口 +
 * 当日管道成本——降级横幅、成本报表、验收断言三处同源。
 *
 * <p>成本与级别经 {@link PipelineGuardService}（llm_call_log scene 5/6/7 当日 SUCCESS 口径，Asia/Shanghai
 * 日界——统计语义优先本地日， V22 source_daily_stats 先例）。SLA 口径（方案 §4.10）：{@code l1RateIn30min = DONE 行中
 * classified_at − fetched_at ≤ 30min 占比}；{@code l2Coverage = EXTRACTED ÷
 * (EXTRACTED+NO_EVENT+FAILED+DEFERRED 当日命中)}，无样本记 null（P50/P90 先例）。
 */
@Service
public class PipelineStatusService {

    /** 统计日界（Asia/Shanghai——与 source_daily_stats.stat_date 同口径）。 */
    static final ZoneId STAT_ZONE = ZoneId.of("Asia/Shanghai");

    private final NewsAnalysisRepository repository;
    private final NewsPipelineService pipelineService;
    private final PipelineGuardService guardService;
    private final PipelineSettings settings;
    private final Clock clock;

    public PipelineStatusService(
            NewsAnalysisRepository repository,
            NewsPipelineService pipelineService,
            PipelineGuardService guardService,
            PipelineSettings settings,
            Clock clock) {
        this.repository = repository;
        this.pipelineService = pipelineService;
        this.guardService = guardService;
        this.settings = settings;
        this.clock = clock;
    }

    /** 当前管道状态（完整版）。 */
    public PipelineStatusView status() {
        String todayStart = todayStartIso();
        GuardLevel level = guardService.currentLevel();
        long budget = settings.dailyBudgetMicros();
        return new PipelineStatusView(
                "NEWS_PIPELINE",
                level,
                todayCounts(todayStart),
                PipelineStatusView.LastTickView.of(pipelineService.lastTick()),
                guardService.todayCostMicros(),
                budget,
                Math.round(budget * settings.degradeRatio()),
                Math.round(budget * settings.fuseRatio()),
                settings.calibratedPerItemMicros(),
                settings.costBasis());
    }

    private String todayStartIso() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), STAT_ZONE);
        return today.atStartOfDay(STAT_ZONE).toInstant().toString();
    }

    private PipelineStatusView.TodayView todayCounts(String todayStartIso) {
        Map<String, Long> l0 = repository.countL0ByResultSince(todayStartIso);
        Map<String, Long> l1 = repository.countL1ByStatusSince(todayStartIso);
        Map<String, Long> l2 = repository.countL2ByStatusSince(todayStartIso);
        L1SlaStats sla = repository.countL1SlaSince(todayStartIso);
        long coverageDenominator =
                countOf(l2, "EXTRACTED")
                        + countOf(l2, "NO_EVENT")
                        + countOf(l2, "FAILED")
                        + countOf(l2, "DEFERRED");
        return new PipelineStatusView.TodayView(
                countOf(l0, "PASS"),
                countOf(l0, "NOISE"),
                countOf(l0, "NEAR_DUP"),
                countOf(l1, "DONE"),
                countOf(l1, "PENDING"),
                countOf(l1, "FAILED"),
                countOf(l2, "EXTRACTED"),
                countOf(l2, "DEFERRED"),
                ratioOrNull(sla.within30Min(), sla.done()),
                ratioOrNull(countOf(l2, "EXTRACTED"), coverageDenominator));
    }

    private static Double ratioOrNull(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return (double) numerator / denominator;
    }

    private static long countOf(Map<String, Long> counts, String key) {
        return counts.getOrDefault(key, 0L);
    }
}
