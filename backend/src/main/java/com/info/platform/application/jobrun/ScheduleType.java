package com.info.platform.application.jobrun;

/**
 * 平台任务的调度类型（T37，方案 §4.1 键空间表）。
 *
 * <p>各 Job 的调度类型固定（收编时对照 §4.1 对照表声明，页面不可切换）：4 个轮询型 Job 为 FIXED_DELAY， 每日推荐盘前预热为 CRON。
 */
public enum ScheduleType {
    /** 固定间隔轮询（上次执行<b>结束</b>后间隔 N 毫秒再执行，对齐 @Scheduled fixedDelay 语义）。 */
    FIXED_DELAY,

    /** cron 表达式调度（Spring 6 段 cron，如 {@code 0 0 9 * * ?}）。 */
    CRON
}
