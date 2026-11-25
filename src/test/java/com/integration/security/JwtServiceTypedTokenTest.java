package com.integration.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 용도(typ)가 있는 토큰은 일반 API 접근 토큰으로 못 쓰고, 기기 토큰은 jti 로 알아봐야 함. */
class JwtServiceTypedTokenTest {

    private final JwtService jwt = new JwtService("test-secret-test-secret-test-secret-32bytes!", 60);

    @Test
    void normalTokenWorksAsAccessToken() {
        String t = jwt.generateToken("me@example.com");
        assertThat(jwt.extractEmail(t)).isEqualTo("me@example.com");
        assertThat(jwt.deviceJti(t)).isNull();
    }

    @Test
    void typedTokensAreRejectedAsAccessTokens() {
        String code = jwt.issueTyped("me@example.com", "mcp_code", Duration.ofMinutes(5), Map.of("cc", "x"));
        String refresh = jwt.issueTyped("me@example.com", "mcp_refresh", Duration.ofDays(1), Map.of());
        assertThrows(JwtException.class, () -> jwt.extractEmail(code));
        assertThrows(JwtException.class, () -> jwt.extractEmail(refresh));
    }

    @Test
    void parseTypedChecksTheType() {
        String refresh = jwt.issueTyped("me@example.com", "mcp_refresh", Duration.ofDays(1), Map.of());
        assertThat(jwt.parseTyped(refresh, "mcp_refresh").getSubject()).isEqualTo("me@example.com");
        assertThrows(JwtException.class, () -> jwt.parseTyped(refresh, "mcp_code"));
    }

    @Test
    void deviceTokenCarriesJti() {
        String dev = jwt.issueTyped("me@example.com", "device", Duration.ofDays(30), Map.of("jti", "abc-123"));
        assertThat(jwt.deviceJti(dev)).isEqualTo("abc-123");
        assertThrows(JwtException.class, () -> jwt.extractEmail(dev));
    }

    @Test
    void tamperedTokenIsRejected() {
        String t = jwt.generateToken("me@example.com");
        JwtService other = new JwtService("another-secret-another-secret-another-32b!!", 60);
        assertThrows(JwtException.class, () -> other.extractEmail(t));
    }
}
