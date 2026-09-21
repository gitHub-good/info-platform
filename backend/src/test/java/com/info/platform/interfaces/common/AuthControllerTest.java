package com.info.platform.interfaces.common;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.common.AuthApplicationService;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.TokenService.TokenPair;
import com.info.platform.interfaces.common.AuthController.TokenResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * AuthController 切片测试（T17）：登录 200/凭证错误 401/限流 429/参数缺失 400、换发 200/令牌无效 401。 用 standaloneSetup 独立装配
 * MockMvc（不加载 Spring 上下文），AuthApplicationService 用 Mockito mock， {@link GlobalExceptionHandler} 作
 * ControllerAdvice。
 */
class AuthControllerTest {

    private MockMvc mockMvc;
    private AuthApplicationService authApplicationService;

    private static final TokenPair PAIR = new TokenPair("access-xyz", "refresh-uvw", 3600L);

    @BeforeEach
    void setUp() {
        authApplicationService = mock(AuthApplicationService.class);
        AuthController controller = new AuthController(authApplicationService);
        mockMvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void login_success_returns200WithTokenPair() throws Exception {
        when(authApplicationService.login(eq("admin"), eq("admin123"), eq("10.0.0.1")))
                .thenReturn(PAIR);

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-Forwarded-For", "10.0.0.1")
                                .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").value("access-xyz"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-uvw"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(3600));
    }

    @Test
    void login_badCredentials_returns401AndCode1001() throws Exception {
        when(authApplicationService.login(eq("admin"), eq("wrong"), eq("10.0.0.1")))
                .thenThrow(new BusinessException(ErrorCode.BAD_CREDENTIALS));

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-Forwarded-For", "10.0.0.1")
                                .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void login_rateLimited_returns429AndCode1002() throws Exception {
        when(authApplicationService.login(eq("admin"), eq("admin123"), eq("10.0.0.1")))
                .thenThrow(new BusinessException(ErrorCode.LOGIN_RATE_LIMITED));

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-Forwarded-For", "10.0.0.1")
                                .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void login_blankUsername_returns400AndCode2001() throws Exception {
        // @Valid @NotBlank 触发 → 2xxx 参数校验失败
        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"\",\"password\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void refresh_success_returns200WithNewPair() throws Exception {
        when(authApplicationService.refresh("refresh-uvw")).thenReturn(PAIR);

        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"refreshToken\":\"refresh-uvw\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").value("access-xyz"));
    }

    @Test
    void refresh_invalidToken_returns401AndCode1003() throws Exception {
        when(authApplicationService.refresh("bad-token"))
                .thenThrow(new BusinessException(ErrorCode.TOKEN_INVALID, "令牌已过期"));

        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"refreshToken\":\"bad-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    void refresh_blankToken_returns400AndCode2001() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    /** 静态引用避免编译器剔除 TokenResponse（其字段经 from 映射，覆盖率受益）。 */
    @SuppressWarnings("unused")
    private TokenResponse ignored;
}
