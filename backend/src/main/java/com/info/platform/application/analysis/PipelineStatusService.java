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
 * 日界——统计语义优先本地日， V22 source_daily_stats 先例）。SLA 口径：{@code l1RateIn30min = DONE 行中 classified_at −
 * fetched_at ≤ 30min 占比}；<b>L2 覆盖率自 T137 起切 coverage-v2 口径（AMB-02 落地，M16 方案 §4.10）</b>： {@code
 * l2Coverage = EXTRACTED ÷ (EXTRACTED+FAILED+DEFERRED+滞留 SELECTED)}——NO_EVENT（正确拒绝）移出未覆盖分母、 独立为
 * {@code noEventRatio} 指标（NO_EVENT ÷ 进入 L2 处理的高价值条目总数），防口径诱导硬凑事件；阈值 80% 判定不变； 比率无样本记 null（P50/P90
 * 先例）。
 */
@Service
public class PipelineStatusService {

    /** 统计日界（Asia/Shanghai——与 source_daily_stats.stat_date 同口径）。 */
    static final ZoneId STAT_ZONE = ZoneId.of("Asia/Shanghai");

    /** L2 覆盖率口径版本串（T137 切 coverage-v2：NO_EVENT 移出分母 + noEventRatio 独立指标）。 */
    static final String COVERAGE_BASIS = "coverage-v2";

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
                settings.costBasis(),
                COVERAGE_BASIS);
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
        // coverage-v2（T137，AMB-02）：分母剔除 NO_EVENT（正确拒绝不计失败）+ 计入当日滞留 SELECTED（未终态）
        long extracted = countOf(l2, "EXTRACTED");
        long noEvent = countOf(l2, "NO_EVENT");
        long failed = countOf(l2, "FAILED");
        long deferred = countOf(l2, "DEFERRED");
        long selectedStuck = countOf(l2, "SELECTED");
        long coverageDenominator = extracted + failed + deferred + selectedStuck;
        long noEventDenominator = extracted + noEvent + failed + deferred + selectedStuck;
        return new PipelineStatusView.TodayView(
                countOf(l0, "PASS"),
                countOf(l0, "NOISE"),
                countOf(l0, "NEAR_DUP"),
                countOf(l1, "DONE"),
                countOf(l1, "PENDING"),
                countOf(l1, "FAILED"),
                extracted,
                deferred,
                ratioOrNull(sla.within30Min(), sla.done()),
                ratioOrNull(extracted, coverageDenominator),
                ratioOrNull(noEvent, noEventDenominator));
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
