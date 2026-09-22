package com.info.platform.application.jobrun;

import com.info.platform.application.common.JobLogView;
import java.util.List;
import java.util.Map;

/**
 * 任务执行中心端口（T37，方案 §4.4.3 jobs 组，REQ 故事 4）。
 *
 * <p>端口在应用层、实现在基础设施层（{@code infrastructure.jobrun.JobCenterFacadeImpl}）：实现需要 JobExecutor 运行守卫 /
 * JobScheduler 下次执行推算 / ConfigCenter 快照等基础设施件，应用层直依会与既有 infrastructure→application 依赖成环（同 {@code
 * LlmConfigFacade}/{@code DataSourceConfigFacade} 先例）。
 *
 * <p>语义：读返回 5 任务总览（调度状态/上次执行/下次执行/运行中）；写为「当前文档合并部分字段 → 校验 → 整体落库」 （expectedUpdatedAt 并发防呆
 * 30065），启停立即生效（取消/注册），间隔与 cron 自下一调度周期生效（ADR-0017）。 手动触发异步受理（返回 executionId 即 {@code
 * job_execution_log.id}，留痕可查）。
 */
public interface JobCenterFacade {

    /** 全部任务总览（GET /api/v1/jobs）。 */
    JobsView view();

    /**
     * 任务详情（含最近 5 次执行，自 job_execution_log）。
     *
     * @throws com.info.platform.domain.common.BusinessException 30062 任务不存在
     */
    JobDetailView detail(String jobKey);

    /**
     * 更新任务调度（PATCH 语义：仅非 null 字段合并进当前文档）。
     *
     * @throws com.info.platform.domain.common.BusinessException 30062 任务不存在；2001 校验失败（含间隔型/ cron
     *     型任务字段不匹配）；30065 并发冲突
     */
    JobView update(String jobKey, JobConfigUpdate update);

    /**
     * 手动触发（异步受理即返；运行中再次触发 30063 拒绝——防重入红线，SQLite 单写者）。
     *
     * @throws com.info.platform.domain.common.BusinessException 30062 任务不存在；30063 任务正在运行
     */
    TriggerResult trigger(String jobKey);

    /** GET /api/v1/jobs 响应。 */
    record JobsView(List<JobView> jobs) {}

    /** 单任务总览行（GET /api/v1/jobs 每项）。 */
    record JobView(
            String jobKey,
            String jobName,
            String name,
            String description,
            String scheduleType,
            Long intervalMillis,
            String cron,
            String userIds,
            boolean enabled,
            boolean running,
            LastExecutionView lastExecution,
            String nextExecutionTime,
            String updatedAt,
            Map<String, String> effectiveModes) {}

    /** 上次执行摘要（从未执行为 null，前端「从未执行」空态，PRD 场景 4.5）。 */
    record LastExecutionView(
            String status, String startTime, String endTime, Long durationMillis) {}

    /** 任务详情（GET /api/v1/jobs/{jobKey}：总览行 + 最近 5 次执行）。 */
    record JobDetailView(JobView job, List<JobLogView> recentExecutions) {}

    /** 单任务部分更新（null 字段 = 不修改；scheduleType 固定不可改，请求不携带）。 */
    record JobConfigUpdate(
            Boolean enabled,
            Long intervalMillis,
            String cron,
            String userIds,
            String expectedUpdatedAt) {}

    /**
     * 手动触发受理结果（POST /api/v1/jobs/{jobKey}/run → 202）。
     *
     * @param executionId job_execution_log 落痕 id（前端轮询任务列表或 Job 日志看状态流转）
     * @param status 受理态（STARTED）
     */
    record TriggerResult(long executionId, String status) {}
}
