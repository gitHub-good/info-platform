package com.info.platform.infrastructure.common;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
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
 * <p><b>失败负缓存（P1-5b/P2，体检条目「FAILED/MISSING 不负缓存」）</b>：FAILED/超时降级结果与 MISSING（源当日无数据）同样入缓存， 但走<b>独立短
 * TTL</b>（写入/更新时经 {@code failureTtlSupplier} 从 {@code datasource.{CODE}.failureCacheTtlSeconds}
 * 解析，缺省 {@link DataSourceDefaults#failureCacheTtlSeconds}：行情 10s / 其余 30s）——故障源窗口内命中负缓存快速返回降级态
 * （不再每请求吃满全额超时预算），TTL 过期即重试真实源（恢复感知 ≤30s，不受长 TTL 源如财务 1h 误锁）。条目按值状态分档：同 key FAILED→OK 覆写后按 OK TTL
 * 生效。
 */
public class SourceCache {

    private final Map<SourceCode, Cache<Long, SourceResult>> caches;

    /** 无供应构造：TTL 固定为代码缺省（纯构造单测/降级用）。 */
    public SourceCache() {
        this(
                code -> Duration.ofSeconds(DataSourceDefaults.cacheTtlSeconds(code)),
                code -> Duration.ofSeconds(DataSourceDefaults.failureCacheTtlSeconds(code)));
    }

    /** 运行时 TTL 供应构造：每条目写入时解析当前配置（LIVE 级热生效），时钟走系统缺省。 */
    public SourceCache(Function<SourceCode, Duration> ttlSupplier) {
        this(
                ttlSupplier,
                code -> Duration.ofSeconds(DataSourceDefaults.failureCacheTtlSeconds(code)),
                Ticker.systemTicker());
    }

    /** 运行时 TTL 供应构造（OK + 失败负缓存双供应，P1-5b），时钟走系统缺省。 */
    public SourceCache(
            Function<SourceCode, Duration> ttlSupplier,
            Function<SourceCode, Duration> failureTtlSupplier) {
        this(ttlSupplier, failureTtlSupplier, Ticker.systemTicker());
    }

    /** 同 {@link #SourceCache(Function, Function)}，可注入时钟——过期回归用例的 fake ticker（DEFECT-1，同包可见）。 */
    SourceCache(
            Function<SourceCode, Duration> ttlSupplier,
            Function<SourceCode, Duration> failureTtlSupplier,
            Ticker ticker) {
        Map<SourceCode, Cache<Long, SourceResult>> map = new EnumMap<>(SourceCode.class);
        for (SourceCode code : SourceCode.values()) {
            map.put(
                    code,
                    Caffeine.newBuilder()
                            .maximumSize(maxSizeFor(code))
                            .ticker(ticker)
                            .expireAfter(new SourceExpiry(code, ttlSupplier, failureTtlSupplier))
                            .build());
        }
        this.caches = Map.copyOf(map);
    }

    public SourceResult getIfPresent(SourceCode code, Long subjectId) {
        Cache<Long, SourceResult> cache = caches.get(code);
        return cache == null ? null : cache.getIfPresent(subjectId);
    }

    /** 写缓存：OK 按本源 TTL；FAILED/MISSING 按短 TTL 负缓存（P1-5b，同 key 覆写时按新值状态重算 TTL）。null 结果忽略。 */
    public void put(SourceCode code, Long subjectId, SourceResult result) {
        Cache<Long, SourceResult> cache = caches.get(code);
        if (cache != null && result != null) {
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

    /**
     * 每条目 TTL：写入/更新按当前配置解析（按值状态分档——OK 走本源 TTL，FAILED/MISSING 走失败短 TTL），读不延长。
     *
     * <p>「读不延长」= {@link #expireAfterRead} 返回剩余时长 {@code currentDuration}（DEFECT-1：Caffeine 3.1.8
     * 中返回 {@code Long.MIN_VALUE} 并非「不变」，而是使条目永不过期——被读过的条目永驻，切源与 TTL 热改对存量条目失效）。
     */
    private static final class SourceExpiry implements Expiry<Long, SourceResult> {
        private final SourceCode code;
        private final Function<SourceCode, Duration> ttlSupplier;
        private final Function<SourceCode, Duration> failureTtlSupplier;

        private SourceExpiry(
                SourceCode code,
                Function<SourceCode, Duration> ttlSupplier,
                Function<SourceCode, Duration> failureTtlSupplier) {
            this.code = code;
            this.ttlSupplier = ttlSupplier;
            this.failureTtlSupplier = failureTtlSupplier;
        }

        @Override
        public long expireAfterCreate(Long key, SourceResult value, long currentTime) {
            return resolveTtl(value).toNanos();
        }

        @Override
        public long expireAfterUpdate(
                Long key, SourceResult value, long currentTime, long currentDuration) {
            return resolveTtl(value).toNanos();
        }

        @Override
        public long expireAfterRead(
                Long key, SourceResult value, long currentTime, long currentDuration) {
            return currentDuration;
        }

        /** 按值状态分档解析：OK → 本源 TTL；FAILED/MISSING → 失败短 TTL（负缓存，P1-5b）。 */
        private Duration resolveTtl(SourceResult value) {
            boolean negative = value.getStatus() != SourceStatus.OK;
            Function<SourceCode, Duration> supplier = negative ? failureTtlSupplier : ttlSupplier;
            long fallbackSeconds =
                    negative
                            ? DataSourceDefaults.failureCacheTtlSeconds(code)
                            : DataSourceDefaults.cacheTtlSeconds(code);
            try {
                Duration ttl = supplier.apply(code);
                return ttl == null || ttl.isNegative() || ttl.isZero()
                        ? Duration.ofSeconds(fallbackSeconds)
                        : ttl;
            } catch (Exception e) {
                return Duration.ofSeconds(fallbackSeconds);
            }
        }
    }
}
