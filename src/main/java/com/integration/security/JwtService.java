package com.integration.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import io.jsonwebtoken.JwtException;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMinutes;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                       @Value("${app.jwt.expiration-minutes}") long expirationMinutes) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            // Fail fast at startup instead of a cryptic WeakKeyException on the first login.
            throw new IllegalStateException(
                    "app.jwt.secret must be at least 32 bytes (256 bits) for HS256; got " + bytes.length);
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expirationMinutes = expirationMinutes;
    }

    public String generateToken(String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(expirationMinutes))))
                .signWith(key)
                .compact();
    }

    /** 수명을 정해 접근 토큰 발급(MCP 커넥터용 짧은 토큰 등). */
    public String generateToken(String email, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder().subject(email).issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl))).signWith(key).compact();
    }

    /**
     * 용도가 정해진 토큰(typ 클레임). OAuth 인가 코드·갱신 토큰처럼 접근 토큰으로 쓰면 안 되는 것들.
     * typ 가 있는 토큰은 {@link #extractEmail} 이 거부하므로 API 호출에는 못 씁니다.
     */
    public String issueTyped(String email, String typ, Duration ttl, Map<String, ?> extra) {
        Instant now = Instant.now();
        var b = Jwts.builder().subject(email).claim("typ", typ);
        if (extra != null) b.claims(extra);
        return b.issuedAt(Date.from(now)).expiration(Date.from(now.plus(ttl))).signWith(key).compact();
    }

    /** typ 가 맞는 토큰만 통과. 아니면 JwtException. */
    public Claims parseTyped(String token, String typ) {
        Claims c = claims(token);
        if (!typ.equals(c.get("typ", String.class))) throw new JwtException("토큰 용도가 맞지 않습니다");
        return c;
    }

    /** Throws io.jsonwebtoken.JwtException (or IllegalArgumentException) if invalid/expired. */
    public String extractEmail(String token) {
        Claims c = claims(token);
        // 인가 코드·갱신 토큰(typ 있음)은 접근 토큰으로 쓸 수 없음
        if (c.get("typ") != null) throw new JwtException("접근 토큰이 아닙니다");
        return c.getSubject();
    }

    /**
     * True once a token is past the halfway point of its lifetime.
     *
     * <p>Drives sliding expiration: {@link JwtAuthenticationFilter} hands the client a fresh
     * token on the next call, so someone actively using the app never gets logged out
     * mid-action, while a session that is genuinely abandoned still expires on schedule.
     * Renewing only in the second half keeps this from minting a new token on every request.
     */
    public boolean shouldRenew(String token) {
        try {
            Claims c = claims(token);
            Instant issued = c.getIssuedAt().toInstant();
            Instant expires = c.getExpiration().toInstant();
            Instant midpoint = issued.plus(Duration.between(issued, expires).dividedBy(2));
            return Instant.now().isAfter(midpoint);
        } catch (Exception e) {
            return false; // 갱신은 부가 기능 — 판단이 안 되면 그냥 갱신하지 않습니다
        }
    }

    private Claims claims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
