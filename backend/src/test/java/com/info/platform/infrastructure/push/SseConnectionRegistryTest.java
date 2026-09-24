package com.info.platform.infrastructure.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.push.NotificationEvent;
import com.info.platform.domain.push.PushType;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

/**
 * SseConnectionRegistry 单元测试（T14）：经 {@link SseConnectionRegistry#createEmitter()} 测试缝注入受控 {@link
 * RecordingEmitter}，绕开容器内 SseEmitter 的异步缓冲/Handler 机制，确定性验证 注册/注销/在线/离线/发送成功/发送失败注销/并发安全 + JSON
 * 序列化格式 + 周期心跳帧（体检 O8，mock 调度器手动驱动任务确定性验证）。
 */
class SseConnectionRegistryTest {

    private RecordingRegistry registry;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ScheduledExecutorService scheduler;

    /** 受控 SseEmitter：覆写 send 决定成功/失败，计数调用次数，绕开真实响应缓冲。 */
    static class RecordingEmitter extends SseEmitter {
        volatile boolean failNext;
        final AtomicInteger sendCount = new AtomicInteger();

        RecordingEmitter(long timeout) {
            super(timeout);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sendCount.incrementAndGet();
            if (failNext) {
                throw new IOException("simulated connection failure");
            }
        }
    }

    /** 测试子注册表：覆写 createEmitter 注入 RecordingEmitter。 */
    static class RecordingRegistry extends SseConnectionRegistry {
        final List<RecordingEmitter> created = new CopyOnWriteArrayList<>();

        RecordingRegistry(ObjectMapper objectMapper, ScheduledExecutorService scheduler) {
            super(objectMapper, 30_000L, scheduler);
        }

        @Override
        protected SseEmitter createEmitter() {
            RecordingEmitter e = new RecordingEmitter(SSE_TIMEOUT_MILLIS);
            created.add(e);
            return e;
        }

        RecordingEmitter last() {
            return created.get(created.size() - 1);
        }
    }

    @BeforeEach
    void setUp() {
        // mock 调度器：捕获心跳任务手动 run()，测试不真实等待；返回受控 future 防 NPE
        scheduler = Mockito.mock(ScheduledExecutorService.class);
        Mockito.when(
                        scheduler.scheduleAtFixedRate(
                                Mockito.any(Runnable.class),
                                Mockito.anyLong(),
                                Mockito.anyLong(),
                                Mockito.any(TimeUnit.class)))
                .thenAnswer(inv -> Mockito.mock(ScheduledFuture.class));
        registry = new RecordingRegistry(objectMapper, scheduler);
    }

    @Test
    void open_registersAndMarksOnline() {
        SseEmitter emitter = registry.open(1L);
        assertThat(emitter).isNotNull();
        assertThat(registry.isOnline(1L)).isTrue();
    }

    @Test
    void open_twice_closesOldAndReplaces() {
        SseEmitter first = registry.open(1L);
        SseEmitter second = registry.open(1L);
        assertThat(second).isNotSameAs(first);
        assertThat(registry.isOnline(1L)).isTrue();
        assertThat(registry.created).hasSize(2);
    }

    @Test
    void send_offlineUser_returnsFalse() {
        boolean ok = registry.send(99L, payload(), 5L);
        assertThat(ok).isFalse();
    }

    @Test
    void send_onlineUser_returnsTrueAndEmitsOnce() {
        registry.open(1L);
        boolean ok = registry.send(1L, payload(), 7L);
        assertThat(ok).isTrue();
        assertThat(registry.last().sendCount.get()).isEqualTo(1);
        // 推送成功不注销
        assertThat(registry.isOnline(1L)).isTrue();
    }

    @Test
    void send_throwsIOException_unregistersAndReturnsFalse() {
        registry.open(1L);
        registry.last().failNext = true;
        boolean ok = registry.send(1L, payload(), 7L);
        assertThat(ok).isFalse();
        assertThat(registry.isOnline(1L)).isFalse(); // 失效连接已注销
    }

    @Test
    void close_unregistersUser() {
        registry.open(1L);
        assertThat(registry.isOnline(1L)).isTrue();
        registry.close(1L);
        assertThat(registry.isOnline(1L)).isFalse();
    }

    @Test
    void close_neverOpened_doesNotThrow() {
        registry.close(123L); // 无残留连接，静默
        assertThat(registry.isOnline(123L)).isFalse();
    }

    @Test
    void concurrent_openSendClose_distinctUsers_noException() throws Exception {
        int threads = 50;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final long uid = 1000L + i;
            pool.submit(
                    () -> {
                        try {
                            registry.open(uid);
                            registry.send(uid, payload(), uid);
                            registry.close(uid);
                        } finally {
                            done.countDown();
                        }
                    });
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        for (int i = 0; i < threads; i++) {
            assertThat(registry.isOnline(1000L + i)).isFalse();
        }
    }

    @Test
    void serialization_payloadJsonContainsTypeSubjectCodeRefContent() throws Exception {
        NotificationEvent event =
                NotificationEvent.of(PushType.ANOMALY, 600519L, "SH600519", "42", "涨幅5%");
        String json = objectMapper.writeValueAsString(event);
        assertThat(json).contains("\"type\":\"anomaly\"");
        assertThat(json).contains("\"subjectId\":600519");
        assertThat(json).contains("\"subjectCode\":\"SH600519\"");
        assertThat(json).contains("\"refId\":\"42\"");
        assertThat(json).contains("\"content\":\"涨幅5%\"");
    }

    // ---- 心跳帧（系统体检 20260924 O8）----

    @Test
    void open_schedulesHeartbeatAtConfiguredInterval() {
        registry.open(1L);

        // 周期 = 配置间隔（initialDelay = period = 30s，首帧不提前）
        Mockito.verify(scheduler)
                .scheduleAtFixedRate(
                        Mockito.any(Runnable.class),
                        Mockito.eq(30_000L),
                        Mockito.eq(30_000L),
                        Mockito.eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void heartbeatTask_sendsCommentFrame_keepsConnectionRegistered() {
        registry.open(1L);
        Runnable heartbeat = capturedHeartbeatTask();

        heartbeat.run();

        // comment 帧经 emitter.send 下发（SSE wire 为 :heartbeat，前端 onmessage 不触发）
        assertThat(registry.last().sendCount.get()).isEqualTo(1);
        assertThat(registry.isOnline(1L)).isTrue();
    }

    @Test
    void heartbeatTask_sendFails_unregistersConnection() {
        registry.open(1L);
        registry.last().failNext = true;
        Runnable heartbeat = capturedHeartbeatTask();

        heartbeat.run();

        // 心跳失败 = 连接已失效 → 注销（isOnline 误判止血，即 O8 的目标）
        assertThat(registry.isOnline(1L)).isFalse();
    }

    @Test
    void heartbeat_disabledByNonPositiveInterval_neverScheduled() {
        // 间隔 0 = 显式关闭心跳：open 不登记任何周期任务
        SseConnectionRegistry off = new SseConnectionRegistry(objectMapper, 0L, scheduler);
        off.open(1L);

        Mockito.verify(scheduler, Mockito.never())
                .scheduleAtFixedRate(
                        Mockito.any(Runnable.class),
                        Mockito.anyLong(),
                        Mockito.anyLong(),
                        Mockito.any(TimeUnit.class));
        assertThat(off.isOnline(1L)).isTrue();
        off.close(1L);
    }

    @Test
    void close_cancelsHeartbeatTask() throws Exception {
        // 独立 mock：scheduleAtFixedRate 返回受控 future，close 时必须 cancel（防定时任务泄漏）
        ScheduledFuture<?> future = Mockito.mock(ScheduledFuture.class);
        ScheduledExecutorService sched = Mockito.mock(ScheduledExecutorService.class);
        Mockito.when(
                        sched.scheduleAtFixedRate(
                                Mockito.any(Runnable.class),
                                Mockito.anyLong(),
                                Mockito.anyLong(),
                                Mockito.any(TimeUnit.class)))
                .thenAnswer(inv -> future);
        RecordingRegistry reg = new RecordingRegistry(objectMapper, sched);

        reg.open(1L);
        reg.close(1L);

        Mockito.verify(future).cancel(false);
        assertThat(reg.isOnline(1L)).isFalse();
    }

    /** 捕获当前注册表 open 时登记的心跳任务（手动驱动）。 */
    private Runnable capturedHeartbeatTask() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        Mockito.verify(scheduler)
                .scheduleAtFixedRate(
                        captor.capture(),
                        Mockito.anyLong(),
                        Mockito.anyLong(),
                        Mockito.any(TimeUnit.class));
        return captor.getValue();
    }

    private static NotificationEvent payload() {
        return NotificationEvent.of(PushType.ANOMALY, 1L, "SH600519", "1", "content");
    }
}
