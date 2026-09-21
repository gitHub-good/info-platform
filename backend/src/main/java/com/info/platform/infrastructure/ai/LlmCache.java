package com.info.platform.infrastructure.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Function;

/**
 * LLM 响应本地缓存（Caffeine Cache-Aside，ADR-0005 + ADR-0008）。
 *
 * <p>同 prompt+context 命中即直返（0 调用、0 成本），重复请求降本；DeepSeek 服务端 {@code prompt_cache_hit_tokens} 折扣叠加。缓存键
 * = {@code briefTypeKey + sha256(messages+model+temperature+ maxTokens+responseFormat)}，不含 {@code
 * briefTypeKey} 本身（用作 TTL 分区，不入哈希——同内容不同简报类型可共 享，但实际调用方按 brief_type 分桶组装，碰撞可忽略）。
 *
 * <p>按 {@code brief_type} 差异化 TTL（个股 1h / 每日推荐 24h，Spike-2 §9）： 单缓存 + 每条目 {@link Expiry}，建/写时按
 * 缓存键中的 {@code briefType} 解析 TTL，读不延长。 Caffeine 红线：必设 {@code maximumSize} + 过期（防 OOM、防陈旧），禁当持久层。
 */
public class LlmCache {

    private final Cache<CacheKey, LlmResponse> cache;

    public LlmCache(Function<String, Duration> ttlForBriefType, Duration defaultTtl, long maxSize) {
        this.cache =
                Caffeine.newBuilder()
                        .maximumSize(maxSize)
                        .expireAfter(new LlmExpiry(ttlForBriefType, defaultTtl))
                        .build();
    }

    /** 命中直返；未命中返回 null（由 gateway 调 adapter 后 {@link #put}）。 */
    public LlmResponse getIfPresent(LlmRequest request) {
        return cache.getIfPresent(keyOf(request));
    }

    public void put(LlmRequest request, LlmResponse response) {
        if (response == null) {
            return;
        }
        cache.put(keyOf(request), response);
    }

    private static CacheKey keyOf(LlmRequest request) {
        String briefType = request.briefTypeKey() == null ? "" : request.briefTypeKey();
        String hash = sha256(canonical(request));
        return new CacheKey(briefType, hash);
    }

    private static String canonical(LlmRequest request) {
        StringBuilder sb = new StringBuilder();
        for (var m : request.messages()) {
            sb.append(m.role()).append(':').append(m.content()).append('|');
        }
        sb.append("model=").append(request.model()).append('|');
        sb.append("temp=").append(request.temperature()).append('|');
        sb.append("maxTokens=").append(request.maxTokens()).append('|');
        sb.append("fmt=").append(request.responseFormatType());
        return sb.toString();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 缓存键（briefType 分区 + 内容哈希）。 */
    private record CacheKey(String briefType, String hash) {}

    /** 每条目差异化 TTL：建/写按 briefType 解析 TTL，读不延长（{@code Long.MIN_VALUE} = 不变）。 */
    private static final class LlmExpiry implements Expiry<CacheKey, LlmResponse> {
        private final Function<String, Duration> ttlForBriefType;
        private final Duration defaultTtl;

        private LlmExpiry(Function<String, Duration> ttlForBriefType, Duration defaultTtl) {
            this.ttlForBriefType = ttlForBriefType;
            this.defaultTtl = defaultTtl;
        }

        @Override
        public long expireAfterCreate(CacheKey key, LlmResponse value, long currentTime) {
            return resolveTtl(key).toNanos();
        }

        @Override
        public long expireAfterUpdate(
                CacheKey key, LlmResponse value, long currentTime, long currentDuration) {
            return resolveTtl(key).toNanos();
        }

        @Override
        public long expireAfterRead(
                CacheKey key, LlmResponse value, long currentTime, long currentDuration) {
            return Long.MIN_VALUE;
        }

        private Duration resolveTtl(CacheKey key) {
            String briefType = key.briefType();
            if (briefType == null || briefType.isEmpty()) {
                return defaultTtl;
            }
            try {
                return ttlForBriefType.apply(briefType);
            } catch (Exception e) {
                return defaultTtl;
            }
        }
    }

    // 仅用于测试观察内部大小
    long estimatedSize() {
        return cache.estimatedSize();
    }
}
