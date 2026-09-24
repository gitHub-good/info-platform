package com.info.platform.infrastructure.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.push.NotificationChannel;
import com.info.platform.domain.push.NotificationEvent;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 在线用户 SSE 连接注册表（基础设施层，实现应用层 {@link NotificationChannel} 端口）。
 *
 * <p>以 {@link ConcurrentHashMap}（{@code userId → SseEmitter}）管理在线长连接，并发安全。一个用户同时只保留最新一条连接（重复 {@link
 * #open} 先优雅关闭旧连接）。
 *
 * <h2>事件格式</h2>
 *
 * {@code event:<type>\nid:<recordId>\ndata:<json>\n\n}（对齐 §4.1.3 SSE 事件类型 + §4.3 流程 3）： {@code id}
 * 为 push_record.id，供客户端 {@code Last-Event-ID} 重连补拉； {@code data} 为 {@link NotificationEvent} 经
 * Jackson 序列化的 JSON。
 *
 * <h2>心跳帧（系统体检 20260924 O8）</h2>
 *
 * 空闲连接无任何字节往来时，中间层（代理/网关）可能静默掐断长连接——客户端在超时前无感知，{@code isOnline} 误判在线。 {@code
 * push.sse.heartbeat-interval-millis}（默认 30s，非正关）周期发送 SSE comment 帧（{@code
 * :heartbeat\n\n}）：对中间层是活跃流量， 对前端 EventSource 不触发 {@code onmessage}（comment
 * 帧规范上被忽略），不污染事件流。发送失败按连接失效注销。
 *
 * <h2>生命周期</h2>
 *
 * {@link #open} 注册回调：onCompletion/onTimeout/onError 时从表注销（防泄漏）。 {@link #send} 在用户离线时返回 false（应用层留
 * status=0 待推）；send 抛 {@link IOException} 时注销失效连接并返回 false。
 */
@Component
public class SseConnectionRegistry implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(SseConnectionRegistry.class);

    /** SSE 长连接超时（30 min，对齐 §4.1.3 长连接）。 */
    static final long SSE_TIMEOUT_MILLIS = 30L * 60 * 1000;

    /** 心跳帧注释文本（SSE wire 上输出 {@code :heartbeat}）。 */
    static final String HEARTBEAT_COMMENT = "heartbeat";

    private final ConcurrentHashMap<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    /** userId → 该连接的心跳定时任务（注销连接时取消，防泄漏）。 */
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> heartbeats =
            new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final long heartbeatIntervalMillis;
    private final ScheduledExecutorService heartbeatScheduler;

    public SseConnectionRegistry(
            ObjectMapper objectMapper,
            @Value("${push.sse.heartbeat-interval-millis:30000}") long heartbeatIntervalMillis,
            ScheduledExecutorService heartbeatScheduler) {
        this.objectMapper = objectMapper;
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    @Override
    public SseEmitter open(long userId) {
        SseEmitter previous = emitters.get(userId);
        if (previous != null) {
            // 重复开连接：优雅关闭旧连接（客户端会自动重连或本次替代）
            log.debug("SSE 重复开连接，关闭旧连接 userId={}", userId);
            previous.complete();
        }
        cancelHeartbeat(userId);
        SseEmitter emitter = createEmitter();
        emitters.put(userId, emitter);
        scheduleHeartbeat(userId, emitter);
        emitter.onCompletion(() -> unregister(userId, emitter));
        emitter.onTimeout(
                () -> {
                    log.debug("SSE 连接超时 userId={}", userId);
                    emitter.complete();
                    unregister(userId, emitter);
                });
        emitter.onError(
                throwable -> {
                    log.debug("SSE 连接异常 userId={}: {}", userId, throwable.toString());
                    unregister(userId, emitter);
                });
        log.info("SSE 连接注册 userId={}", userId);
        return emitter;
    }

    /** 创建 SSE 句柄（protected seam：测试可覆写为受控 SseEmitter 子类，绕开容器内 SseEmitter 缓冲机制）。 */
    protected SseEmitter createEmitter() {
        return new SseEmitter(SSE_TIMEOUT_MILLIS);
    }

    @Override
    public boolean send(long userId, NotificationEvent event, long recordId) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter == null) {
            // 离线用户：交应用层留 status=0 待推
            return false;
        }
        try {
            String json = objectMapper.writeValueAsString(event);
            emitter.send(
                    SseEmitter.event()
                            .name(event.type())
                            .id(String.valueOf(recordId))
                            .data(json, MediaType.APPLICATION_JSON));
            return true;
        } catch (JsonProcessingException e) {
            // 序列化失败：自身缺陷，记 ERROR 不重试（事件已落地 push_record，可后续补）
            log.error("SSE 推送序列化失败 userId={} recordId={}: {}", userId, recordId, e.toString());
            return false;
        } catch (IOException | IllegalStateException e) {
            // 连接已失效（客户端断开）：注销并返回 false，应用层据重试策略处理
            log.debug("SSE 推送失败（连接失效）userId={} recordId={}: {}", userId, recordId, e.toString());
            unregister(userId, emitter);
            return false;
        }
    }

    @Override
    public boolean isOnline(long userId) {
        return emitters.containsKey(userId);
    }

    @Override
    public void close(long userId) {
        SseEmitter emitter = emitters.remove(userId);
        cancelHeartbeat(userId);
        if (emitter != null) {
            emitter.complete();
            log.info("SSE 连接关闭 userId={}", userId);
        }
    }

    /** 仅当 map 中仍是该 emitter 时移除（避免新连接被旧回调误删），连带取消其心跳任务。 */
    private void unregister(long userId, SseEmitter emitter) {
        if (emitters.remove(userId, emitter)) {
            cancelHeartbeat(userId);
        }
    }

    /** 登记周期心跳任务；间隔非正（显式关闭）不调度。 */
    private void scheduleHeartbeat(long userId, SseEmitter emitter) {
        if (heartbeatIntervalMillis <= 0) {
            return;
        }
        heartbeats.put(
                userId,
                heartbeatScheduler.scheduleAtFixedRate(
                        () -> sendHeartbeat(userId, emitter),
                        heartbeatIntervalMillis,
                        heartbeatIntervalMillis,
                        TimeUnit.MILLISECONDS));
    }

    /** 发送一帧 comment 心跳；连接已被替代/失效则自取消，发送异常按连接失效注销。 */
    private void sendHeartbeat(long userId, SseEmitter emitter) {
        if (emitters.get(userId) != emitter) {
            // 旧连接的心跳残 task（新连接已替代）：自取消
            ScheduledFuture<?> task = heartbeats.remove(userId);
            if (task != null) {
                task.cancel(false);
            }
            return;
        }
        try {
            emitter.send(SseEmitter.event().comment(HEARTBEAT_COMMENT));
            log.debug("SSE 心跳 userId={}", userId);
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 心跳失败（连接失效）userId={}: {}", userId, e.toString());
            unregister(userId, emitter);
        }
    }

    /** 取消该用户当前心跳任务（无任务静默）。 */
    private void cancelHeartbeat(long userId) {
        ScheduledFuture<?> task = heartbeats.remove(userId);
        if (task != null) {
            task.cancel(false);
        }
    }
}
