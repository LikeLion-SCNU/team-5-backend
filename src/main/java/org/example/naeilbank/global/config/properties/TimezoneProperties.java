package org.example.naeilbank.global.config.properties;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.DateTimeException;
import java.time.ZoneId;

@Validated
@ConfigurationProperties(prefix = "app.timezone")
public record TimezoneProperties(
        @NotBlank String defaultZone
) {

    @AssertTrue(message = "default-zone must be a valid ZoneId")
    public boolean isDefaultZoneValid() {
        try {
            ZoneId.of(defaultZone);
            return true;
        } catch (DateTimeException ignored) {
            return false;
        }
    }
}
