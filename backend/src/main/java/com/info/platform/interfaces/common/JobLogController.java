package com.info.platform.interfaces.common;

import com.info.platform.application.common.JobLogPage;
import com.info.platform.application.common.JobLogQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Job 执行日志接口（T33）。
 *
 * <p>受 JWT 保护（{@code /api/v1/job-logs} 不在 {@code JwtAuthFilter} 白名单，过滤器校验 Bearer 令牌后才放行）。 Job
 * 日志为全局运维数据，不按用户隔离；接口层仅参数校验 + 编排，查询逻辑在 {@link JobLogQueryService}。
 *
 * <h2>端点</h2>
 *
 * <ul>
 *   <li>{@code GET /api/v1/job-logs?jobName=&cursor=} —— 游标分页（{@code id < cursor LIMIT 20}，§4.4
 *       防深分页）， 按 jobName 过滤、按 created_at 倒序；每条含
 *       jobName/startTime/endTime/status/durationMillis/processedCount/errorCount/errorMessage。
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/job-logs")
public class JobLogController {

    private final JobLogQueryService jobLogQueryService;

    public JobLogController(JobLogQueryService jobLogQueryService) {
        this.jobLogQueryService = jobLogQueryService;
    }

    /**
     * Job 执行日志游标分页。
     *
     * @param jobName 过滤；空表示不限 Job
     * @param cursor 上一页末条 id；null 表示首页
     */
    @GetMapping
    public Result<JobLogPage> list(
            @RequestParam(required = false) String jobName,
            @RequestParam(required = false) Long cursor) {
        return Result.ok(jobLogQueryService.list(jobName, cursor));
    }
}
