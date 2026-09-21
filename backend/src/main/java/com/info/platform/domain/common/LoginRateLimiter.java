package com.info.platform.domain.common;

/**
 * 登录限流端口（依赖倒置：领域层定义、基础设施层 Caffeine 实现）。
 *
 * <p>领域层纯净接口。撞库防护：按 {@code ip + username} 组合计数，窗口内失败次数达阈值则锁定该组合一段时间。 认证应用层在登录前查 {@link
 * #isBlocked}、凭证错误后调 {@link #recordFailure}。
 */
public interface LoginRateLimiter {

    /** 是否处于锁定状态（锁定窗口内拒绝登录）。 */
    boolean isBlocked(String ip, String username);

    /** 记录一次登录失败（窗口内累计达阈值即触发锁定）。 */
    void recordFailure(String ip, String username);
}
