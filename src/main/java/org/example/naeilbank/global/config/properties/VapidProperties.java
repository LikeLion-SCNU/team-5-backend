package org.example.naeilbank.global.config.properties;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "web-push.vapid")
public record VapidProperties(
        @NotBlank String publicKey,
        @NotBlank String privateKey,
        @NotBlank String subject
) {

    @AssertTrue(message = "subject must start with mailto: or https://")
    public boolean isSubjectContactUri() {
        return subject != null && (subject.startsWith("mailto:") || subject.startsWith("https://"));
    }
}
