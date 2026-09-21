package com.info.platform.application.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.LoginRateLimiter;
import com.info.platform.domain.common.PasswordEncoder;
import com.info.platform.domain.common.TokenService;
import com.info.platform.domain.common.TokenService.TokenClaims;
import com.info.platform.domain.common.TokenService.TokenPair;
import com.info.platform.domain.common.TokenService.TokenType;
import com.info.platform.domain.common.User;
import com.info.platform.domain.common.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** AuthApplicationService 单测（T17）：编排登录/换发，AAA 结构。 mock 四个领域端口，验证限流预检、凭证校验、失败计数、换发类型校验。 */
class AuthApplicationServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private TokenService tokenService;
    private LoginRateLimiter loginRateLimiter;
    private AuthApplicationService service;

    private static final String IP = "10.0.0.1";
    private static final User SEEDED_ADMIN =
            User.reconstruct(1L, "admin", "$2a$10$hash", 0L, Instant.now(), Instant.now());
    private static final TokenPair PAIR = new TokenPair("access-xyz", "refresh-uvw", 3600L);

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        tokenService = mock(TokenService.class);
        loginRateLimiter = mock(LoginRateLimiter.class);
        service =
                new AuthApplicationService(
                        userRepository, passwordEncoder, tokenService, loginRateLimiter);
    }

    @Test
    void login_validCredentials_returnsTokenPairWithoutCountingFailure() {
        // Arrange
        when(loginRateLimiter.isBlocked(IP, "admin")).thenReturn(false);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(SEEDED_ADMIN));
        when(passwordEncoder.matches("admin123", SEEDED_ADMIN.getPasswordHash())).thenReturn(true);
        when(tokenService.issue(1L, "admin")).thenReturn(PAIR);

        // Act
        TokenPair result = service.login("admin", "admin123", IP);

        // Assert
        assertThat(result).isEqualTo(PAIR);
        verify(loginRateLimiter, never()).recordFailure(anyString(), anyString());
    }

    @Test
    void login_wrongPassword_countsFailureAndThrowsBadCredentials() {
        when(loginRateLimiter.isBlocked(IP, "admin")).thenReturn(false);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(SEEDED_ADMIN));
        when(passwordEncoder.matches("wrong", SEEDED_ADMIN.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> service.login("admin", "wrong", IP))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_CREDENTIALS);
        verify(loginRateLimiter).recordFailure(IP, "admin");
        verify(tokenService, never()).issue(anyLong(), anyString());
    }

    @Test
    void login_userNotFound_countsFailureAndThrowsBadCredentials() {
        when(loginRateLimiter.isBlocked(IP, "ghost")).thenReturn(false);
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("ghost", "pw", IP))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_CREDENTIALS);
        verify(loginRateLimiter).recordFailure(IP, "ghost");
    }

    @Test
    void login_rateLimited_throwsBeforeCredentialCheck() {
        when(loginRateLimiter.isBlocked(IP, "admin")).thenReturn(true);

        assertThatThrownBy(() -> service.login("admin", "admin123", IP))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.LOGIN_RATE_LIMITED);
        // 限流短路：不查库、不校验、不计数、不发令牌
        verify(userRepository, never()).findByUsername(anyString());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(loginRateLimiter, never()).recordFailure(anyString(), anyString());
        verify(tokenService, never()).issue(anyLong(), anyString());
    }

    @Test
    void refresh_validRefreshToken_userExists_returnsNewPair() {
        TokenClaims claims = new TokenClaims(1L, "admin", TokenType.REFRESH);
        when(tokenService.verify("refresh-uvw")).thenReturn(claims);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(SEEDED_ADMIN));
        when(tokenService.issue(1L, "admin")).thenReturn(PAIR);

        TokenPair result = service.refresh("refresh-uvw");

        assertThat(result).isEqualTo(PAIR);
    }

    @Test
    void refresh_accessTokenRejected_throwsTokenInvalid() {
        // access 令牌不能用于换发
        TokenClaims claims = new TokenClaims(1L, "admin", TokenType.ACCESS);
        when(tokenService.verify("access-xyz")).thenReturn(claims);

        assertThatThrownBy(() -> service.refresh("access-xyz"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOKEN_INVALID);
        verify(tokenService, never()).issue(anyLong(), anyString());
    }

    @Test
    void refresh_userNoLongerExists_throwsTokenInvalid() {
        TokenClaims claims = new TokenClaims(1L, "deleted", TokenType.REFRESH);
        when(tokenService.verify("refresh-uvw")).thenReturn(claims);
        when(userRepository.findByUsername("deleted")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("refresh-uvw"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOKEN_INVALID);
        verify(tokenService, never()).issue(anyLong(), anyString());
    }

    @Test
    void refresh_invalidToken_propagatesTokenInvalid() {
        // verify 对无效令牌抛 TOKEN_INVALID，编排层应原样上抛
        when(tokenService.verify("garbage"))
                .thenThrow(new BusinessException(ErrorCode.TOKEN_INVALID, "令牌无效"));

        assertThatThrownBy(() -> service.refresh("garbage"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOKEN_INVALID);
        verify(tokenService, never()).issue(anyLong(), anyString());
        verify(userRepository, never()).findByUsername(anyString());
    }
}
