package org.example.naeilbank.global.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.config.properties.JwtProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    @Test
    void accessTokenExpiresAfterConfiguredThirtyMinutes() {
        String secret = "test-jwt-secret-fixture-32-bytes-minimum";
        JwtProperties properties = new JwtProperties(secret, Duration.ofMinutes(30), Duration.ofDays(14));
        Clock fixedClock = Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC);
        JwtTokenProvider provider = new JwtTokenProvider(properties, fixedClock);

        String token = provider.createToken(UUID.fromString("00000000-0000-0000-0000-000000000001"), "user@example.com", "ROLE_USER");

        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();
        assertThat(claims.getSubject()).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(claims.get("role", String.class)).isEqualTo("ROLE_USER");
        assertThat(claims.getIssuedAt().toInstant()).isEqualTo(fixedClock.instant());
        assertThat(claims.getExpiration().toInstant()).isEqualTo(fixedClock.instant().plus(Duration.ofMinutes(30)));
    }

    @Test
    void expiredAccessTokenIsDistinguishedFromMalformedToken() {
        String secret = "test-jwt-secret-fixture-32-bytes-minimum";
        JwtProperties properties = new JwtProperties(secret, Duration.ofMinutes(30), Duration.ofDays(14));
        Clock issuedAt = Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC);
        JwtTokenProvider issuer = new JwtTokenProvider(properties, issuedAt);
        String token = issuer.createToken(UUID.fromString("00000000-0000-0000-0000-000000000001"), "user@example.com", "USER");

        Clock expiredClock = Clock.fixed(Instant.parse("2026-08-20T00:31:00Z"), ZoneOffset.UTC);
        JwtTokenProvider verifier = new JwtTokenProvider(properties, expiredClock);

        assertThatThrownBy(() -> verifier.getAuthentication(token))
                .isInstanceOf(AuthException.class)
                .extracting("errorCode")
                .hasToString("ACCESS_TOKEN_EXPIRED");
        assertThatThrownBy(() -> verifier.getAuthentication("malformed"))
                .isInstanceOf(AuthException.class)
                .extracting("errorCode")
                .hasToString("INVALID_ACCESS_TOKEN");
    }
}
