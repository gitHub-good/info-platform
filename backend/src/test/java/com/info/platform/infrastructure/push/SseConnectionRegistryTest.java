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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

/**
 * SseConnectionRegistry 单元测试（T14）：经 {@link SseConnectionRegistry#createEmitter()} 测试缝注入受控 {@link
 * RecordingEmitter}，绕开容器内 SseEmitter 的异步缓冲/Handler 机制，确定性验证 注册/注销/在线/离线/发送成功/发送失败注销/并发安全 + JSON
 * 序列化格式。
 */
class SseConnectionRegistryTest {

    private RecordingRegistry registry;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

        RecordingRegistry(ObjectMapper objectMapper) {
            super(objectMapper);
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
        registry = new RecordingRegistry(objectMapper);
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
    void serialization_payloadJsonContainsTypeSubjectRefContent() throws Exception {
        NotificationEvent event = NotificationEvent.of(PushType.ANOMALY, 600519L, "42", "涨幅5%");
        String json = objectMapper.writeValueAsString(event);
        assertThat(json).contains("\"type\":\"anomaly\"");
        assertThat(json).contains("\"subjectId\":600519");
        assertThat(json).contains("\"refId\":\"42\"");
        assertThat(json).contains("\"content\":\"涨幅5%\"");
    }

    private static NotificationEvent payload() {
        return NotificationEvent.of(PushType.ANOMALY, 1L, "1", "content");
    }
}
