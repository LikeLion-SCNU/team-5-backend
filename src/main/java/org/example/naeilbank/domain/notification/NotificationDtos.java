package org.example.naeilbank.domain.notification;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

public final class NotificationDtos {
    private NotificationDtos() {
    }

    public record PushKeys(
            @NotBlank @Size(max = 128) String p256dh,
            @NotBlank @Size(max = 32) String auth
    ) {
    }

    public record SubscriptionRequest(
            @NotBlank @Size(max = 2048)
            @Pattern(regexp = "https://.+", message = "endpoint must use HTTPS") String endpoint,
            @NotNull PushKeys keys,
            Instant expirationTime
    ) {
    }

    public record SubscriptionResponse(
            UUID id,
            boolean active,
            Instant expirationTime
    ) {
    }

    public record PreferenceRequest(
            @NotNull Boolean enabled,
            @NotBlank @Size(max = 64) String timezone,
            @NotNull @JsonFormat(pattern = "HH:mm", lenient = OptBoolean.FALSE) LocalTime morningTime
    ) {
        @AssertTrue(message = "timezone must be a valid IANA time zone")
        public boolean isTimezoneValid() {
            if (timezone == null || timezone.isBlank()) {
                return true;
            }
            try {
                ZoneId.of(timezone);
                return true;
            } catch (DateTimeException e) {
                return false;
            }
        }

        @AssertTrue(message = "morningTime must have minute precision")
        public boolean isMorningTimeMinutePrecision() {
            return morningTime == null || (morningTime.getSecond() == 0 && morningTime.getNano() == 0);
        }
    }

    public record PreferenceResponse(
            boolean enabled,
            String timezone,
            @JsonFormat(pattern = "HH:mm") LocalTime morningTime
    ) {
    }

    public record PublicKeyResponse(
            String publicKey
    ) {
    }
}
