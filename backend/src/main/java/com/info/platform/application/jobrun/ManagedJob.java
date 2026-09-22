package com.info.platform.application.jobrun;

/**
 * 受管任务接口（T37 / ADR-0017 冲突解法 2，方案 §4.5）。
 *
 * <p>平台任务收编统一契约：5 个既有 Job 去 {@code @Scheduled}/{@code @ConditionalOnProperty} 后实现本接口并注册为 <b>无条件
 * bean</b>（调度与执行解耦——Job bean 恒在容器中，停用只取消调度注册，手动触发不受装配限制）。 定时触发与手动触发走同一执行通道（{@code JobExecutor}），共用
 * CAS 运行守卫与 {@code job_execution_log} 留痕链路。
 *
 * <p>实现要求：{@link #run()} 为整轮执行入口，内部自行容错（轮询型 Job 沿用既有「整轮异常不上抛」惯例）； 留痕（STARTED→SUCCESS/FAILED）由
 * {@code JobExecutor} 显式驱动，实现不感知。
 */
public interface ManagedJob {

    /** 任务键（runtime_config {@code job.{JOB_KEY}} 的键后缀，如 {@code POLICY_FETCH}，方案 §4.1 对照表）。 */
    String jobKey();

    /**
     * 留痕名（对齐 {@code job_execution_log.job_name} 与 Job 日志页过滤口径）。
     *
     * <p>默认取实现类简单名（如 {@code PolicyFetchJob}），与退役 AOP 切面的 jobName 派生规则一致——历史留痕可续读。
     */
    default String jobName() {
        return getClass().getSimpleName();
    }

    /** 展示名（任务中心页面「任务」列，如「政策抓取」）。 */
    String displayName();

    /** 人读说明（任务中心页面副文案）。 */
    String description();

    /** 调度类型（固定，各 Job 声明后不可切换，方案 §4.1 对照表）。 */
    ScheduleType scheduleType();

    /** 执行一轮任务（定时与手动触发共用入口）。 */
    void run();
}
