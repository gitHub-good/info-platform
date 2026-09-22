package com.info.platform.application.common;

import com.info.platform.domain.common.JobExecutionLogRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Job 执行日志查询应用服务（T33）：接口层 {@code JobLogController} 经本服务读领域端口，隔离 Controller 与基础设施。
 *
 * <p>游标分页（对齐 §4.4 防深分页，{@code LIMIT 20}）：按 {@code id DESC}（newest-first）取页， {@code nextCursor} =
 * 当前页末条 id（最小 id），页未满即末页返回 null。{@code jobName} 非空时按 Job 名过滤。
 */
@Service
public class JobLogQueryService {

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
}
