package com.info.platform.interfaces.common;

import com.info.platform.application.jobrun.JobCenterFacade;
import com.info.platform.application.jobrun.JobCenterFacade.JobConfigUpdate;
import com.info.platform.application.jobrun.JobCenterFacade.JobDetailView;
import com.info.platform.application.jobrun.JobCenterFacade.JobView;
import com.info.platform.application.jobrun.JobCenterFacade.JobsView;
import com.info.platform.application.jobrun.JobCenterFacade.TriggerResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务执行中心接口（T37，方案 §4.4.3 jobs 组，REQ 故事 4）。
 *
 * <p>受 JWT 保护（不在 {@code JwtAuthFilter} 白名单）。读：任务总览（调度状态/上次执行/下次执行/运行中，从未执行
 * lastExecution=null）与详情（最近 5 次执行）。写：PATCH 合并（enabled/intervalMillis/cron/userIds，启停立即、 间隔与 cron
 * 自下一调度周期生效，响应 effectiveModes 明示）；手动触发 POST run 异步受理 202 （{@code executionId} 即 {@code
 * job_execution_log.id}），运行中再触发 409/30063（防重入 Must 红线）。
 *
 * <h2>端点</h2>
 *
 * <ul>
 *   <li>{@code GET /api/v1/jobs} —— 全部任务总览
 *   <li>{@code GET /api/v1/jobs/{jobKey}} —— 任务详情（含最近 5 次执行）
 *   <li>{@code PATCH /api/v1/jobs/{jobKey}} —— 更新调度（expectedUpdatedAt 并发防呆 30065）
 *   <li>{@code POST /api/v1/jobs/{jobKey}/run} —— 手动触发（202；运行中 409/30063）
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobCenterFacade facade;

    public JobController(JobCenterFacade facade) {
        this.facade = facade;
    }

    /** 全部任务总览（5 任务注册表；从未执行 lastExecution=null，停用 nextExecutionTime=null）。 */
    @GetMapping
    public Result<JobsView> list() {
        return Result.ok(facade.view());
    }

    /** 任务详情（含最近 5 次执行，自 job_execution_log）。 */
    @GetMapping("/{jobKey}")
    public Result<JobDetailView> detail(@PathVariable String jobKey) {
        return Result.ok(facade.detail(jobKey));
    }

    /** 更新调度（启停立即生效；间隔/cron 自下一调度周期生效，effectiveModes 明示）。 */
    @PatchMapping("/{jobKey}")
    public Result<JobView> update(
            @PathVariable String jobKey, @RequestBody JobConfigUpdate update) {
        return Result.ok(facade.update(jobKey, update));
    }

    /** 手动触发（异步受理 202；同任务运行中再触发 409/30063 防重入）。 */
    @PostMapping("/{jobKey}/run")
    public ResponseEntity<Result<TriggerResult>> run(@PathVariable String jobKey) {
        return ResponseEntity.accepted().body(Result.ok(facade.trigger(jobKey)));
    }
}
