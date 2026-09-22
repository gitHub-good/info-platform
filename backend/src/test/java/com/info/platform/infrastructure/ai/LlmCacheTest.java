package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.LlmProvider;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.domain.ai.LlmUsage;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * LlmCache 单测（T19+T35）：同 prompt+context 命中、briefType 分区、per-entry TTL 解析（ADR-0005 + ADR-0008）。
 *
 * <p>覆盖：命中/未命中、按 {@code briefType} 分区（同内容不同类型不共享）、TTL 解析器被调用、 <b>TTL 运行时热改对新条目生效</b> （T35）、null
 * 响应不缓存。
 *
 * <p><b>DEFECT-1 过期回归（M4）</b>：fake ticker 注入（不真实等待），验证「读后过 TTL 须重新取数」——曾因 {@code expireAfterRead}
 * 返回 {@code Long.MIN_VALUE}（Caffeine 3.1.8 实测语义=条目永不过期）导致 AI 简报/每日推荐缓存被读后永驻。
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
    void ttlChangedAtRuntime_resolverReadsLatestValueForNewEntries() {
        // Arrange（T35 热改，方案 §4.3「TTL 分档改运行时」）：解析函数按可变表现算——等价于装配传入的 ConfigCenter 快照函数
        java.util.Map<String, Long> runtimeTtl = new java.util.HashMap<>();
        runtimeTtl.put("brief-type-1", 60L);
        List<Long> resolved = new java.util.ArrayList<>();
        LlmCache cache =
                new LlmCache(
                        bt -> {
                            long seconds = runtimeTtl.getOrDefault("brief-type-" + bt, 60L);
                            resolved.add(seconds);
                            return Duration.ofSeconds(seconds);
                        },
                        Duration.ofSeconds(60),
                        100);
        cache.put(request("1", "ctx-a"), response());

        // Act：页面把 brief-type-1 的 TTL 从 60s 改为 3600s（保存即生效）
        runtimeTtl.put("brief-type-1", 3600L);
        cache.put(request("1", "ctx-b"), response());

        // Assert：新缓存条目按写入时点的新值解析（存量条目按写入时点旧值到期，不回溯）
        assertThat(resolved).containsExactly(60L, 3600L);
    }

    @Test
    void putNullResponse_notCached() {
        LlmCache cache = new LlmCache(bt -> Duration.ofSeconds(60), Duration.ofSeconds(60), 100);
        LlmRequest req = request("1", "ctx");
        cache.put(req, null);
        assertThat(cache.getIfPresent(req)).isNull();
    }

    // —— DEFECT-1 过期回归（fake ticker 驱动，修前红修后绿）——

    /** 手动推进的假时钟：过期用例不真实等待（离线探针同源，报告 §4 DEFECT-1）。 */
    private static final class FakeTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }

    @Test
    void readEntry_expiresAfterTtl_notImmortal() {
        // DEFECT-1 回归（修前红）：命中一次（完成「读」）后，过 TTL 再读必须 miss 重新调用
        LlmCacheTest.FakeTicker ticker = new LlmCacheTest.FakeTicker();
        LlmCache cache =
                new LlmCache(bt -> Duration.ofSeconds(2), Duration.ofSeconds(2), 100, ticker);
        LlmRequest req = request("1", "ctx");
        cache.put(req, response());
        assertThat(cache.getIfPresent(req)).isEqualTo(response());

        ticker.advance(Duration.ofSeconds(60));

        assertThat(cache.getIfPresent(req)).isNull();
    }

    @Test
    void readEntry_ttlNotExtendedByRead() {
        // 读不延长：TTL 内读一次，到期时点（写入时点+TTL）仍须过期
        LlmCacheTest.FakeTicker ticker = new LlmCacheTest.FakeTicker();
        LlmCache cache =
                new LlmCache(bt -> Duration.ofSeconds(2), Duration.ofSeconds(2), 100, ticker);
        LlmRequest req = request("1", "ctx");
        cache.put(req, response());

        ticker.advance(Duration.ofSeconds(1));
        assertThat(cache.getIfPresent(req)).isEqualTo(response());

        ticker.advance(Duration.ofSeconds(2));

        assertThat(cache.getIfPresent(req)).isNull();
    }
}
