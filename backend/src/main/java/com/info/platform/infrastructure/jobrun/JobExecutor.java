package com.info.platform.infrastructure.jobrun;

import com.info.platform.application.jobrun.JobRunStats;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.JobExecutionLog;
import com.info.platform.infrastructure.common.JobExecutionRecorder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 任务执行器（T37，基础设施层，方案 §4.5）：定时触发与手动触发<b>共用</b>的唯一执行通道。
 *
 * <h2>防重入（PRD 场景 4.4 Must 红线）</h2>
 *
 * 每 jobKey 一个 {@link AtomicBoolean} CAS 运行守卫：同任务运行中，手动再触发抛 409/30063 （{@link
 * #triggerNow}），定时触发跳过本轮记 DEBUG（{@link #runScheduled}）；守卫在 finally 释放。 跨任务并发维持现状（方案 §4.5：SQLite 靠
 * WAL + busy_timeout 排队，个人量级无锁风险）。
 *
 * <h2>留痕（替代退役的 JobExecutionAspect，字段语义不变）</h2>
 *
 * 执行前显式调 {@link JobExecutionRecorder#start} 记 STARTED，正常结束 {@code success(0,0)}、抛异常 {@code
 * failed(摘要,0,0)}（processed/error 计数与 AOP 时代一致记 0）；jobName 取 {@link ManagedJob#jobName()}（默认实现 =
 * 类简单名，与 AOP 派生规则一致，历史留痕可续读）。
 *
 * <h2>手动触发受理</h2>
 *
 * 同步落 STARTED 留痕取得 executionId，业务执行异步提交虚拟线程执行器即返（202 受理，方案库 12 裁剪）； 留痕写入失败时不受理（返回
 * 50000），守卫即时释放——无痕运行违背「触发→效果→日志」三处闭环（PRD 场景 4.2/4.3）。
 */
@Component
public class JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    private final JobExecutionRecorder recorder;
    private final Executor manualTriggerExecutor;
    private final Map<String, AtomicBoolean> runningGuards = new ConcurrentHashMap<>();

    /** 生产构造：手动触发走虚拟线程（每任务一线程，量级小；daemon 虚拟线程不阻退出）。 */
    @Autowired
    public JobExecutor(JobExecutionRecorder recorder) {
        this(recorder, Executors.newVirtualThreadPerTaskExecutor());
    }

    /** 测试构造：注入同步/受控执行器以确定性验证受理与防重入。 */
    JobExecutor(JobExecutionRecorder recorder, Executor manualTriggerExecutor) {
        this.recorder = recorder;
        this.manualTriggerExecutor = manualTriggerExecutor;
    }

    /** 该任务是否运行中（任务中心总览 {@code running} 字段来源）。 */
    public boolean isRunning(String jobKey) {
        return guard(jobKey).get();
    }

    /**
     * 手动触发（异步受理）：CAS 抢守卫 → 同步落 STARTED → 虚拟线程执行 → 即返受理结果。
     *
     * @throws BusinessException 30063 任务正在运行（防重入）；50000 留痕写入失败（不受理）
     */
    public TriggerOutcome triggerNow(ManagedJob job) {
        AtomicBoolean guard = guard(job.jobKey());
        if (!guard.compareAndSet(false, true)) {
            throw new BusinessException(
                    ErrorCode.JOB_ALREADY_RUNNING, "任务正在运行: " + job.jobKey() + "，请等待本轮完成");
        }
        JobExecutionLog started = recorder.start(job.jobName());
        if (started == null || started.getId() == null) {
            guard.set(false);
            throw new BusinessException(ErrorCode.SERVER_ERROR, "任务留痕写入失败，触发未受理: " + job.jobKey());
        }
        long executionId = started.getId();
        manualTriggerExecutor.execute(() -> runGuarded(job, started, guard));
        log.info("手动触发任务受理 jobKey={} executionId={}", job.jobKey(), executionId);
        return new TriggerOutcome(executionId);
    }

    /** 定时触发入口（JobScheduler 回调）：运行中跳过本轮（记 DEBUG，不告警——同任务串行是设计语义）； 留痕失败不阻断执行（Recorder 内部容错契约）。 */
    public void runScheduled(ManagedJob job) {
        AtomicBoolean guard = guard(job.jobKey());
        if (!guard.compareAndSet(false, true)) {
            log.debug("任务上一轮仍在执行，跳过本轮定时触发 jobKey={}", job.jobKey());
            return;
        }
        runGuarded(job, recorder.start(job.jobName()), guard);
    }

    /**
     * 守卫持有期内的完整执行：start（可 null）→ run → success/failed → finally 释放守卫。
     *
     * <p>成功路径按可选接口 {@link JobRunStats} 读取轮次计数与明细（T71 / ADR-0036 §2）——不实现的 Job 恒记 (0,0,null)，
     * 字节级不变；FAILED 路径不读（异常摘要语义不变）。
     */
    private void runGuarded(ManagedJob job, JobExecutionLog started, AtomicBoolean guard) {
        try {
            job.run();
            if (started != null) {
                int processedCount = 0;
                String detail = null;
                if (job instanceof JobRunStats stats) {
                    processedCount = stats.lastProcessedCount();
                    detail = stats.lastRunDetail();
                }
                recorder.success(started, processedCount, 0, detail);
            }
        } catch (Throwable ex) {
            // 与退役 AOP 的 FAILED 语义一致：异常摘要入 errorMessage；定时链路无调用方，此处记 ERROR 不上抛
            log.error("任务执行失败 jobKey={}: {}", job.jobKey(), ex.toString(), ex);
            if (started != null) {
                recorder.failed(started, ex.toString(), 0, 0);
            }
        } finally {
            guard.set(false);
        }
    }

    private AtomicBoolean guard(String jobKey) {
        return runningGuards.computeIfAbsent(jobKey, key -> new AtomicBoolean(false));
    }

    /** 手动触发受理结果（executionId = job_execution_log 落痕 id，状态恒 STARTED）。 */
    record TriggerOutcome(long executionId) {}
}
