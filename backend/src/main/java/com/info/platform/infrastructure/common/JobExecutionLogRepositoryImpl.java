package com.info.platform.infrastructure.common;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.info.platform.domain.common.JobExecutionLog;
import com.info.platform.domain.common.JobExecutionLogRepository;
import com.info.platform.domain.common.JobExecutionStatus;
import com.info.platform.domain.common.JobLogFilter;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link JobExecutionLogRepository} 端口的 SQLite/MyBatis-Plus 实现（基础设施层）。
 *
 * <p>PO↔Entity 转换集中于此；时间戳存 ISO-8601 整秒文本（字典序即时间序）。游标分页按 {@code id DESC} （自增主键顺序即 created_at
 * 顺序），newest-first，对齐 {@code PushRepository#findByUserIdCursor} 防深分页模式。
 */
@Repository
public class JobExecutionLogRepositoryImpl implements JobExecutionLogRepository {

    private final JobExecutionLogMapper mapper;

    public JobExecutionLogRepositoryImpl(JobExecutionLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public JobExecutionLog save(JobExecutionLog log) {
        JobExecutionLogPO po = toPO(log);
        String now = Instant.now().toString();
        if (po.getId() == null) {
            po.setCreatedAt(now);
            po.setUpdatedAt(now);
            if (po.getStatus() == null) {
                po.setStatus(JobExecutionStatus.STARTED.persistentName());
            }
            if (po.getProcessedCount() == null) {
                po.setProcessedCount(0);
            }
            if (po.getErrorCount() == null) {
                po.setErrorCount(0);
            }
            mapper.insert(po);
            return toEntity(po);
        }
        po.setUpdatedAt(now);
        mapper.updateById(po);
        return toEntity(po);
    }

    @Override
    public List<JobExecutionLog> byJobNameCursor(String jobName, Long cursor, int limit) {
        LambdaQueryWrapper<JobExecutionLogPO> wrapper =
                new LambdaQueryWrapper<JobExecutionLogPO>()
                        .eq(
                                jobName != null && !jobName.isBlank(),
                                JobExecutionLogPO::getJobName,
                                jobName)
                        .lt(cursor != null, JobExecutionLogPO::getId, cursor)
                        .orderByDesc(JobExecutionLogPO::getId)
                        .last("LIMIT " + limit);
        return toEntities(mapper.selectList(wrapper));
    }

    @Override
    public List<JobExecutionLog> byFilterPage(JobLogFilter filter, int page, int size) {
        // 页码模式：同序（id DESC）同过滤；LIMIT/OFFSET 从简（ADR-0035 实测 12k 行毫秒级）
        LambdaQueryWrapper<JobExecutionLogPO> wrapper =
                filterWrapper(filter)
                        .orderByDesc(JobExecutionLogPO::getId)
                        .last("LIMIT " + size + " OFFSET " + (page - 1) * size);
        return toEntities(mapper.selectList(wrapper));
    }

    @Override
    public long countByFilter(JobLogFilter filter) {
        // 精确 COUNT，同一 WHERE（与 byFilterPage 同一 filterWrapper 组装，口径单点）
        return mapper.selectCount(filterWrapper(filter));
    }

    /**
     * 页码模式组合 WHERE 一处组装（jobName 等值 + status 等值持久化名），{@link #byFilterPage}/{@link #countByFilter}
     * 两用——保证页数据与计数同口径。status 不建索引（3 值低基数，全扫毫秒级，ADR-0035）。
     *
     * <p>status 用显式 if 而非条件式 {@code eq(condition, column, value)}：Java 实参急切求值， {@code
     * persistentName()} 在 status=null 时先于条件判断 NPE。
     */
    private static LambdaQueryWrapper<JobExecutionLogPO> filterWrapper(JobLogFilter filter) {
        LambdaQueryWrapper<JobExecutionLogPO> wrapper =
                new LambdaQueryWrapper<JobExecutionLogPO>()
                        .eq(
                                filter.jobName() != null && !filter.jobName().isBlank(),
                                JobExecutionLogPO::getJobName,
                                filter.jobName());
        if (filter.status() != null) {
            wrapper.eq(JobExecutionLogPO::getStatus, filter.status().persistentName());
        }
        return wrapper;
    }

    @Override
    public List<JobExecutionLog> findRecent(int limit) {
        List<JobExecutionLogPO> pos =
                mapper.selectList(
                        new LambdaQueryWrapper<JobExecutionLogPO>()
                                .orderByDesc(JobExecutionLogPO::getId)
                                .last("LIMIT " + limit));
        return toEntities(pos);
    }

    @Override
    public long countSince(Instant since) {
        // created_at 存 ISO-8601 整秒文本，字典序即时间序，范围扫描命中 idx_job_log_time（V15）
        return mapper.selectCount(
                new LambdaQueryWrapper<JobExecutionLogPO>()
                        .ge(JobExecutionLogPO::getCreatedAt, since.toString()));
    }

    @Override
    public long countFailedSince(Instant since) {
        return mapper.selectCount(
                new LambdaQueryWrapper<JobExecutionLogPO>()
                        .ge(JobExecutionLogPO::getCreatedAt, since.toString())
                        .eq(
                                JobExecutionLogPO::getStatus,
                                JobExecutionStatus.FAILED.persistentName()));
    }

    @Override
    public List<JobExecutionLog> findFailedSince(Instant since, int limit) {
        // newest-first（id DESC），仅失败行供概览提取涉及 jobName
        return toEntities(
                mapper.selectList(
                        new LambdaQueryWrapper<JobExecutionLogPO>()
                                .ge(JobExecutionLogPO::getCreatedAt, since.toString())
                                .eq(
                                        JobExecutionLogPO::getStatus,
                                        JobExecutionStatus.FAILED.persistentName())
                                .orderByDesc(JobExecutionLogPO::getId)
                                .last("LIMIT " + limit)));
    }

    private List<JobExecutionLog> toEntities(List<JobExecutionLogPO> pos) {
        if (pos == null || pos.isEmpty()) {
            return Collections.emptyList();
        }
        return pos.stream().map(JobExecutionLogRepositoryImpl::toEntity).toList();
    }

    private static JobExecutionLog toEntity(JobExecutionLogPO po) {
        return JobExecutionLog.reconstruct(
                po.getId(),
                po.getJobName(),
                po.getStartTime() == null ? null : Instant.parse(po.getStartTime()),
                po.getEndTime() == null ? null : Instant.parse(po.getEndTime()),
                po.getStatus() == null ? null : JobExecutionStatus.fromName(po.getStatus()),
                po.getDurationMillis() == null ? null : po.getDurationMillis().longValue(),
                po.getProcessedCount() == null ? 0 : po.getProcessedCount(),
                po.getErrorCount() == null ? 0 : po.getErrorCount(),
                po.getErrorMessage(),
                po.getCreatedAt() == null ? null : Instant.parse(po.getCreatedAt()),
                po.getUpdatedAt() == null ? null : Instant.parse(po.getUpdatedAt()));
    }

    private static JobExecutionLogPO toPO(JobExecutionLog log) {
        JobExecutionLogPO po = new JobExecutionLogPO();
        po.setId(log.getId());
        po.setJobName(log.getJobName());
        po.setStartTime(log.getStartTime() == null ? null : log.getStartTime().toString());
        po.setEndTime(log.getEndTime().map(Instant::toString).orElse(null));
        po.setStatus(log.getStatus() == null ? null : log.getStatus().persistentName());
        po.setDurationMillis(log.getDurationMillis().map(Long::intValue).orElse(null));
        po.setProcessedCount(log.getProcessedCount());
        po.setErrorCount(log.getErrorCount());
        po.setErrorMessage(log.getErrorMessage().orElse(null));
        po.setCreatedAt(log.getCreatedAt() == null ? null : log.getCreatedAt().toString());
        po.setUpdatedAt(log.getUpdatedAt() == null ? null : log.getUpdatedAt().toString());
        return po;
    }
}
