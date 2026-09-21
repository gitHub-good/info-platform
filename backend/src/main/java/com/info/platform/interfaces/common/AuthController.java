package com.info.platform.interfaces.common;

import com.info.platform.application.common.AuthApplicationService;
import com.info.platform.domain.common.TokenService.TokenPair;
import com.info.platform.interfaces.common.AuthController.LoginRequest;
import com.info.platform.interfaces.common.AuthController.RefreshRequest;
import com.info.platform.interfaces.common.AuthController.TokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（对齐技术方案 §4.1 认证段与 §5 安全）。
 *
 * <p>{@code POST /api/v1/auth/login}：用户名+密码 → 200 令牌对；凭证错误→1001（401）；限流→1002（429）。 {@code POST
 * /api/v1/auth/refresh}：refresh 令牌 → 新令牌对；令牌无效/过期→1003（401）。 限流按 ip+username 组合，ip
 * 由请求头提取（X-Forwarded-For 首段，缺失回退 remoteAddr）。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthApplicationService authApplicationService;

    public AuthController(AuthApplicationService authApplicationService) {
        this.authApplicationService = authApplicationService;
    }

    @PostMapping("/login")
    public Result<TokenResponse> login(
            @Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        TokenPair pair =
                authApplicationService.login(
                        req.username(), req.password(), extractClientIp(request));
        return Result.ok(TokenResponse.from(pair));
    }

    @PostMapping("/refresh")
    public Result<TokenResponse> refresh(@Valid @RequestBody RefreshRequest req) {
        TokenPair pair = authApplicationService.refresh(req.refreshToken());
        return Result.ok(TokenResponse.from(pair));
    }

    /** 取客户端 IP：X-Forwarded-For 首段（反向代理场景），否则 remoteAddr。 */
    private static String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String real = request.getHeader("X-Real-IP");
        return real != null && !real.isBlank() ? real.trim() : request.getRemoteAddr();
    }

    /** 登录请求体。 */
    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password) {}

    /** 换发请求体。 */
    public record RefreshRequest(@NotBlank(message = "refreshToken 不能为空") String refreshToken) {}

    /** 令牌响应体（供前端持久化 access + refresh）。 */
    public record TokenResponse(
            String accessToken, String refreshToken, String tokenType, long expiresIn) {
        static TokenResponse from(TokenPair pair) {
            return new TokenResponse(
                    pair.accessToken(), pair.refreshToken(), "Bearer", pair.expiresInSeconds());
        }
    }
}
