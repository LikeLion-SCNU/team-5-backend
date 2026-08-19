package org.example.naeilbank.global.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.example.naeilbank.global.config.properties.JwtProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    @Test
    void accessTokenExpiresAfterConfiguredThirtyMinutes() {
        String secret = "test-jwt-secret-fixture-32-bytes-minimum";
        JwtProperties properties = new JwtProperties(secret, Duration.ofMinutes(30), Duration.ofDays(14));
        Clock fixedClock = Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC);
        JwtTokenProvider provider = new JwtTokenProvider(properties, fixedClock);

        String token = provider.createToken(1L, "user@example.com", "ROLE_USER");

        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();
        assertThat(claims.getIssuedAt().toInstant()).isEqualTo(fixedClock.instant());
        assertThat(claims.getExpiration().toInstant()).isEqualTo(fixedClock.instant().plus(Duration.ofMinutes(30)));
    }
}
