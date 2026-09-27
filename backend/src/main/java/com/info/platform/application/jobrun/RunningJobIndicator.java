package com.info.platform.application.jobrun;

/**
 * 任务运行态查询端口（应用层，M22 T190，ADR-0061 裁决 3 互斥层②）：只读暴露「某 jobKey 是否运行中」，供增量重评 tick 在入口让路 （{@code
 * isRunning(FACTOR_SNAPSHOT) || isRunning(MARKET_TOP_JOB)} → 本轮整体 DEFERRED，事件留待下轮）。
 *
 * <p>依赖倒置（仓储同款）：接口落在应用层，实现由基础设施 {@code JobExecutor} 承担（每 jobKey CAS 守卫的真实态）—— M20/M21 两个重 Job
 * 类零改动即获得被查询面。
 */
public interface RunningJobIndicator {

    /** 该任务是否运行中（JobExecutor 同 jobKey CAS 守卫位；未触发过的键恒 false）。 */
    boolean isRunning(String jobKey);
}
