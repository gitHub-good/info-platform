package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.TokenService.TokenClaims;
import com.info.platform.domain.common.TokenService.TokenPair;
import com.info.platform.domain.common.TokenService.TokenType;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * JwtTokenService 单测（T17）：签发/校验往返、access/refresh 类型正确、过期→1003、无效/签名不符→1003、密钥缺失/过短 fail-fast。 真实
 * JJWT，不启 Spring 上下文。
 */
class JwtTokenServiceTest {

    private static final String SECRET =
            "test-jwt-secret-not-for-production-use-only-64bytes-aaaaaaaaaaaaaaaa";

    private AuthProperties propsWith(Duration accessTtl, Duration refreshTtl) {
        AuthProperties p = new AuthProperties();
        p.getJwt().setSecret(SECRET);
        p.getJwt().setAccessTokenTtl(accessTtl);
        p.getJwt().setRefreshTokenTtl(refreshTtl);
        return p;
    }

    private JwtTokenService service() {
        return new JwtTokenService(propsWith(Duration.ofHours(1), Duration.ofDays(7)));
    }

    @Test
    void issueAndVerify_accessToken_roundTrip() {
        JwtTokenService svc = service();
        TokenPair pair = svc.issue(42L, "alice");

        TokenClaims claims = svc.verify(pair.accessToken());
        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.username()).isEqualTo("alice");
        assertThat(claims.tokenType()).isEqualTo(TokenType.ACCESS);
        assertThat(pair.expiresInSeconds()).isEqualTo(3600);
    }

    @Test
    void verify_refreshToken_hasRefreshType() {
        JwtTokenService svc = service();
        TokenPair pair = svc.issue(7L, "bob");

        TokenClaims claims = svc.verify(pair.refreshToken());
        assertThat(claims.tokenType()).isEqualTo(TokenType.REFRESH);
        assertThat(claims.userId()).isEqualTo(7L);
    }

    @Test
    void verify_garbageToken_throwsTokenInvalid() {
        JwtTokenService svc = service();
        assertThatThrownBy(() -> svc.verify("not-a-jwt"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    void verify_tokenSignedByDifferentKey_throwsTokenInvalid() {
        JwtTokenService svc1 = service();
        AuthProperties otherProps = new AuthProperties();
        otherProps.getJwt().setSecret("another-secret-key-also-not-for-prod-32bytes-min-xxxxx");
        otherProps.getJwt().setAccessTokenTtl(Duration.ofHours(1));
        otherProps.getJwt().setRefreshTokenTtl(Duration.ofDays(7));
        JwtTokenService svcOther = new JwtTokenService(otherProps);

        String token = svc1.issue(1L, "u").accessToken();
        assertThatThrownBy(() -> svcOther.verify(token))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    void verify_expiredToken_throwsTokenInvalid() throws Exception {
        JwtTokenService svc =
                new JwtTokenService(propsWith(Duration.ofMillis(1), Duration.ofDays(7)));
        String token = svc.issue(1L, "expiring").accessToken();
        Thread.sleep(50L); // 等待过期

        assertThatThrownBy(() -> svc.verify(token))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    void constructor_blankSecret_failFast() {
        AuthProperties p = new AuthProperties();
        p.getJwt().setSecret("");
        p.getJwt().setAccessTokenTtl(Duration.ofHours(1));
        p.getJwt().setRefreshTokenTtl(Duration.ofDays(7));
        assertThatThrownBy(() -> new JwtTokenService(p))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security.jwt.secret");
    }

    @Test
    void constructor_shortSecret_failFast() {
        AuthProperties p = new AuthProperties();
        p.getJwt().setSecret("short"); // < 32 字节
        p.getJwt().setAccessTokenTtl(Duration.ofHours(1));
        p.getJwt().setRefreshTokenTtl(Duration.ofDays(7));
        assertThatThrownBy(() -> new JwtTokenService(p))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("过短");
    }
}
