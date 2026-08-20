package org.example.naeilbank.global.config.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.scheduling")
public record SchedulingProperties(
        @NotBlank String morningStatementCron
) {
}
