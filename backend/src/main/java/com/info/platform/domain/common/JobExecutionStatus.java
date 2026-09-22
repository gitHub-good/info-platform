package com.info.platform.domain.common;

/**
 * Job 执行状态（job_execution_log.status 持久化为枚举名字文本 STARTED/SUCCESS/FAILED）。
 *
 * <p>领域层纯净枚举（仅 JDK），与持久化层 {@code TEXT} 互转。生命周期：
 *
 * <ul>
 *   <li>{@link #STARTED}：Job 方法被 AOP 切面拦截、proceed 前插入（start_time 已填，end_time/duration 留空）。
 *   <li>{@link #SUCCESS}：proceed 正常返回（Job 内部吞掉的异常不算失败，方法正常返回即成功）。
 *   <li>{@link #FAILED}：proceed 抛出未捕获异常。
 * </ul>
 *
 * <p>状态流转：STARTED →（SUCCESS | FAILED），单向终态，不回退。
 */
public enum JobExecutionStatus {
    /** 执行中（已插入起始记录，尚未结束）。 */
    STARTED,
    /** 执行成功（正常返回，无未捕获异常）。 */
    SUCCESS,
    /** 执行失败（抛出未捕获异常）。 */
    FAILED;

    /** 持久化用枚举名（与 DDL status TEXT 一致，跨语言读取不歧义）。 */
    public String persistentName() {
        return name();
    }

    /** 从持久化文本反查枚举；未知值兜底抛非法参数（防御脏数据）。 */
    public static JobExecutionStatus fromName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("jobExecutionStatus 为空");
        }
        for (JobExecutionStatus s : values()) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知 jobExecutionStatus: " + name);
    }
}
