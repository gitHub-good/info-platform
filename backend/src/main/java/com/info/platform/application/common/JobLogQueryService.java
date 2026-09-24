package com.info.platform.application.common;

import com.info.platform.domain.common.JobExecutionLogRepository;
import com.info.platform.domain.common.JobExecutionStatus;
import com.info.platform.domain.common.JobLogFilter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Job 执行日志查询应用服务（T33 + M9 T61 页码模式）：接口层 {@code JobLogController} 经本服务读领域端口，隔离 Controller 与基础设施。
 *
 * <p>游标分页（对齐 §4.4 防深分页，{@code LIMIT 20}）：按 {@code id DESC}（newest-first）取页， {@code nextCursor} =
 * 当前页末条 id（最小 id），页未满即末页返回 null。{@code jobName} 非空时按 Job 名过滤。
 *
 * <p>页码模式（T61）：{@code jobName + status} 组合过滤 + count + LIMIT/OFFSET → {@link JobLogPagedView}；debug
 * 日志带 page/size/total/耗时（护栏观测数据源，ADR-0035 P95 阈值口径）。
 */
@Service
public class JobLogQueryService {

    private static final Logger log = LoggerFactory.getLogger(JobLogQueryService.class);

    /** 游标分页单页条数（§4.4 游标分页，LIMIT 20）。 */
    static final int PAGE_SIZE = 20;

    private final JobExecutionLogRepository repository;

    public JobLogQueryService(JobExecutionLogRepository repository) {
        this.repository = repository;
    }

    /**
     * 游标分页查询。
     *
     * @param jobName 过滤；null/空白表示不限 Job
     * @param cursor 上一页末条 id（最小 id）；null 表首页
     */
    public JobLogPage list(String jobName, Long cursor) {
        String name = (jobName == null || jobName.isBlank()) ? null : jobName.trim();
        List<JobLogView> items =
                repository.byJobNameCursor(name, cursor, PAGE_SIZE).stream()
                        .map(JobLogView::from)
                        .toList();
        Long nextCursor = items.size() == PAGE_SIZE ? items.get(items.size() - 1).id() : null;
        return new JobLogPage(items, nextCursor);
    }

    /**
     * 页码模式查询（M9 T61）：count + byFilterPage 同一 filter → {@link JobLogPagedView}。
     *
     * <p>越界页由 offset 语义天然返回空列表（零分支，ADR-0035）；page/size 由接口层 {@code PageQuery} 校验归一。
     *
     * @param jobName 过滤；null/空白表示不限 Job
     * @param status 状态过滤；null 不过滤（接口层已完成枚举校验）
     * @param page 页码（1 起，已校验）
     * @param size 页大小（1~50，已校验）
     */
    public JobLogPagedView listPaged(
            String jobName, JobExecutionStatus status, int page, int size) {
        long startedAt = System.currentTimeMillis();
        String name = (jobName == null || jobName.isBlank()) ? null : jobName.trim();
        JobLogFilter filter = new JobLogFilter(name, status);
        long total = repository.countByFilter(filter);
        List<JobLogView> items =
                repository.byFilterPage(filter, page, size).stream().map(JobLogView::from).toList();
        log.debug(
                "Job 日志页码查询 jobName={} status={} page={} size={} total={} 耗时{}ms",
                name,
                status,
                page,
                size,
                total,
                System.currentTimeMillis() - startedAt);
        return new JobLogPagedView(items, total, page, size);
    }
}
