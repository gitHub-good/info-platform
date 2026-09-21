package com.info.platform.domain.common;

/**
 * 令牌服务端口（依赖倒置：领域层定义、基础设施层 JWT 实现）。
 *
 * <p>领域层纯净接口。认证应用层用它签发 access/refresh token 对、解析并校验令牌。 无效/过期令牌由实现抛 {@link BusinessException}（{@link
 * ErrorCode#TOKEN_INVALID}）。
 */
public interface TokenService {

    /** 签发 access + refresh token 对（access 短期、refresh 长期，TTL 由基础设施层配置注入）。 */
    TokenPair issue(long userId, String username);

    /**
     * 解析并校验令牌，返回声明。令牌缺失/格式错误/签名不符/已过期 → 抛 {@link BusinessException} （{@link
     * ErrorCode#TOKEN_INVALID}）。
     */
    TokenClaims verify(String token);

    /** 令牌类型：access（接口鉴权）/ refresh（换发新对）。 */
    enum TokenType {
        ACCESS,
        REFRESH
    }

    /** access + refresh token 对。 */
    record TokenPair(String accessToken, String refreshToken, long expiresInSeconds) {}

    /** 解析后的令牌声明，承载 {@code userId}（行级权限取数键）与 {@code username}。 */
    record TokenClaims(long userId, String username, TokenType tokenType) {}
}
