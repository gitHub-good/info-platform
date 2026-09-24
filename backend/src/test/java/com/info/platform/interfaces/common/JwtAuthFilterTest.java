package com.info.platform.interfaces.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.TokenService;
import com.info.platform.domain.common.TokenService.TokenClaims;
import com.info.platform.domain.common.TokenService.TokenType;
import com.info.platform.domain.common.UserContext;
import jakarta.servlet.FilterChain;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * JwtAuthFilter 单测（T17）：白名单放行、有效 token 写入 UserContext（含 userId 供行级权限）、 无效/过期 token→401/1003、缺失
 * token→401/1003。 直接调用过滤器（不启 Spring 上下文），TokenService 用 Mockito mock。
 */
class JwtAuthFilterTest {

    private TokenService tokenService;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        UserContext.clear(); // 防 ThreadLocal 跨用例串味
        tokenService = mock(TokenService.class);
        filter = new JwtAuthFilter(tokenService, new ObjectMapper());
    }

    @Test
    void whitelistPath_passesThroughWithoutTokenCheck() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/auth/login");
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean[] forwarded = {false};
        FilterChain chain = (r, s) -> forwarded[0] = true;

        filter.doFilter(req, res, chain);

        assertThat(forwarded[0]).isTrue();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(UserContext.get()).isNull();
    }

    @Test
    void actuatorPath_isWhitelisted() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean[] forwarded = {false};
        FilterChain chain = (r, s) -> forwarded[0] = true;

        filter.doFilter(req, res, chain);

        assertThat(forwarded[0]).isTrue();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void validToken_setsUserContextWithUserId() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/subjects/1/detail");
        req.addHeader("Authorization", "Bearer valid.jwt");
        MockHttpServletResponse res = new MockHttpServletResponse();
        when(tokenService.verify(eq("valid.jwt")))
                .thenReturn(new TokenClaims(7L, "alice", TokenType.ACCESS));

        UserContext.Principal[] captured = {null};
        FilterChain chain = (r, s) -> captured[0] = UserContext.get(); // 过滤器已 set，下游可取 userId

        filter.doFilter(req, res, chain);

        assertThat(captured[0]).isNotNull();
        assertThat(captured[0].userId()).isEqualTo(7L);
        assertThat(captured[0].username()).isEqualTo("alice");
        assertThat(res.getStatus()).isEqualTo(200);
        // finally 清理：过滤器返回后 ThreadLocal 必须清空（防线程池复用串味）
        assertThat(UserContext.get()).isNull();
    }

    @Test
    void missingToken_returns401AndCode1003() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/subjects/1/detail");
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean[] forwarded = {false};
        FilterChain chain = (r, s) -> forwarded[0] = true;

        filter.doFilter(req, res, chain);

        assertThat(forwarded[0]).isFalse(); // 被拦截，未进入下游
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(bodyCode(res)).isEqualTo(1003);
    }

    @Test
    void overviewPath_requiresToken_t42() throws Exception {
        // T42 概览聚合接口不在白名单：缺 token → 401/1003（不进下游）
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/overview");
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean[] forwarded = {false};
        FilterChain chain = (r, s) -> forwarded[0] = true;

        filter.doFilter(req, res, chain);

        assertThat(forwarded[0]).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(bodyCode(res)).isEqualTo(1003);
    }

    @Test
    void malformedAuthorizationHeader_returns401AndCode1003() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/subjects/1/detail");
        req.addHeader("Authorization", "Basic xyz"); // 非 Bearer
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean[] forwarded = {false};
        FilterChain chain = (r, s) -> forwarded[0] = true;

        filter.doFilter(req, res, chain);

        assertThat(forwarded[0]).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(bodyCode(res)).isEqualTo(1003);
    }

    @Test
    void refreshToken_rejectedWith401AndCode1003() throws Exception {
        // P0-2 回归（系统体检 20260924）：7 天 refresh 令牌不得当 access 令牌调用受保护 API——
        // verify 通过（签名/有效期合法）但 tokenType=REFRESH，必须拒 401/1003，不放行进下游
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/subjects/1/detail");
        req.addHeader("Authorization", "Bearer refresh.jwt");
        MockHttpServletResponse res = new MockHttpServletResponse();
        when(tokenService.verify(eq("refresh.jwt")))
                .thenReturn(new TokenClaims(7L, "alice", TokenType.REFRESH));
        boolean[] forwarded = {false};
        FilterChain chain = (r, s) -> forwarded[0] = true;

        filter.doFilter(req, res, chain);

        assertThat(forwarded[0]).isFalse(); // 被拦截，未进入下游（1h 短期令牌设计不被架空）
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(bodyCode(res)).isEqualTo(1003);
        assertThat(UserContext.get()).isNull();
    }

    @Test
    void invalidToken_returns401AndCode1003() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/subjects/1/detail");
        req.addHeader("Authorization", "Bearer expired.or.bad");
        MockHttpServletResponse res = new MockHttpServletResponse();
        when(tokenService.verify(eq("expired.or.bad")))
                .thenThrow(new BusinessException(ErrorCode.TOKEN_INVALID, "令牌已过期"));
        boolean[] forwarded = {false};
        FilterChain chain = (r, s) -> forwarded[0] = true;

        filter.doFilter(req, res, chain);

        assertThat(forwarded[0]).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(bodyCode(res)).isEqualTo(1003);
        assertThat(UserContext.get()).isNull();
    }

    @Test
    void sseStream_queryToken_authenticatesAndForwards() throws Exception {
        // P1-1 通知中心：EventSource 无法带 Authorization 头 → /notifications/stream 接受 access_token 查询参数
        MockHttpServletRequest req =
                new MockHttpServletRequest("GET", "/api/v1/notifications/stream");
        req.setQueryString("access_token=valid.jwt");
        req.setParameter("access_token", "valid.jwt");
        MockHttpServletResponse res = new MockHttpServletResponse();
        when(tokenService.verify(eq("valid.jwt")))
                .thenReturn(new TokenClaims(7L, "alice", TokenType.ACCESS));
        UserContext.Principal[] captured = {null};
        FilterChain chain = (r, s) -> captured[0] = UserContext.get();

        filter.doFilter(req, res, chain);

        assertThat(captured[0]).isNotNull(); // 鉴权通过进入下游
        assertThat(captured[0].userId()).isEqualTo(7L);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void sseStream_queryTokenInvalid_returns401AndCode1003() throws Exception {
        MockHttpServletRequest req =
                new MockHttpServletRequest("GET", "/api/v1/notifications/stream");
        req.setParameter("access_token", "expired.jwt");
        MockHttpServletResponse res = new MockHttpServletResponse();
        when(tokenService.verify(eq("expired.jwt")))
                .thenThrow(new BusinessException(ErrorCode.TOKEN_INVALID, "令牌已过期"));
        boolean[] forwarded = {false};
        FilterChain chain = (r, s) -> forwarded[0] = true;

        filter.doFilter(req, res, chain);

        assertThat(forwarded[0]).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(bodyCode(res)).isEqualTo(1003);
    }

    @Test
    void nonStreamPath_queryTokenOnly_stillRejected() throws Exception {
        // query token 仅限 SSE 握手端点：其余端点无头 → 401（token 不进访问日志的暴露面不扩大）
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/notifications");
        req.setParameter("access_token", "valid.jwt");
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean[] forwarded = {false};
        FilterChain chain = (r, s) -> forwarded[0] = true;

        filter.doFilter(req, res, chain);

        assertThat(forwarded[0]).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(bodyCode(res)).isEqualTo(1003);
    }

    @Test
    void sseStream_headerTakesPrecedenceOverQueryToken() throws Exception {
        // 头优先：头与 query 同传时验头（query 是回退不是旁路）
        MockHttpServletRequest req =
                new MockHttpServletRequest("GET", "/api/v1/notifications/stream");
        req.addHeader("Authorization", "Bearer header.jwt");
        req.setParameter("access_token", "query.jwt");
        MockHttpServletResponse res = new MockHttpServletResponse();
        when(tokenService.verify(eq("header.jwt")))
                .thenReturn(new TokenClaims(7L, "alice", TokenType.ACCESS));
        boolean[] forwarded = {false};
        FilterChain chain = (r, s) -> forwarded[0] = true;

        filter.doFilter(req, res, chain);

        assertThat(forwarded[0]).isTrue();
        verify(tokenService).verify(eq("header.jwt"));
        verify(tokenService, never()).verify(eq("query.jwt"));
    }

    @SuppressWarnings("unchecked")
    private static int bodyCode(MockHttpServletResponse res) throws Exception {
        Map<String, Object> body =
                new ObjectMapper().readValue(res.getContentAsByteArray(), Map.class);
        return ((Number) body.get("code")).intValue();
    }
}
