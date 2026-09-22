package com.info.platform.infrastructure.common;

import com.info.platform.domain.common.JobExecutionLog;
import com.info.platform.domain.common.JobExecutionLogRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job 执行记录器（基础设施层横切，T33）。
 *
 * <p>提供 {@code start / success / failed} 三步，驱动 {@link JobExecutionLog} 的 STARTED→（SUCCESS|FAILED）
 * 生命周期落库。由 JobExecutor（T37 调度集中化后，定时与手动触发的统一执行通道）显式调用；各 Job 也可显式 调本类补充 processed/error 计数（增强，非必需）。
 *
 * <h2>容错：记录失败不阻断 Job</h2>
 *
 * <p>所有方法对自身持久化异常 try-catch 记 ERROR 后吞掉——Job 业务逻辑正常执行不应被留痕链路拖垮。 {@code start} 持久化失败时返回 {@code
 * null}，后续 {@code success/failed} 见 null 直接 no-op。
 *
 * <h2>时间</h2>
 *
 * <p>注入 {@link Clock}（生产 {@code Clock#systemUTC()}，单测可注入 {@code Clock#fixed} 固定时间，对齐 04 测试规范
 * 「时间/随机数要 mock 或固定种子」）。duration 由 {@link JobExecutionLog#markSuccess} 按 (endTime - startTime) 计算。
 */
@Component
public class JobExecutionRecorder {

    private static final Logger log = LoggerFactory.getLogger(JobExecutionRecorder.class);

    private final JobExecutionLogRepository repository;
    private final Clock clock;

    public JobExecutionRecorder(JobExecutionLogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 创建 STARTED 记录并落库（proceed 前调）。
     *
     * @return 落库后的记录（含回填 id）；持久化失败返回 null（后续 success/failed no-op）
     */
    public JobExecutionLog start(String jobName) {
        try {
            Instant now = clock.instant();
            return repository.save(JobExecutionLog.create(jobName, now));
        } catch (Exception e) {
            log.error("Job 执行留痕 start 失败 jobName={}: {}", jobName, e.toString(), e);
            return null;
        }
    }

    /**
     * 翻转为 SUCCESS 并落库（proceed 正常返回后调）。
     *
     * @param logEntry {@link #start} 返回的记录；null 时 no-op
     * @param processedCount 处理条数（AOP 首期传 0）
     * @param errorCount 错误条数（AOP 首期传 0）
     */
    public void success(JobExecutionLog logEntry, int processedCount, int errorCount) {
        if (logEntry == null) {
            return;
        }
        try {
            logEntry.markSuccess(clock.instant(), processedCount, errorCount);
            repository.save(logEntry);
        } catch (Exception e) {
            log.error("Job 执行留痕 success 失败 jobName={}: {}", logEntry.getJobName(), e.toString(), e);
        }
    }

    /**
     * 翻转为 FAILED 并落库（proceed 抛异常时调）。
     *
     * @param logEntry {@link #start} 返回的记录；null 时 no-op
     * @param errorMessage 异常摘要
     * @param processedCount 处理条数（AOP 首期传 0）
     * @param errorCount 错误条数（AOP 首期传 0）
     */
    public void failed(
            JobExecutionLog logEntry, String errorMessage, int processedCount, int errorCount) {
        if (logEntry == null) {
            return;
        }
        try {
            logEntry.markFailed(clock.instant(), errorMessage, processedCount, errorCount);
            repository.save(logEntry);
        } catch (Exception e) {
            log.error("Job 执行留痕 failed 失败 jobName={}: {}", logEntry.getJobName(), e.toString(), e);
        }
    }
}
