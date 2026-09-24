package com.info.platform.interfaces.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.TokenService;
import com.info.platform.domain.common.TokenService.TokenClaims;
import com.info.platform.domain.common.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 鉴权过滤器（接口层横切，置于 {@link TraceIdFilter} 之后）。
 *
 * <p>从 {@code Authorization: Bearer <token>} 解析并校验 access 令牌（签名/有效期合法且 {@code
 * tokenType=ACCESS}），校验通过则把 {@link UserContext.Principal}（含 userId）写入 ThreadLocal，供 T11 watchlist
 * 行级权限取数。 白名单（登录/换发/actuator）直接放行；令牌缺失/无效/过期/非 access 类型（refresh 令牌，P0-2）→ 401 + 1003（统一 Result 体）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(TokenService tokenService, ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (isWhitelisted(path)) {
            chain.doFilter(request, response);
            return;
        }

        String token = extractBearer(request);
        if (token == null) {
            reject(response, ErrorCode.TOKEN_INVALID, "未提供认证令牌");
            return;
        }
        try {
            TokenClaims claims = tokenService.verify(token);
            // P0-2（系统体检 20260924）：仅 access 令牌可调受保护 API——refresh 令牌（7 天长效）签名合法
            // 但 tokenType=REFRESH，须拒 401，防短期令牌设计被架空
            if (claims.tokenType() != TokenService.TokenType.ACCESS) {
                log.warn("鉴权失败: 非访问令牌被拒: path={}, tokenType={}", path, claims.tokenType());
                reject(response, ErrorCode.TOKEN_INVALID, "令牌类型不符，须为访问令牌");
                return;
            }
            UserContext.set(new UserContext.Principal(claims.userId(), claims.username()));
            try {
                chain.doFilter(request, response);
            } finally {
                UserContext.clear();
            }
        } catch (BusinessException ex) {
            log.warn("鉴权失败: path={}, code={}", path, ex.getErrorCode().getCode());
            reject(response, ex.getErrorCode(), ex.getDetail());
        }
    }

    /** 取 Bearer 令牌；缺失或格式不符返回 null。 */
    private static String extractBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /** 白名单路径：认证自身端点 + actuator，放行不过滤。 */
    private static boolean isWhitelisted(String path) {
        return path.startsWith("/api/v1/auth/login")
                || path.startsWith("/api/v1/auth/refresh")
                || path.startsWith("/actuator");
    }

    /** 以统一 Result 体回写 401（traceId 已由 TraceIdFilter 写入 MDC）。 */
    private void reject(HttpServletResponse response, ErrorCode code, String detail)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(code.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(code, detail)));
    }
}
