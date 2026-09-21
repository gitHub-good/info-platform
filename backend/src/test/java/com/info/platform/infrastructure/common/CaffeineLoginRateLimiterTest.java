package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** CaffeineLoginRateLimiter 单测（T17）：阈值触发锁定、锁定期间拒绝、锁过期自动放行、计数窗口重置。 用极小窗口/锁时长加速，避免阻塞。 */
class CaffeineLoginRateLimiterTest {

    private static final String IP = "10.0.0.1";
    private static final String USER = "admin";

    private CaffeineLoginRateLimiter limiter(int threshold, Duration window, Duration lock) {
        AuthProperties p = new AuthProperties();
        p.getLoginRateLimit().setThreshold(threshold);
        p.getLoginRateLimit().setWindow(window);
        p.getLoginRateLimit().setLockDuration(lock);
        return new CaffeineLoginRateLimiter(p);
    }

    @Test
    void belowThreshold_notBlocked() {
        CaffeineLoginRateLimiter lim = limiter(3, Duration.ofMillis(500), Duration.ofMillis(200));
        lim.recordFailure(IP, USER);
        lim.recordFailure(IP, USER);
        assertThat(lim.isBlocked(IP, USER)).isFalse();
    }

    @Test
    void reachingThreshold_blocks() {
        CaffeineLoginRateLimiter lim = limiter(3, Duration.ofMillis(500), Duration.ofMillis(200));
        lim.recordFailure(IP, USER);
        lim.recordFailure(IP, USER);
        lim.recordFailure(IP, USER); // 第 3 次 → 触发阈值
        assertThat(lim.isBlocked(IP, USER)).isTrue();
    }

    @Test
    void blockedRecordFailure_isNoOp_doesNotExtendLock() throws Exception {
        CaffeineLoginRateLimiter lim = limiter(2, Duration.ofMillis(500), Duration.ofMillis(200));
        lim.recordFailure(IP, USER);
        lim.recordFailure(IP, USER); // 触发锁
        assertThat(lim.isBlocked(IP, USER)).isTrue();

        // 锁定期间继续失败 → 不再累计/续期
        lim.recordFailure(IP, USER);
        lim.recordFailure(IP, USER);
        Thread.sleep(300L); // 等待锁过期
        assertThat(lim.isBlocked(IP, USER)).isFalse();
    }

    @Test
    void lockExpires_unblocksAutomatically() throws Exception {
        CaffeineLoginRateLimiter lim = limiter(2, Duration.ofMillis(500), Duration.ofMillis(150));
        lim.recordFailure(IP, USER);
        lim.recordFailure(IP, USER);
        assertThat(lim.isBlocked(IP, USER)).isTrue();
        Thread.sleep(250L); // 锁 150ms 过期
        assertThat(lim.isBlocked(IP, USER)).isFalse();
    }

    @Test
    void windowReset_afterQuietPeriod_countStartsOver() throws Exception {
        CaffeineLoginRateLimiter lim = limiter(3, Duration.ofMillis(100), Duration.ofMillis(200));
        lim.recordFailure(IP, USER);
        lim.recordFailure(IP, USER); // 窗口内 2 次
        Thread.sleep(150L); // 窗口 100ms 过期 → 计数重置
        lim.recordFailure(IP, USER); // 新窗口第 1 次
        assertThat(lim.isBlocked(IP, USER)).isFalse();
    }

    @Test
    void differentKey_isIndependent() {
        CaffeineLoginRateLimiter lim = limiter(2, Duration.ofMillis(500), Duration.ofMillis(200));
        lim.recordFailure(IP, USER);
        lim.recordFailure(IP, USER); // IP+USER 被锁
        assertThat(lim.isBlocked(IP, USER)).isTrue();
        // 同 IP 不同用户、同用户不同 IP 均不受影响
        assertThat(lim.isBlocked(IP, "other")).isFalse();
        assertThat(lim.isBlocked("10.0.0.2", USER)).isFalse();
    }

    @Test
    void nullInputs_handledWithoutError() {
        CaffeineLoginRateLimiter lim = limiter(2, Duration.ofMillis(500), Duration.ofMillis(200));
        lim.recordFailure(null, null);
        assertThat(lim.isBlocked(null, null)).isFalse();
    }
}
