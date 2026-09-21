package com.info.platform.application.common;

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
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 认证应用服务：编排登录与令牌换发，依赖领域端口（UserRepository / PasswordEncoder / TokenService / LoginRateLimiter）。
 *
 * <p>对齐技术方案 §5 安全：JWT 短期 access + refresh；登录限流防撞库（按 ip+username）。
 * 失败语义：凭证错误→1001（401）、限流→1002（429）、令牌无效/过期→1003（401）。
 */
@Service
public class AuthApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AuthApplicationService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final LoginRateLimiter loginRateLimiter;

    public AuthApplicationService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            TokenService tokenService,
            LoginRateLimiter loginRateLimiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.loginRateLimiter = loginRateLimiter;
    }

    /**
     * 登录：限流预检 → 加载用户 → 校验密码 → 签发令牌对。
     *
     * @param clientIp 客户端 IP（限流键之一，由接口层从请求提取）
     * @return access + refresh token 对
     */
    public TokenPair login(String username, String password, String clientIp) {
        if (loginRateLimiter.isBlocked(clientIp, username)) {
            log.warn("登录被限流: ip={}, username={}", clientIp, username);
            throw new BusinessException(ErrorCode.LOGIN_RATE_LIMITED);
        }

        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty() || !userOpt.get().passwordMatches(password, passwordEncoder)) {
            loginRateLimiter.recordFailure(clientIp, username);
            log.warn("登录失败（凭证错误）: ip={}, username={}", clientIp, username);
            throw new BusinessException(ErrorCode.BAD_CREDENTIALS);
        }

        User user = userOpt.get();
        TokenPair pair = tokenService.issue(user.getId(), user.getUsername());
        log.info("登录成功: userId={}, username={}", user.getId(), user.getUsername());
        return pair;
    }

    /**
     * 换发令牌：校验 refresh 令牌 → 确认用户仍存在 → 签发新令牌对。
     *
     * @param refreshToken 客户端提交的 refresh 令牌
     * @return 新的 access + refresh token 对
     */
    public TokenPair refresh(String refreshToken) {
        // verify 对无效/过期令牌抛 TOKEN_INVALID（1003）
        TokenClaims claims = tokenService.verify(refreshToken);
        if (claims.tokenType() != TokenType.REFRESH) {
            log.warn("换发令牌类型不符: 期望 REFRESH, 实际 {}", claims.tokenType());
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "令牌类型不符，需 refresh 令牌");
        }
        // 确认用户仍存在且未被禁用（refresh 长期内用户可能已删）
        if (userRepository.findByUsername(claims.username()).isEmpty()) {
            log.warn("换发令牌失败：用户不存在 username={}", claims.username());
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        TokenPair pair = tokenService.issue(claims.userId(), claims.username());
        log.info("换发令牌成功: userId={}, username={}", claims.userId(), claims.username());
        return pair;
    }
}
