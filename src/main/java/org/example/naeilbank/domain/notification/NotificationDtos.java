package org.example.naeilbank.domain.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

public final class NotificationDtos {
    private NotificationDtos() {
    }

    public record PushKeys(
            @NotBlank String p256dh,
            @NotBlank String auth
    ) {
    }

    public record SubscriptionRequest(
            @NotBlank @Size(max = 2048) String endpoint,
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
            boolean enabled,
            @NotBlank String timezone,
            @NotNull LocalTime morningTime
    ) {
    }

    public record PreferenceResponse(
            boolean enabled,
            String timezone,
            LocalTime morningTime
    ) {
    }

    public record PublicKeyResponse(
            @Pattern(regexp = ".*") String publicKey
    ) {
    }
}
