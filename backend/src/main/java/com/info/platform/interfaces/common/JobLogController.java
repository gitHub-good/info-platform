package com.info.platform.interfaces.common;

import com.info.platform.application.common.JobLogQueryService;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.JobExecutionStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Job 执行日志接口（T33 + M9 T61 页码模式与状态筛选）。
 *
 * <p>受 JWT 保护（{@code /api/v1/job-logs} 不在 {@code JwtAuthFilter} 白名单，过滤器校验 Bearer 令牌后才放行）。 Job
 * 日志为全局运维数据，不按用户隔离；接口层仅参数校验 + 编排，查询逻辑在 {@link JobLogQueryService}。
 *
 * <h2>端点（同端点双模式，ADR-0035）</h2>
 *
 * <ul>
 *   <li>{@code GET /api/v1/job-logs?jobName=&cursor=} —— 游标分页（{@code id < cursor LIMIT 20}，§4.4
 *       防深分页）， {@code page} 参数缺席时本路径字节级不变。
 *   <li>{@code GET /api/v1/job-logs?jobName=&status=&page=&size=} —— 页码模式，返回 {@code {items[],
 *       total, page, size}}（列表字段与游标模式逐字段一致）。{@code status} 合法值 STARTED/SUCCESS/FAILED，非法值 400/2001；
 *       参数校验经 {@link PageQuery} 共用件。
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
     * Job 执行日志（双模式分派：page 出现即页码模式，缺席走既有游标路径）。
     *
     * @param jobName 过滤；空表示不限 Job
     * @param cursor 上一页末条 id；null 表示首页（游标模式）
     * @param page 页码；出现即页码模式
     * @param size 页大小（1~50，缺省 20）
     * @param status 状态过滤；空表示不过滤，非法值 400
     * @return 游标模式 200 + {items[], nextCursor}；页码模式 200 + {items[], total, page, size}
     */
    @GetMapping
    public Result<?> list(
            @RequestParam(required = false) String jobName,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String status) {
        boolean statusPresent = status != null && !status.isBlank();
        PageQuery.requirePageParam(page, "status", statusPresent);
        PageQuery pageQuery = PageQuery.resolve(page, size, cursor);
        if (pageQuery == null) {
            return Result.ok(jobLogQueryService.list(jobName, cursor));
        }
        return Result.ok(
                jobLogQueryService.listPaged(
                        jobName, parseStatus(status), pageQuery.page(), pageQuery.size()));
    }

    /**
     * status 解析：blank = 不过滤；非法值显式转 {@link BusinessException}（400/2001「未知 status: xxx」），
     * 不依赖全局处理器兜底（§4.2）。
     */
    private static JobExecutionStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return JobExecutionStatus.fromName(status.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "未知 status: " + status.trim());
        }
    }
}
