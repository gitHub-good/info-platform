package com.info.platform.infrastructure.common;

import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.TokenService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link TokenService} 端口的 JJWT HS256 实现（基础设施层）。
 *
 * <p>access 短期 + refresh 长期，TTL 由 {@link AuthProperties.Jwt} 注入。签名密钥走环境变量 {@code ${JWT_SECRET}}（经
 * {@link AuthProperties} 绑定），构造期校验非空且 >=256 位（HS256 安全下限）， 缺失/过短即抛异常使上下文启动失败——fail-fast。
 */
@Component
public class JwtTokenService implements TokenService {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);

    private static final String CLAIM_UID = "uid";
    private static final String CLAIM_TYPE = "type";

    /** HS256 要求密钥 >= 256 位 = 32 字节。 */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final AuthProperties.Jwt jwtProps;

    public JwtTokenService(AuthProperties properties) {
        this.jwtProps = properties.getJwt();
        String secret = jwtProps.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "security.jwt.secret 未配置：请通过环境变量 JWT_SECRET 注入（>=32 字节随机串），生产不可缺失");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "security.jwt.secret 过短：HS256 要求 >=32 字节，当前 " + secretBytes.length + " 字节");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        log.info(
                "JwtTokenService 就绪: accessTTL={}s, refreshTTL={}s",
                jwtProps.getAccessTokenTtl().getSeconds(),
                jwtProps.getRefreshTokenTtl().getSeconds());
    }

    @Override
    public TokenPair issue(long userId, String username) {
        Instant now = Instant.now();
        String accessToken =
                build(userId, username, TokenType.ACCESS, now, jwtProps.getAccessTokenTtl());
        String refreshToken =
                build(userId, username, TokenType.REFRESH, now, jwtProps.getRefreshTokenTtl());
        return new TokenPair(accessToken, refreshToken, jwtProps.getAccessTokenTtl().getSeconds());
    }

    @Override
    public TokenClaims verify(String token) {
        try {
            Claims claims =
                    Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            long userId = readUserId(claims);
            String username = claims.getSubject();
            TokenType type = TokenType.valueOf(claims.get(CLAIM_TYPE, String.class));
            return new TokenClaims(userId, username, type);
        } catch (ExpiredJwtException e) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "令牌已过期");
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "令牌无效");
        }
    }

    private String build(
            long userId,
            String username,
            TokenType type,
            Instant issuedAt,
            java.time.Duration ttl) {
        return Jwts.builder()
                .subject(username)
                .claim(CLAIM_UID, userId)
                .claim(CLAIM_TYPE, type.name())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 健壮读取 uid：JSON 数字按需转 Integer/Long，统一回 long。 */
    private static long readUserId(Claims claims) {
        Object raw = claims.get(CLAIM_UID);
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw == null) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "令牌缺少用户标识");
        }
        return Long.parseLong(String.valueOf(raw));
    }
}
