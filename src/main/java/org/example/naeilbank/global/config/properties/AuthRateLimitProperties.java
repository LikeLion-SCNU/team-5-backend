package org.example.naeilbank.global.config.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.auth.rate-limit")
public record AuthRateLimitProperties(
        @Min(1) int capacity,
        @NotNull Duration window,
        @Min(1) int maxBuckets
) {
}
