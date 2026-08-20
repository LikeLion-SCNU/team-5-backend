package org.example.naeilbank.domain.consent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.example.naeilbank.domain.model.entity.Consent;

import java.time.Instant;
import java.util.List;

public final class ConsentDtos {
    private ConsentDtos() {
    }

    public record ChangeRequest(
            @NotNull Boolean granted,
            @NotNull @Positive Integer consentVersion,
            @NotBlank
            @Pattern(regexp = "^[0-9a-fA-F]{64}$", message = "must be a SHA-256 hex digest")
            String textHash,
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank @Size(max = 128) String idempotencyKey
    ) {
    }

    public record ConsentStatus(
            Consent.Purpose purpose,
            boolean granted,
            Integer consentVersion,
            String textHash,
            Instant grantedAt,
            Instant revokedAt,
            Long resourceVersion,
            boolean replayed
    ) {
        public static ConsentStatus missing(Consent.Purpose purpose) {
            return new ConsentStatus(purpose, false, null, null, null, null, null, false);
        }

        public static ConsentStatus from(Consent consent, boolean replayed) {
            return new ConsentStatus(
                    consent.getPurpose(),
                    consent.isGranted(),
                    consent.getConsentVersion(),
                    consent.getTextHash(),
                    consent.getGrantedAt(),
                    consent.getRevokedAt(),
                    consent.resourceVersion(),
                    replayed
            );
        }
    }

    public record ConsentListResponse(List<ConsentStatus> consents) {
        public ConsentListResponse {
            consents = List.copyOf(consents);
        }
    }
}
