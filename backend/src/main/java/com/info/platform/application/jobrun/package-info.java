/**
 * 任务执行中心（T37 / ADR-0017 调度集中化）：ManagedJob 收编契约 + 注册表 + 域校验器 + 中心端口。
 *
 * <p>组件分工（方案 §4.5）：应用层定义 {@link com.info.platform.application.jobrun.ManagedJob} 契约与 {@link
 * com.info.platform.application.jobrun.JobRegistry} 注册表、job 域校验器与任务中心端口；基础设施层 {@code
 * infrastructure.jobrun} 落 JobScheduler（集中调度 + 配置热重注册）与 JobExecutor（CAS 防重入 +
 * 显式留痕）。定时与手动触发共用执行通道，天然防重入、同一张 {@code job_execution_log} 留痕。
 */
package com.info.platform.application.jobrun;
