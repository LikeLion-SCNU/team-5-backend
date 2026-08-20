package org.example.naeilbank.global.config.properties;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.timezone")
public record TimezoneProperties(
        @NotBlank String defaultZone
) {
    private static final String REQUIRED_ZONE = "Asia/Seoul";

    @AssertTrue(message = "default-zone must be Asia/Seoul")
    public boolean isDefaultZoneKst() {
        return REQUIRED_ZONE.equals(defaultZone);
    }
}
