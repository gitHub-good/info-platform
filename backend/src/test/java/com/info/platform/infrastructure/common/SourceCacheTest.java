package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.SourceStatus;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SourceCache 单测（T02）：OK 缓存命中、MISSING/FAILED 不缓存、按 sourceCode 分区。
 *
 * <p>验证 Caffeine 红线（maximumSize+expireAfterWrite）经配置生效的行为面——非命中结果不入缓存，避免长 TTL 源误锁恢复。
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
}
