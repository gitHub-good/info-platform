package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.SourceStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * SourceCache 单测（T02）：OK 缓存命中、MISSING/FAILED 不缓存、按 sourceCode 分区。
 *
 * <p>验证 Caffeine 红线（maximumSize+expireAfterWrite）经配置生效的行为面——非命中结果不入缓存，避免长 TTL 源误锁恢复。
 *
 * <p><b>DEFECT-1 过期回归（M4）</b>：fake ticker 注入不真实等待，验证「读后过 TTL 须重新取数」——曾因 {@code expireAfterRead} 返回
 * {@code Long.MIN_VALUE}（Caffeine 3.1.8 实测语义=条目永不过期，非注释假设的「不变」） 导致被读过的条目永驻， 切源（场景 3.2）与 TTL
 * 热改对存量条目失效。
 */
class SourceCacheTest {

    private final SourceCache cache = new SourceCache();

    @Test
    void putOkResult_thenGetIfPresentReturnsIt() {
        SourceResult ok =
                SourceResult.ok(SourceCode.QUOTE, 1L, Map.of("price", 1), "src", Instant.now());

        cache.put(SourceCode.QUOTE, 1L, ok);

        assertThat(cache.getIfPresent(SourceCode.QUOTE, 1L)).isSameAs(ok);
    }

    @Test
    void putMissingResult_isNotCached() {
        SourceResult missing = SourceResult.missing(SourceCode.QUOTE, 1L, "src");

        cache.put(SourceCode.QUOTE, 1L, missing);

        assertThat(cache.getIfPresent(SourceCode.QUOTE, 1L)).isNull();
    }

    @Test
    void putFailedResult_isNotCached() {
        SourceResult failed = SourceResult.failed(SourceCode.QUOTE, 1L, "src");

        cache.put(SourceCode.QUOTE, 1L, failed);

        assertThat(cache.getIfPresent(SourceCode.QUOTE, 1L)).isNull();
    }

    @Test
    void putNullResult_isIgnored() {
        cache.put(SourceCode.QUOTE, 1L, null);

        assertThat(cache.getIfPresent(SourceCode.QUOTE, 1L)).isNull();
    }

    @Test
    void getIfPresent_whenEmpty_returnsNull() {
        assertThat(cache.getIfPresent(SourceCode.NEWS, 9L)).isNull();
    }

    @Test
    void cachesArePartitionedBySourceCodeAndSubjectId() {
        SourceResult quote =
                SourceResult.ok(SourceCode.QUOTE, 1L, Map.of("p", 1), "s", Instant.now());

        cache.put(SourceCode.QUOTE, 1L, quote);

        assertThat(cache.getIfPresent(SourceCode.FINANCE, 1L)).isNull();
        assertThat(cache.getIfPresent(SourceCode.QUOTE, 2L)).isNull();
    }

    @Test
    void okResultCarriesOkStatusWhenCached() {
        SourceResult ok =
                SourceResult.ok(SourceCode.POLICY, 7L, Map.of("t", 1), "s", Instant.now());

        cache.put(SourceCode.POLICY, 7L, ok);

        SourceResult got = cache.getIfPresent(SourceCode.POLICY, 7L);
        assertThat(got).isNotNull();
        assertThat(got.getStatus()).isEqualTo(SourceStatus.OK);
    }

    @Test
    void runtimeTtlSupplier_readAtPutTimeForNewEntries() {
        // Arrange（T36 热化，对齐 LlmCache 测试口径）：供应函数按可变表现算——等价于装配传入的 ConfigCenter 快照函数
        java.util.Map<SourceCode, Long> runtimeTtl = new java.util.HashMap<>();
        runtimeTtl.put(SourceCode.QUOTE, 5L);
        java.util.List<Long> resolved = new java.util.ArrayList<>();
        SourceCache cache =
                new SourceCache(
                        code -> {
                            long seconds = runtimeTtl.getOrDefault(code, 60L);
                            resolved.add(seconds);
                            return Duration.ofSeconds(seconds);
                        });
        SourceResult ok =
                SourceResult.ok(SourceCode.QUOTE, 1L, Map.of("price", 1), "src", Instant.now());

        // Act：页面把 QUOTE 的 cacheTtlSeconds 从 5s 改为 60s（保存即换快照），再写一条新缓存
        cache.put(SourceCode.QUOTE, 1L, ok);
        runtimeTtl.put(SourceCode.QUOTE, 60L);
        SourceResult ok2 =
                SourceResult.ok(SourceCode.QUOTE, 2L, Map.of("price", 2), "src", Instant.now());
        cache.put(SourceCode.QUOTE, 2L, ok2);

        // Assert：新条目按写入时点的新值解析（存量条目按写入时点旧值到期，不回溯）
        assertThat(resolved).containsExactly(5L, 60L);
        assertThat(cache.getIfPresent(SourceCode.QUOTE, 1L)).isSameAs(ok);
        assertThat(cache.getIfPresent(SourceCode.QUOTE, 2L)).isSameAs(ok2);
    }

    @Test
    void supplierThrows_fallsBackToCodeDefaults_notBreakingWrites() {
        SourceCache cache =
                new SourceCache(
                        code -> {
                            throw new IllegalStateException("snapshot broken");
                        });
        SourceResult ok =
                SourceResult.ok(SourceCode.POLICY, 1L, Map.of("items", 1), "src", Instant.now());

        cache.put(SourceCode.POLICY, 1L, ok);

        assertThat(cache.getIfPresent(SourceCode.POLICY, 1L)).isSameAs(ok);
    }

    @Test
    void defaultConstructor_usesCodeDefaultTtls() {
        // 无供应构造（纯构造单测/降级）：回落 DataSourceDefaults（QUOTE 5s）
        SourceCache cache = new SourceCache();
        SourceResult ok =
                SourceResult.ok(SourceCode.QUOTE, 1L, Map.of("price", 1), "src", Instant.now());

        cache.put(SourceCode.QUOTE, 1L, ok);

        assertThat(cache.getIfPresent(SourceCode.QUOTE, 1L)).isSameAs(ok);
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

    private static SourceCache cacheWithTtl5s(Ticker ticker) {
        return new SourceCache(code -> Duration.ofSeconds(5), ticker);
    }

    @Test
    void unreadEntry_expiresAfterTtl() {
        // 对照组（装置可信性）：未被读过的条目按写入时 TTL 正常过期
        FakeTicker ticker = new FakeTicker();
        SourceCache cache = cacheWithTtl5s(ticker);
        SourceResult ok =
                SourceResult.ok(SourceCode.QUOTE, 1L, Map.of("price", 1), "src", Instant.now());
        cache.put(SourceCode.QUOTE, 1L, ok);

        ticker.advance(Duration.ofSeconds(30));

        assertThat(cache.getIfPresent(SourceCode.QUOTE, 1L)).isNull();
    }

    @Test
    void readEntry_expiresAfterTtl_notImmortal() {
        // DEFECT-1 回归（修前红）：命中一次（完成「读」）后，过 TTL 再读必须 miss 重新取数
        FakeTicker ticker = new FakeTicker();
        SourceCache cache = cacheWithTtl5s(ticker);
        SourceResult ok =
                SourceResult.ok(SourceCode.QUOTE, 1L, Map.of("price", 1), "src", Instant.now());
        cache.put(SourceCode.QUOTE, 1L, ok);
        assertThat(cache.getIfPresent(SourceCode.QUOTE, 1L)).isSameAs(ok);

        ticker.advance(Duration.ofSeconds(30));

        assertThat(cache.getIfPresent(SourceCode.QUOTE, 1L)).isNull();
    }

    @Test
    void readEntry_ttlNotExtendedByRead() {
        // 读不延长：TTL 内读一次，到期时点（写入时点+TTL）仍须过期
        FakeTicker ticker = new FakeTicker();
        SourceCache cache = cacheWithTtl5s(ticker);
        SourceResult ok =
                SourceResult.ok(SourceCode.QUOTE, 1L, Map.of("price", 1), "src", Instant.now());
        cache.put(SourceCode.QUOTE, 1L, ok);

        ticker.advance(Duration.ofSeconds(4));
        assertThat(cache.getIfPresent(SourceCode.QUOTE, 1L)).isSameAs(ok);

        ticker.advance(Duration.ofSeconds(2));

        assertThat(cache.getIfPresent(SourceCode.QUOTE, 1L)).isNull();
    }
}
