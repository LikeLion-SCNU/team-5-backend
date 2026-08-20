package org.example.naeilbank.global.config.properties;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Base64;

@Validated
@ConfigurationProperties(prefix = "web-push")
public record WebPushEncryptionProperties(
        @NotBlank String encryptionKey
) {
    @AssertTrue(message = "encryption-key must be a Base64-encoded 256-bit key")
    public boolean isValidEncryptionKey() {
        if (encryptionKey == null) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(encryptionKey).length == 32;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
