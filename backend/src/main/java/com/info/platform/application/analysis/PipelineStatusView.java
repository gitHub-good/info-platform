package com.info.platform.application.analysis;

import com.info.platform.application.analysis.NewsPipelineService.LastTick;

/**
 * 管道状态视图（M15 T121 基础版，方案 §4.8 {@code GET /api/v1/pipeline/status}）：当日各状态计数 + 最近批窗口 + 当日 L1 成本。
 *
 * <p><b>本批边界</b>：护栏面（level/budget/degradeAt/fuseAt/校准值/l1RateIn30min/l2 覆盖）随 T125
 * 扩展为完整版——本视图字段是其稳定子集， 前端契约只增不改。
 *
 * @param jobKey 任务键（NEWS_PIPELINE）
 * @param today 当日计数（Asia/Shanghai 日界）
 * @param lastTick 最近一轮批窗口（未跑过为 null）
 * @param todayCostMicros 当日 L1 归类成本（llm_call_log scene=5 SUCCESS 求和，微元）
 */
public record PipelineStatusView(
        String jobKey, TodayView today, LastTickView lastTick, long todayCostMicros) {

    /** 当日三态计数（l0 按 l0_result / l1 按 l1_status；缺态计 0）。 */
    public record TodayView(
            long l0Pass, long l0Noise, long l0NearDup, long l1Done, long l1Pending, long l1Failed) {

        /** 空计数（无数据日/未跑过）。 */
        public static TodayView empty() {
            return new TodayView(0, 0, 0, 0, 0, 0);
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
