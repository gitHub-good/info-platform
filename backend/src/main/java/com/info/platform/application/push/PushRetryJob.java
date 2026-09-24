package com.info.platform.application.push;

import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import com.info.platform.domain.push.AnomalyRecord;
import com.info.platform.domain.push.AnomalyRepository;
import com.info.platform.domain.push.PushRecord;
import com.info.platform.domain.push.PushRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 推送补推 job（T15，对齐技术方案 §4.3 流程 3「断线靠 history 补拉」+ 重启恢复；T37 收编 {@link ManagedJob}）。
 *
 * <p>双扫描补全推送链路完整性——把实时推送（T14 PushService）遗留的两类「未投递」记录补推出去：
 *
 * <ol>
 *   <li><b>重启恢复</b>（{@link #recoverPendingAnomalies}）：扫 anomaly_event pushed=0（{@link
 *       AnomalyRepository#findPending}）。Spring {@code ApplicationEvent} 在内存不持久化，进程在「发事件 → @Async
 *       消费」之间崩溃即丢事件；这些「已入库未消费」的异动由本扫描重新喂回推送链路——复用 {@link PushService#processAnomaly} 走完整「查订阅→幂等写
 *       push_record→推送/待推→标记 pushed=1」全链路。
 *   <li><b>待推补推</b>（{@link #retryPendingRecords}）：扫 push_record status=0（{@link
 *       PushRepository#findPending}）——离线时留的待推记录。调 {@link PushService#retryPending}： 用户当前在线 → SSE
 *       推送（失败重试1次仍失败 status=2 告警）+ 翻 status=1；仍离线 → 跳过留 status=0 待下轮或 SSE 重连补拉。
 * </ol>
 *
 * <p><b>执行顺序</b>：先恢复未消费异动（可能产出新 status=0 待推记录），再扫待推——同轮内把新产出的在线用户记录一并补推， 单轮完成重启恢复。
 *
 * <p><b>幂等协调</b>（与实时推不重复，详见 {@link PushService#retryPending} / {@link PushService#processAnomaly}）：
 *
 * <ul>
 *   <li>重启恢复复用 {@code saveIfAbsent}：DB {@code UNIQUE(idempotency_key)} 兜底，与实时推同 key 即跳过。
 *   <li>待推补推不重新 {@code saveIfAbsent}（记录已存在），仅 {@code send + 状态翻转}；status 非 0 不入扫表，不重发。
 *   <li>anomaly.pushed 标记防 findPending 重复拾取；push_record.status 翻转防 findPending 重复补推。
 * </ul>
 *
 * <p><b>容错</b>：单条异常 try-catch 不阻断整轮（与 {@code AnomalyDetectionJob} 同策略）。调度（T37 集中化，
 * ADR-0017）：去 @Scheduled/条件注解后无条件装配，由 JobScheduler 按 {@code job.PUSH_RETRY} 运行时配置注册
 * （FIXED_DELAY，种子间隔默认 30s，页面可调可停用）；测试 profile 种子 {@code enabled=false} → 零注册，隔离语义 等价平移，逻辑由单测直调验证。
 *
 * <p><b>扫描有界（系统体检 20260924 P1-1 后端半段）</b>：待推扫描 {@code push.retry.scan-limit}（默认 100）LIMIT + {@code
 * push.retry.pending-retention-days}（默认 7 天）过期截止——前端未接 SSE 期间积压的 PENDING 无消费者，超保留期直接跳过不再扫，
 * 防 30s 轮询无界全量拉取与 PENDING 永久积压；空转轮（两扫描均空）零日志输出（有数据才 INFO）。
 */
@Component
public class PushRetryJob implements ManagedJob {

    private static final Logger log = LoggerFactory.getLogger(PushRetryJob.class);

    /** 单轮扫描上限兜底默认（scan-limit 配置非正时取此值）。 */
    static final int DEFAULT_SCAN_LIMIT = 100;

    private final PushService pushService;
    private final PushRepository pushRepository;
    private final AnomalyRepository anomalyRepository;
    private final int scanLimit;
    private final int pendingRetentionDays;

    public PushRetryJob(
            PushService pushService,
            PushRepository pushRepository,
            AnomalyRepository anomalyRepository,
            @Value("${push.retry.scan-limit:100}") int scanLimit,
            @Value("${push.retry.pending-retention-days:7}") int pendingRetentionDays) {
        this.pushService = pushService;
        this.pushRepository = pushRepository;
        this.anomalyRepository = anomalyRepository;
        this.scanLimit = scanLimit <= 0 ? DEFAULT_SCAN_LIMIT : scanLimit;
        this.pendingRetentionDays = pendingRetentionDays < 0 ? 7 : pendingRetentionDays;
    }

    @Override
    public String jobKey() {
        return "PUSH_RETRY";
    }

    @Override
    public String displayName() {
        return "推送补推";
    }

    @Override
    public String description() {
        return "扫描未消费异动与失败推送记录，补全推送链路";
    }

    @Override
    public ScheduleType scheduleType() {
        return ScheduleType.FIXED_DELAY;
    }

    /** 定时与手动触发共用入口（委托 {@link #retry}）。 */
    @Override
    public void run() {
        retry();
    }

    /**
     * 补推轮询（FIXED_DELAY 默认每 30s）。
     *
     * <p>先恢复未消费异动，再扫待推记录；两步各自独立 try-catch 单条异常，不阻断整轮。
     */
    public void retry() {
        recoverPendingAnomalies();
        retryPendingRecords();
    }

    /**
     * 重启恢复：扫 anomaly_event pushed=0 → 复用 {@link PushService#processAnomaly} 全链路补推（包内可见，便于单测）。
     *
     * <p>单条异常不阻断整轮：某条异动处理失败记 ERROR，跳过该条继续下一条（下轮 findPending 仍会拾取未标 pushed 的）。
     */
    void recoverPendingAnomalies() {
        List<AnomalyRecord> pending = anomalyRepository.findPending();
        if (pending.isEmpty()) {
            return;
        }
        log.info("补推：恢复未消费异动 {} 条", pending.size());
        for (AnomalyRecord record : pending) {
            try {
                pushService.processAnomaly(record);
            } catch (Exception e) {
                log.error("补推：异动恢复异常，跳过 anomalyId={}: {}", record.getId(), e.toString(), e);
            }
        }
    }

    /**
     * 待推补推：扫 push_record status=0 → {@link PushService#retryPending} 在线补推/离线跳过（包内可见，便于单测）。
     *
     * <p>扫描有界（P1-1 后端半段）：LIMIT {@code scanLimit} + 超保留期的 PENDING 跳过（created_at 截止）； 空转轮零日志。
     *
     * <p>单条异常不阻断整轮：某条补推失败记 ERROR，跳过该条继续下一条（status 仍 0，下轮再试）。
     */
    void retryPendingRecords() {
        Instant createdSince = Instant.now().minus(Duration.ofDays(pendingRetentionDays));
        List<PushRecord> pending = pushRepository.findPending(scanLimit, createdSince);
        if (pending.isEmpty()) {
            return;
        }
        log.info("补推：扫描待推记录 {} 条（上限 {}，保留期 {} 天）", pending.size(), scanLimit, pendingRetentionDays);
        for (PushRecord record : pending) {
            try {
                pushService.retryPending(record);
            } catch (Exception e) {
                log.error("补推：单条异常，跳过 pushRecordId={}: {}", record.getId(), e.toString(), e);
            }
        }
    }
}
