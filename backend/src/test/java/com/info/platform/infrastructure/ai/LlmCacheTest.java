package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.LlmProvider;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.domain.ai.LlmUsage;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * LlmCache 单测（T19）：同 prompt+context 命中、briefType 分区、per-entry TTL 解析（ADR-0005 + ADR-0008）。
 *
 * <p>不实测真实过期（需等待）；覆盖：命中/未命中、按 {@code briefType} 分区（同内容不同类型不共享）、TTL 解析器被调用、null 响应不缓存。
 */
class LlmCacheTest {

    private static LlmRequest request(String briefType, String userContent) {
        return LlmRequest.json(
                List.of(new ChatMessage("system", "sys"), new ChatMessage("user", userContent)),
                briefType);
    }

    private static LlmResponse response() {
        return new LlmResponse(
                "content", new LlmUsage(10, 5), LlmProvider.DEEPSEEK, "deepseek-flash");
    }

    @Test
    void put_thenGetIfPresent_returnsSameResponse() {
        LlmCache cache = new LlmCache(bt -> Duration.ofSeconds(60), Duration.ofSeconds(60), 100);
        LlmRequest req = request("1", "ctx");
        cache.put(req, response());
        assertThat(cache.getIfPresent(req)).isEqualTo(response());
    }

    @Test
    void differentContent_misses() {
        LlmCache cache = new LlmCache(bt -> Duration.ofSeconds(60), Duration.ofSeconds(60), 100);
        cache.put(request("1", "ctxA"), response());
        assertThat(cache.getIfPresent(request("1", "ctxB"))).isNull();
    }

    @Test
    void sameContentDifferentBriefType_missesDueToPartition() {
        // briefType 作为缓存键分区字段（TTL 按 brief_type，不跨类型共享）
        LlmCache cache = new LlmCache(bt -> Duration.ofSeconds(60), Duration.ofSeconds(60), 100);
        cache.put(request("1", "shared ctx"), response());
        assertThat(cache.getIfPresent(request("4", "shared ctx"))).isNull();
    }

    @Test
    void ttlResolver_invokedWithBriefTypeOnPut() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> seen = new AtomicReference<>();
        LlmCache cache =
                new LlmCache(
                        bt -> {
                            calls.incrementAndGet();
                            seen.set(bt);
                            return Duration.ofSeconds(60);
                        },
                        Duration.ofSeconds(60),
                        100);
        cache.put(request("4", "ctx"), response());
        assertThat(seen.get()).isEqualTo("4");
        assertThat(calls.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void putNullResponse_notCached() {
        LlmCache cache = new LlmCache(bt -> Duration.ofSeconds(60), Duration.ofSeconds(60), 100);
        LlmRequest req = request("1", "ctx");
        cache.put(req, null);
        assertThat(cache.getIfPresent(req)).isNull();
    }
}
