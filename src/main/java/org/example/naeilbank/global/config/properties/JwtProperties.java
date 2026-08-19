package org.example.naeilbank.global.config.properties;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        @NotBlank @Size(min = 32) String secret,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl
) {

    @AssertTrue(message = "access-token-ttl must be 30 minutes")
    public boolean isAccessTokenTtlThirtyMinutes() {
        return Duration.ofMinutes(30).equals(accessTokenTtl);
    }

    @AssertTrue(message = "refresh-token-ttl must be 14 days")
    public boolean isRefreshTokenTtlFourteenDays() {
        return Duration.ofDays(14).equals(refreshTokenTtl);
    }
}
