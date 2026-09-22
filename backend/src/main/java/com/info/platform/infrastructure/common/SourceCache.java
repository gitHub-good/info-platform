package com.info.platform.infrastructure.common;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.SourceStatus;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 数据源本地缓存（Caffeine Cache-Aside，ADR-0005）。
 *
 * <p>按 {@link SourceCode} 分缓存区，差异化 TTL（行情 5s / 财务·估值 1h / 公告 5min / 新闻 2min / 政策 10min / 事件 30s），
 * 各区均设 maximumSize + 过期（Caffeine 红线：防 OOM、防陈旧数据；禁当持久层）。 key=subjectId，value={@link SourceResult}。
 *
 * <p><b>T36 TTL 热化</b>（方案 §4.3「SourceCache 改 per-entry Expiry + 运行时 TTL 供应」，对齐 LlmCache 模式）：TTL
 * 改为每条目写入/更新时经 {@code ttlSupplier} 从运行时配置 {@code datasource.{CODE}.cacheTtlSeconds} 解析（LIVE
 * 级，页面保存后<b>新缓存条目</b>即按新值；存量条目按写入时点值到期，不回溯；读不延长）。 供应函数异常回落 {@link
 * DataSourceDefaults#cacheTtlSeconds}；无供应构造（纯构造单测）同。容量 maximumSize 建缓存时固化（保持启动期，RESTART 级）。
 *
 * <p>仅缓存 status={@link SourceStatus#OK} 的结果：MISSING/FAILED 不缓存，避免长 TTL 源（如财务 1h）把"暂无数据"误锁一整小时。
 * 穿透防护（负缓存空值 + 短 TTL）留待后续与 {@code data_source_event} 一并评估。
 */
public class SourceCache {

    private final Map<SourceCode, Cache<Long, SourceResult>> caches;

    /** 无供应构造：TTL 固定为代码缺省（纯构造单测/降级用）。 */
    public SourceCache() {
        this(code -> Duration.ofSeconds(DataSourceDefaults.cacheTtlSeconds(code)));
    }

    /** 运行时 TTL 供应构造：每条目写入时解析当前配置（LIVE 级热生效）。 */
    public SourceCache(Function<SourceCode, Duration> ttlSupplier) {
        Map<SourceCode, Cache<Long, SourceResult>> map = new EnumMap<>(SourceCode.class);
        for (SourceCode code : SourceCode.values()) {
            map.put(
                    code,
                    Caffeine.newBuilder()
                            .maximumSize(maxSizeFor(code))
                            .expireAfter(new SourceExpiry(code, ttlSupplier))
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

    /** 差异化容量（启动期固化，对齐技术方案 §4.4 缓存一致性 + ADR-0005）。 */
    private static long maxSizeFor(SourceCode code) {
        return switch (code) {
            case QUOTE -> 5_000;
            case NEWS -> 3_000;
            case FINANCE, VALUATION, ANNOUNCE, POLICY, EVENT -> 2_000;
        };
    }

    /** 每条目 TTL：写入/更新按当前配置解析，读不延长（{@code Long.MIN_VALUE} = 不变）。 */
    private static final class SourceExpiry implements Expiry<Long, SourceResult> {
        private final SourceCode code;
        private final Function<SourceCode, Duration> ttlSupplier;

        private SourceExpiry(SourceCode code, Function<SourceCode, Duration> ttlSupplier) {
            this.code = code;
            this.ttlSupplier = ttlSupplier;
        }

        @Override
        public long expireAfterCreate(Long key, SourceResult value, long currentTime) {
            return resolveTtl().toNanos();
        }

        @Override
        public long expireAfterUpdate(
                Long key, SourceResult value, long currentTime, long currentDuration) {
            return resolveTtl().toNanos();
        }

        @Override
        public long expireAfterRead(
                Long key, SourceResult value, long currentTime, long currentDuration) {
            return Long.MIN_VALUE;
        }

        private Duration resolveTtl() {
            try {
                Duration ttl = ttlSupplier.apply(code);
                return ttl == null || ttl.isNegative() || ttl.isZero()
                        ? Duration.ofSeconds(DataSourceDefaults.cacheTtlSeconds(code))
                        : ttl;
            } catch (Exception e) {
                return Duration.ofSeconds(DataSourceDefaults.cacheTtlSeconds(code));
            }
        }
    }
}
