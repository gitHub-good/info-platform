package com.info.platform.infrastructure.common;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 认证相关配置（绑定 {@code security.*}，基础设施层横切）。
 *
 * <p>对齐技术方案 §5 安全与 backend.md「认证」段：JWT access 短期 1h + refresh 7d；登录限流按 ip+username
 * 组合，窗口内失败达阈值即锁定。敏感配置 {@code security.jwt.secret} 走环境变量 {@code ${JWT_SECRET}} 注入，生产缺失即 fail-fast（见
 * {@code JwtTokenService} 构造校验）。
 */
@Component
@ConfigurationProperties(prefix = "security")
public class AuthProperties {

    private Jwt jwt = new Jwt();
    private LoginRateLimit loginRateLimit = new LoginRateLimit();

    public Jwt getJwt() {
        return jwt;
    }

    public void setJwt(Jwt jwt) {
        this.jwt = jwt;
    }

    public LoginRateLimit getLoginRateLimit() {
        return loginRateLimit;
    }

    public void setLoginRateLimit(LoginRateLimit loginRateLimit) {
        this.loginRateLimit = loginRateLimit;
    }

    /** JWT 签发参数。 */
    public static class Jwt {
        /** HS256 签名密钥，环境变量 {@code ${JWT_SECRET}} 注入，永不写入文件。 */
        private String secret;

        /** access 令牌有效期（短期，默认 1h）。 */
        private Duration accessTokenTtl = Duration.ofHours(1);

        /** refresh 令牌有效期（默认 7d）。 */
        private Duration refreshTokenTtl = Duration.ofDays(7);

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public Duration getAccessTokenTtl() {
            return accessTokenTtl;
        }

        public void setAccessTokenTtl(Duration accessTokenTtl) {
            this.accessTokenTtl = accessTokenTtl;
        }

        public Duration getRefreshTokenTtl() {
            return refreshTokenTtl;
        }

        public void setRefreshTokenTtl(Duration refreshTokenTtl) {
            this.refreshTokenTtl = refreshTokenTtl;
        }
    }

    /** 登录限流参数（撞库防护）。 */
    public static class LoginRateLimit {
        /** 失败计数窗口（默认 1m）。 */
        private Duration window = Duration.ofMinutes(1);

        /** 窗口内失败次数阈值（默认 5 次）。 */
        private int threshold = 5;

        /** 触发阈值后的锁定时长（默认 5m）。 */
        private Duration lockDuration = Duration.ofMinutes(5);

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public int getThreshold() {
            return threshold;
        }

        public void setThreshold(int threshold) {
            this.threshold = threshold;
        }

        public Duration getLockDuration() {
            return lockDuration;
        }

        public void setLockDuration(Duration lockDuration) {
            this.lockDuration = lockDuration;
        }
    }
}
