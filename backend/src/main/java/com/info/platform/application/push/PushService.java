package com.info.platform.application.push;

import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.push.AnomalyDetectedEvent;
import com.info.platform.domain.push.AnomalyRecord;
import com.info.platform.domain.push.AnomalyRepository;
import com.info.platform.domain.push.NotificationEvent;
import com.info.platform.domain.push.PushRecord;
import com.info.platform.domain.push.PushRepository;
import com.info.platform.domain.push.PushType;
import com.info.platform.domain.push.SubscriptionResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 推送应用服务（对齐技术方案 §4.3 流程 3 + §4.4 幂等 + ADR-0006）。
 *
 * <p>双职责：
 *
 * <ol>
 *   <li><b>事件消费（异步）</b>：{@link #onAnomalyDetected} 以 {@code @Async @EventListener} 消费 {@link
 *       AnomalyDetectedEvent}（T13 异动检测发布）。编排：定位 anomaly_record 取 refId → {@link
 *       SubscriptionResolver} 查推送目标（M1=watchlist 隐含订阅）→ 对每个 userId 幂等写 push_record（{@code
 *       saveIfAbsent} 靠 {@code UNIQUE(idempotency_key)} 防重，已存在跳过不重推）→ {@link
 *       NotificationChannel#send} 推在线用户 → 成功 status=1+anomaly pushed=1；失败重试 1 次仍失败 status=2 + ERROR
 *       告警； 离线用户 push_record 留 status=0 待推，重连补拉。
 *   <li><b>连接与历史（同步）</b>：{@link #openStream} 注册 SSE 长连接 + 重连补拉待推记录；{@link #history} 游标分页历史推送。
 * </ol>
 *
 * <p>幂等键 = userId + pushType + refId（refId=anomaly_event.id）。@Async 不依赖请求上下文——推送目标 userId 来自
 * watchlist 解析，不经 {@code UserContext}（T17 ThreadLocal 异步不传递，本服务不取 UserContext，符合设计）。
 *
 * <p>测试：@Async/@EventListener 在单测直调 {@link #handleAnomaly}（绕过代理，同步执行），mock 全部外部依赖。
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    /** history 游标分页单页条数（§4.4 游标分页，LIMIT 20）。 */
    static final int HISTORY_PAGE_SIZE = 20;

    /** PENDING 待推保留期兜底默认（配置非正时取此值，对齐 PushRetryJob 同名配置语义）。 */
    static final int DEFAULT_PENDING_RETENTION_DAYS = 7;

    /** 异动推送的兜底文案（anomaly_record.detail 为空时用）。 */
    private static final String DEFAULT_ANOMALY_CONTENT = "异动触发";

    private final PushRepository pushRepository;
    private final AnomalyRepository anomalyRepository;
    private final SubscriptionResolver subscriptionResolver;
    private final NotificationChannel channel;
    private final SubjectRepository subjectRepository;
    private final Clock clock;
    private final int pendingRetentionDays;

    public PushService(
            PushRepository pushRepository,
            AnomalyRepository anomalyRepository,
            SubscriptionResolver subscriptionResolver,
            NotificationChannel channel,
            SubjectRepository subjectRepository,
            Clock clock,
            @Value("${push.retry.pending-retention-days:7}") int pendingRetentionDays) {
        this.pushRepository = pushRepository;
        this.anomalyRepository = anomalyRepository;
        this.subscriptionResolver = subscriptionResolver;
        this.channel = channel;
        this.subjectRepository = subjectRepository;
        this.clock = clock;
        this.pendingRetentionDays =
                pendingRetentionDays < 0 ? DEFAULT_PENDING_RETENTION_DAYS : pendingRetentionDays;
    }

    /**
     * 异步消费异动事件（{@code @Async} 走虚拟线程执行器，{@code @EventListener} 按 {@link AnomalyDetectedEvent} 类型匹配）。
     *
     * <p>异常兜底：处理过程中任何异常记 ERROR 不上抛（异步监听器异常会被 Spring 吞并记错，这里显式记上下文便于告警定位）。
     */
    @Async("pushAsyncExecutor")
    @EventListener
    public void onAnomalyDetected(AnomalyDetectedEvent event) {
        try {
            handleAnomaly(event);
        } catch (Exception e) {
            log.error(
                    "异动推送处理异常 subjectId={} type={} triggerTime={}: {}",
                    event.getSubjectId(),
                    event.getAnomalyType(),
                    event.getTriggerTime(),
                    e.toString(),
                    e);
        }
    }

    /**
     * 异动推送编排（包内可见，便于单测直调绕过 @Async 代理）。
     *
     * <p>步骤：定位 anomaly_record（取 id 作 refId）→ 委托 {@link #processAnomaly} 走「已 pushed 跳过 → 解析推送目标 →
     * 逐用户幂等防重推 → 标记 pushed=1」全链路。 定位失败（事件与记录不一致，理论不应发生）记 WARN 跳过。
     */
    void handleAnomaly(AnomalyDetectedEvent event) {
        Optional<AnomalyRecord> recordOpt = locateAnomalyRecord(event);
        if (recordOpt.isEmpty()) {
            log.warn(
                    "异动推送：未定位到 anomaly_record，跳过 subjectId={} type={} triggerTime={}",
                    event.getSubjectId(),
                    event.getAnomalyType(),
                    event.getTriggerTime());
            return;
        }
        processAnomaly(recordOpt.get());
    }

    /**
     * 处理已定位的异动记录（包内可见）：已 pushed 跳过 → 解析推送目标 → 逐用户幂等防重推 → 标记 pushed=1。
     *
     * <p>双入口共用本方法，保证编排一致、幂等协调统一：
     *
     * <ul>
     *   <li><b>实时消费</b>：{@link #handleAnomaly} 据 {@link AnomalyDetectedEvent} 定位记录后调（T14）。
     *   <li><b>重启恢复</b>（T15）：补推 job 经 {@link AnomalyRepository#findPending} 拾取 pushed=0 记录直调——
     *       进程崩溃在发事件与消费之间时，Spring {@code ApplicationEvent} 在内存不持久化、重启即丢； findPending
     *       把这些「已入库未消费」的异动重新喂回推送链路。
     * </ul>
     *
     * <p>无论在线推送 / 离线待推 / 无目标，事件已处理即标记 pushed=1，防 findPending 重复拾取（对齐 §4.3 流程 3）。
     */
    void processAnomaly(AnomalyRecord record) {
        if (record.isPushed()) {
            log.debug("异动推送：记录已 pushed，跳过 anomalyId={}", record.getId());
            return;
        }
        String refId = String.valueOf(record.getId());
        String content = record.getDetail().orElse(DEFAULT_ANOMALY_CONTENT);

        Set<Long> targets = subscriptionResolver.resolveAnomalyTargets(record.getSubjectId());
        log.info(
                "异动推送: anomalyId={} subjectId={} 目标用户 {} 个",
                record.getId(),
                record.getSubjectId(),
                targets.size());
        for (long userId : targets) {
            try {
                pushAnomalyToOne(userId, record.getSubjectId(), refId, content);
            } catch (Exception e) {
                // 单用户推送异常不阻断其他用户（与异动检测同策略：热路径单点失败不拖垮整批）
                log.error(
                        "单用户异动推送异常 userId={} anomalyId={}: {}",
                        userId,
                        record.getId(),
                        e.toString(),
                        e);
            }
        }
        // 事件已处理（在线推送 / 离线待推 / 无目标），标记 pushed=1 防 findPending 重复拾取
        record.markPushed();
        anomalyRepository.save(record);
        log.info("异动推送完成 anomalyId={} subjectId={}", record.getId(), record.getSubjectId());
    }

    /**
     * 单用户异动推送：幂等写 push_record → 区分在线/离线 → 在线推送（失败重试1次）→ 状态翻转。
     *
     * <p>离线用户（无 SSE 连接）：push_record 已以 PENDING 落库，留 status=0 待 SSE 重连补拉（不翻转状态、不重试）。 在线用户：推送失败重试 1
     * 次，仍失败置 status=2 + ERROR 告警（对齐 §4.3 流程 3）。
     */
    private void pushAnomalyToOne(long userId, Long subjectId, String refId, String content) {
        PushRecord record = PushRecord.create(userId, subjectId, PushType.ANOMALY, refId, content);
        Optional<PushRecord> savedOpt = pushRepository.saveIfAbsent(record);
        if (savedOpt.isEmpty()) {
            // 幂等防重：同 key 已存在（事件重投/@Async 重发），跳过不重推
            log.debug("异动推送防重跳过: userId={} idempotencyKey={}", userId, record.getIdempotencyKey());
            return;
        }
        PushRecord saved = savedOpt.get();
        if (!channel.isOnline(userId)) {
            // 离线：push_record 已 PENDING 落库，留待 SSE 重连补拉（不 update、不重试）
            log.info("离线用户留待推 userId={} pushRecordId={} refId={}", userId, saved.getId(), refId);
            return;
        }
        NotificationEvent payload =
                NotificationEvent.of(
                        PushType.ANOMALY,
                        subjectId,
                        resolveSubjectCode(subjectId, null),
                        refId,
                        content);
        deliver(saved, userId, payload);
    }

    /** 在线推送交付（含重试1次与状态翻转）。 */
    private void deliver(PushRecord saved, long userId, NotificationEvent payload) {
        boolean ok = channel.send(userId, payload, saved.getId());
        if (!ok) {
            // 失败重试 1 次（对齐 §4.3 流程 3「推送失败重试 1 次」）
            saved.recordRetry();
            ok = channel.send(userId, payload, saved.getId());
        }
        if (ok) {
            saved.markPushed(clock.instant());
            log.info(
                    "推送成功 userId={} pushRecordId={} retryCount={}",
                    userId,
                    saved.getId(),
                    saved.getRetryCount());
        } else {
            saved.markFailed();
            log.error(
                    "推送失败（重试1次仍失败）userId={} pushRecordId={} type={}",
                    userId,
                    saved.getId(),
                    saved.getPushType());
        }
        pushRepository.update(saved);
    }

    /**
     * 补推单条待推记录（T15 补推 job 调用，包内可见）。
     *
     * <p>面向 push_record status=0（离线时留的待推）：用户当前在线 → 复用 {@link #deliver} 走 SSE 推送（失败重试1次仍失败 status=2
     * 告警）+ 翻 status=1；仍离线 → 跳过，留 status=0 待下轮或 SSE 重连补拉。
     *
     * <p>幂等协调（与实时推不重复）：本方法<b>不重新 {@code saveIfAbsent}</b>——记录已存在，仅 {@code send + 状态翻转}。 实时推侧 {@code
     * saveIfAbsent} 受 DB {@code UNIQUE(idempotency_key)} 约束，同 key 已存在即跳过不重发； 补推侧只翻现有记录状态， status 非
     * 0 不入扫表（{@code findPending} 仅取 status=0），故不会重发已推记录。两条路径经 idempotency_key + status 双重隔离，无重复推送。
     */
    void retryPending(PushRecord record) {
        if (!channel.isOnline(record.getUserId())) {
            log.debug(
                    "补推：用户离线跳过，留下次 userId={} pushRecordId={}", record.getUserId(), record.getId());
            return;
        }
        NotificationEvent payload =
                buildEvent(
                        record.getPushType(),
                        record.getSubjectId().orElse(null),
                        record.getRefId().orElse(null),
                        record.getContent());
        deliver(record, record.getUserId(), payload);
    }

    /**
     * 开启 SSE 长连接并补拉待推记录（接口层 stream 端点调用）。
     *
     * @param userId 当前认证用户（JwtAuthFilter 写入 UserContext，接口层传入）
     * @param lastEventId 客户端 {@code Last-Event-ID} 头（重连补拉游标），null 表示首次连接
     */
    public SseEmitter openStream(long userId, Long lastEventId) {
        SseEmitter emitter = channel.open(userId);
        flushPending(userId, lastEventId);
        return emitter;
    }

    /** 重连补拉：取该用户待推记录（status=0，保留期内），按 Last-Event-ID 之后补发并翻转 status=1。 */
    private void flushPending(long userId, Long lastEventId) {
        Instant createdSince = clock.instant().minus(Duration.ofDays(pendingRetentionDays));
        List<PushRecord> pending = pushRepository.findPendingByUser(userId, createdSince);
        if (pending.isEmpty()) {
            return;
        }
        log.info("SSE 重连补拉: userId={} 待推 {} 条 lastEventId={}", userId, pending.size(), lastEventId);
        for (PushRecord pr : pending) {
            if (lastEventId != null && pr.getId() != null && pr.getId() <= lastEventId) {
                // 客户端已见该 id（Last-Event-ID 及之前），跳过避免重复
                continue;
            }
            NotificationEvent payload =
                    buildEvent(
                            pr.getPushType(),
                            pr.getSubjectId().orElse(null),
                            pr.getRefId().orElse(null),
                            pr.getContent());
            boolean ok = channel.send(userId, payload, pr.getId());
            if (!ok) {
                // 连接已失效，停止补拉（剩余留 status=0 下次重连再补）
                log.warn("SSE 补拉中断（连接失效）userId={} 剩余待推未补", userId);
                return;
            }
            pr.markPushed(clock.instant());
            pushRepository.update(pr);
        }
    }

    /** history 游标分页（接口层 history 端点调用）。 */
    public NotificationHistory history(long userId, Long cursor, PushType type) {
        List<PushRecord> records =
                pushRepository.findByUserIdCursor(userId, cursor, type, HISTORY_PAGE_SIZE);
        List<NotificationView> items = projectViews(records);
        Long nextCursor =
                items.size() == HISTORY_PAGE_SIZE ? items.get(items.size() - 1).id() : null;
        return new NotificationHistory(items, nextCursor);
    }

    /**
     * 最近 N 条 history（P1-1 前端通知面板兜底，单次拉取无游标）。
     *
     * <p>返回按 id 升序的最近 {@code limit} 条；{@code nextCursor} 恒 null（单次语义，不续页）。
     */
    public NotificationHistory latestHistory(long userId, int limit, PushType type) {
        List<PushRecord> records = pushRepository.findLatestByUser(userId, type, limit);
        return new NotificationHistory(projectViews(records), null);
    }

    /** 批量投影 PushRecord → NotificationView（subjectCode 按页回查，同页同标的不重复查）。 */
    private List<NotificationView> projectViews(List<PushRecord> records) {
        Map<Long, String> codeCache = new HashMap<>();
        return records.stream()
                .map(
                        r ->
                                NotificationView.from(
                                        r,
                                        resolveSubjectCode(
                                                r.getSubjectId().orElse(null), codeCache)))
                .toList();
    }

    /** 构造推送载荷（回查标的代码，P1-1 前端按 subjectCode 跳标的详情）。 */
    private NotificationEvent buildEvent(
            PushType type, Long subjectId, String refId, String content) {
        return NotificationEvent.of(
                type, subjectId, resolveSubjectCode(subjectId, null), refId, content);
    }

    /** subjectId → 内部统一代码；标的不存在/无 subjectId 返回 null（不阻断推送）。 */
    private String resolveSubjectCode(Long subjectId, Map<Long, String> codeCache) {
        if (subjectId == null) {
            return null;
        }
        if (codeCache != null && codeCache.containsKey(subjectId)) {
            return codeCache.get(subjectId);
        }
        String code =
                subjectRepository
                        .findById(subjectId)
                        .map(s -> s.getSubjectCode().value())
                        .orElse(null);
        if (code == null) {
            // 标的已删等边缘：仍推送（content 可读），仅不可跳转
            log.debug("推送回查标的不存在 subjectId={}", subjectId);
        }
        if (codeCache != null) {
            codeCache.put(subjectId, code);
        }
        return code;
    }

    /** 据 AnomalyDetectedEvent 定位 anomaly_record：同 subjectId 下按 (type, triggerTime) 唯一匹配。 */
    private Optional<AnomalyRecord> locateAnomalyRecord(AnomalyDetectedEvent event) {
        List<AnomalyRecord> candidates = anomalyRepository.findBySubjectId(event.getSubjectId());
        return candidates.stream()
                .filter(r -> r.getAnomalyType() == event.getAnomalyType())
                .filter(r -> event.getTriggerTime().equals(r.getTriggerTime()))
                .findFirst();
    }
}
