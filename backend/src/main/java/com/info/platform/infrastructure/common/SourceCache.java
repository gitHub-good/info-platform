package com.info.platform.infrastructure.common;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.SourceStatus;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * 数据源本地缓存（Caffeine Cache-Aside，ADR-0005）。
 *
 * <p>按 {@link SourceCode} 分缓存区，差异化 TTL（行情 5s / 财务·估值 1h / 公告 5min / 新闻 2min / 政策 10min）， 各区均设
 * maximumSize + expireAfterWrite（Caffeine 红线：防 OOM、防陈旧数据；禁当持久层）。 key=subjectId，value={@link
 * SourceResult}。
 *
 * <p>仅缓存 status={@link SourceStatus#OK} 的结果：MISSING/FAILED 不缓存，避免长 TTL 源（如财务 1h）把"暂无数据"误锁一整小时。
 * 穿透防护（负缓存空值 + 短 TTL）留待 T16 与 {@code data_source_event} 一并落地。
 */
public class SourceCache {

    private final Map<SourceCode, Cache<Long, SourceResult>> caches;

    public SourceCache() {
        Map<SourceCode, Cache<Long, SourceResult>> map = new EnumMap<>(SourceCode.class);
        for (SourceCode code : SourceCode.values()) {
            CacheSpec spec = specFor(code);
            map.put(
                    code,
                    Caffeine.newBuilder()
                            .maximumSize(spec.maxSize())
                            .expireAfterWrite(spec.ttl())
                            .build());
        }
        this.caches = Map.copyOf(map);
    }

    public SourceResult getIfPresent(SourceCode code, Long subjectId) {
        Cache<Long, SourceResult> cache = caches.get(code);
        return cache == null ? null : cache.getIfPresent(subjectId);
    }

    /** 仅缓存 OK 结果；MISSING/FAILED 跳过。 */
    public void put(SourceCode code, Long subjectId, SourceResult result) {
        Cache<Long, SourceResult> cache = caches.get(code);
        if (cache != null && result != null && result.getStatus() == SourceStatus.OK) {
            cache.put(subjectId, result);
        }
    }

    /** 差异化 TTL 与容量（对齐技术方案 §4.4 缓存一致性 + ADR-0005）。 */
    private static CacheSpec specFor(SourceCode code) {
        return switch (code) {
            case QUOTE -> new CacheSpec(Duration.ofSeconds(5), 5_000);
            case FINANCE -> new CacheSpec(Duration.ofHours(1), 2_000);
            case VALUATION -> new CacheSpec(Duration.ofHours(1), 2_000);
            case ANNOUNCE -> new CacheSpec(Duration.ofMinutes(5), 2_000);
            case NEWS -> new CacheSpec(Duration.ofMinutes(2), 3_000);
            case POLICY -> new CacheSpec(Duration.ofMinutes(10), 2_000);
        };
    }

    /** 缓存规格（内部值对象）。 */
    private record CacheSpec(Duration ttl, long maxSize) {}
}
