package com.info.platform.infrastructure.common;

import java.time.Duration;
import java.util.Objects;

/**
 * 弹性四件套之超时 + 重试配置（供 {@link ResilienceRunner}）。
 *
 * <p>对齐方案 07 弹性四件套： {@code timeout} 单次调用上限； {@code maxRetries} 额外重试次数（0=不重试， 仅对幂等只读操作配 &gt;0）；
 * {@code backoffBase} 首次退避基数，退避 = backoffBase × 2^(attempt-1) + 25% 抖动。
 *
 * <p>各源差异化取值由子类 adapter 在 {@code resilienceSpec()} 声明（如行情超时 1.5s 重试 0、公告超时 2s 重试 1）。
 */
public final class ResilienceSpec {

    private final Duration timeout;
    private final int maxRetries;
    private final Duration backoffBase;

    public ResilienceSpec(Duration timeout, int maxRetries, Duration backoffBase) {
        this.timeout = Objects.requireNonNull(timeout, "timeout 必填");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout 必须为正");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries 不能为负");
        }
        this.maxRetries = maxRetries;
        this.backoffBase = backoffBase == null ? Duration.ZERO : backoffBase;
    }

    public static ResilienceSpec of(Duration timeout, int maxRetries, Duration backoffBase) {
        return new ResilienceSpec(timeout, maxRetries, backoffBase);
    }

    /** 无重试快捷工厂（幂等性弱或重试无意义的只读调用）。 */
    public static ResilienceSpec noRetry(Duration timeout) {
        return new ResilienceSpec(timeout, 0, Duration.ZERO);
    }

    public Duration timeout() {
        return timeout;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public Duration backoffBase() {
        return backoffBase;
    }
}
