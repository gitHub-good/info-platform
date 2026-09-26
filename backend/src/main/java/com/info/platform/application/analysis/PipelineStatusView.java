package com.info.platform.application.analysis;

import com.info.platform.application.analysis.NewsPipelineService.LastTick;
import com.info.platform.domain.analysis.GuardLevel;

/**
 * 管道状态视图（M15 T121 基础版 → T125 完整版，方案 §4.8 {@code GET /api/v1/pipeline/status}）：护栏面 + 当日各状态计数 + 最近批窗口
 * + 当日管道成本（scene 5/6/7）。
 *
 * <p><b>字段只增不改</b>（前端契约稳定）：T125 在基础版上增护栏面（level/预算/阈值/校准值）与当日 L2/SLA 口径；{@code todayCostMicros} 口径自
 * T125 起为管道全量（scene 5/6/7 SUCCESS 求和，日报 scene 7 随 T124 产生留痕后自然计入）。
 *
 * @param jobKey 任务键（NEWS_PIPELINE）
 * @param level 护栏级别（NORMAL/DEGRADED/FUSED，降级横幅数据面同源）
 * @param today 当日计数（Asia/Shanghai 日界；SLA 比率无样本为 null）
 * @param lastTick 最近一轮批窗口（未跑过为 null）
 * @param todayCostMicros 当日管道成本（llm_call_log scene∈{5,6,7} SUCCESS 求和，微元）
 * @param budgetMicros 当日预算（pipeline.budget.dailyBudgetMicros，微元）
 * @param degradeAtMicros 降级阈值（budget × degradeRatio，微元）
 * @param fuseAtMicros 熔断阈值（budget × fuseRatio，微元）
 * @param calibratedPerItemMicros 单条成本校准值（pipeline.budget，微元；初值 1100 = 附录 A 实测+推算）
 * @param costBasis 成本口径版本串（校准写入时升版，不静默）
 * @param coverageBasis L2 覆盖率口径版本串（T137 起 coverage-v2——NO_EVENT 移出分母 + noEventRatio 独立；切换不静默）
 */
public record PipelineStatusView(
        String jobKey,
        GuardLevel level,
        TodayView today,
        LastTickView lastTick,
        long todayCostMicros,
        long budgetMicros,
        long degradeAtMicros,
        long fuseAtMicros,
        long calibratedPerItemMicros,
        String costBasis,
        String coverageBasis) {

    /** 当日计数（l0 按 l0_result / l1 按 l1_status / l2 按 l2_status；缺态计 0；比率无样本 null）。 */
    public record TodayView(
            long l0Pass,
            long l0Noise,
            long l0NearDup,
            long l1Done,
            long l1Pending,
            long l1Failed,
            long l2Extracted,
            long l2Deferred,
            Double l1RateIn30min,
            Double l2Coverage,
            Double noEventRatio) {

        /** 空计数（无数据日/未跑过）。 */
        public static TodayView empty() {
            return new TodayView(0, 0, 0, 0, 0, 0, 0, 0, null, null, null);
        }
    }

    /** 最近批窗口（status 端点形态；ISO 文本）。 */
    public record LastTickView(String startedAt, String finishedAt, String detail) {

        static LastTickView of(LastTick lastTick) {
            return lastTick == null
                    ? null
                    : new LastTickView(
                            lastTick.startedAt().toString(),
                            lastTick.finishedAt().toString(),
                            lastTick.detail());
        }
    }
}
