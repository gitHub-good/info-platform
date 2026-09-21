package com.info.platform.infrastructure.common;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.info.platform.domain.common.LoginRateLimiter;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * {@link LoginRateLimiter} 端口的 Caffeine 实现（基础设施层，进程内限流）。
 *
 * <p>撞库防护：按 {@code ip + username} 组合在 {@code window} 内计数失败次数，达 {@code threshold} 即置 {@code
 * blockedUntil}， 锁定 {@code lockDuration} 内拒绝该组合的登录。本地缓存红线遵守：设 {@code maximumSize} 与 {@code
 * expireAfterWrite}（过期自动清理陈旧条目）。 多实例部署需升级 Redis 限流（见 backend.md 升级线）。
 */
@Component
public class CaffeineLoginRateLimiter implements LoginRateLimiter {

    private final long windowMillis;
    private final int threshold;
    private final long lockMillis;
    private final Cache<String, Entry> cache;

    public CaffeineLoginRateLimiter(AuthProperties properties) {
        AuthProperties.LoginRateLimit cfg = properties.getLoginRateLimit();
        this.windowMillis = cfg.getWindow().toMillis();
        this.threshold = cfg.getThreshold();
        this.lockMillis = cfg.getLockDuration().toMillis();
        // 清理 TTL = 锁定 + 窗口：保证已锁定条目至少存活到锁结束，不被提前清掉误放行
        this.cache =
                Caffeine.newBuilder()
                        .maximumSize(10_000)
                        .expireAfterWrite(Duration.ofMillis(lockMillis + windowMillis))
                        .build();
    }

    @Override
    public boolean isBlocked(String ip, String username) {
        Entry e = cache.getIfPresent(key(ip, username));
        return e != null && e.blockedUntilEpoch > System.currentTimeMillis();
    }

    @Override
    public void recordFailure(String ip, String username) {
        String k = key(ip, username);
        long now = System.currentTimeMillis();
        Entry e = cache.get(k, x -> new Entry(now));
        synchronized (e) {
            if (e.blockedUntilEpoch > now) {
                // 已锁定，不再累计（避免无限续期）
                return;
            }
            if (now - e.firstFailureEpoch > windowMillis) {
                // 计数窗口已过，重新计 1 次
                e.firstFailureEpoch = now;
                e.count = 1;
            } else {
                e.count++;
            }
            if (e.count >= threshold) {
                e.blockedUntilEpoch = now + lockMillis;
            }
        }
    }

    private static String key(String ip, String username) {
        return (ip == null ? "" : ip) + "|" + (username == null ? "" : username);
    }

    /** 单组合的限流状态（可变，按实例同步）。 */
    private static final class Entry {
        long firstFailureEpoch;
        int count;
        long blockedUntilEpoch;

        Entry(long now) {
            this.firstFailureEpoch = now;
            this.count = 0;
            this.blockedUntilEpoch = 0L;
        }
    }
}
