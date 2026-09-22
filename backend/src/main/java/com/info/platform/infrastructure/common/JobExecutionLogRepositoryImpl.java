package com.info.platform.infrastructure.common;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.info.platform.domain.common.JobExecutionLog;
import com.info.platform.domain.common.JobExecutionLogRepository;
import com.info.platform.domain.common.JobExecutionStatus;
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
    public List<JobExecutionLog> findRecent(int limit) {
        List<JobExecutionLogPO> pos =
                mapper.selectList(
                        new LambdaQueryWrapper<JobExecutionLogPO>()
                                .orderByDesc(JobExecutionLogPO::getId)
                                .last("LIMIT " + limit));
        return toEntities(pos);
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
