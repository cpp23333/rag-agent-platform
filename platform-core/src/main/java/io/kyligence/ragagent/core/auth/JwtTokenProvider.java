package io.kyligence.ragagent.core.auth;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * JWT Token 生成与验证
 */
@Slf4j
@Component
public class JwtTokenProvider {

    private final SecretKey accessTokenSecret;
    private final SecretKey refreshTokenSecret;
    private final long accessTokenValidityMinutes;
    private final long refreshTokenValidityDays;

    public JwtTokenProvider(
            @Value("${app.jwt.access-token-secret}") String accessTokenSecret,
            @Value("${app.jwt.refresh-token-secret}") String refreshTokenSecret,
            @Value("${app.jwt.access-token-validity-minutes:60}") long accessTokenValidityMinutes,
            @Value("${app.jwt.refresh-token-validity-days:30}") long refreshTokenValidityDays) {
        this.accessTokenSecret = Keys.hmacShaKeyFor(accessTokenSecret.getBytes(StandardCharsets.UTF_8));
        this.refreshTokenSecret = Keys.hmacShaKeyFor(refreshTokenSecret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenValidityMinutes = accessTokenValidityMinutes;
        this.refreshTokenValidityDays = refreshTokenValidityDays;
    }

    /**
     * 生成 Access Token（1 小时）
     */
    public String generateAccessToken(String userId, String workspaceId, Role role) {
        Instant now = Instant.now();
        Instant expiry = now.plus(accessTokenValidityMinutes, ChronoUnit.MINUTES);

        return Jwts.builder()
                .subject(userId)
                .claim("workspaceId", workspaceId)
                .claim("role", role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(accessTokenSecret)
                .compact();
    }

    /**
     * 生成 Refresh Token（30 天）
     */
    public String generateRefreshToken(String userId) {
        Instant now = Instant.now();
        Instant expiry = now.plus(refreshTokenValidityDays, ChronoUnit.DAYS);

        return Jwts.builder()
                .subject(userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(refreshTokenSecret)
                .compact();
    }

    /**
     * 验证并解析 Access Token
     */
    public Claims validateAccessToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(accessTokenSecret)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new PlatformException(ErrorCode.TOKEN_EXPIRED, "Access token expired");
        } catch (JwtException e) {
            log.warn("Invalid access token: {}", e.getMessage());
            throw new PlatformException(ErrorCode.TOKEN_INVALID, "Invalid access token");
        }
    }

    /**
     * 验证并解析 Refresh Token
     */
    public Claims validateRefreshToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(refreshTokenSecret)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new PlatformException(ErrorCode.TOKEN_EXPIRED, "Refresh token expired");
        } catch (JwtException e) {
            log.warn("Invalid refresh token: {}", e.getMessage());
            throw new PlatformException(ErrorCode.TOKEN_INVALID, "Invalid refresh token");
        }
    }

    /**
     * 从 Claims 提取 userId
     */
    public String getUserId(Claims claims) {
        return claims.getSubject();
    }

    /**
     * 从 Claims 提取 workspaceId
     */
    public String getWorkspaceId(Claims claims) {
        return claims.get("workspaceId", String.class);
    }

    /**
     * 从 Claims 提取 role
     */
    public Role getRole(Claims claims) {
        String roleName = claims.get("role", String.class);
        return Role.valueOf(roleName);
    }
}
