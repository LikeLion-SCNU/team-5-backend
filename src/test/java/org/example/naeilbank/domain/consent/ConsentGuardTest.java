package org.example.naeilbank.domain.consent;

import org.example.naeilbank.domain.model.entity.Consent;
import org.example.naeilbank.domain.model.repository.ConsentRepository;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConsentGuardTest {
    @Mock
    ConsentRepository consentRepository;

    @Test
    void missingAndWithdrawnConsentAreDeniedImmediately() {
        UUID userId = UUID.randomUUID();
        ConsentGuard guard = new ConsentGuard(consentRepository);
        when(consentRepository.findByUserIdAndPurpose(userId, Consent.Purpose.FACE_AI))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.requireGranted(userId, Consent.Purpose.FACE_AI))
                .isInstanceOf(AuthException.class)
                .extracting(exception -> ((AuthException) exception).getErrorCode())
                .isEqualTo(ErrorCode.CONSENT_REQUIRED);

        Consent withdrawn = Consent.create(
                userId,
                Consent.Purpose.NOTIFICATION,
                false,
                1,
                "a".repeat(64),
                Instant.parse("2026-01-01T00:00:00Z")
        );
        when(consentRepository.findByUserIdAndPurpose(userId, Consent.Purpose.NOTIFICATION))
                .thenReturn(Optional.of(withdrawn));

        assertThatThrownBy(() -> guard.requireGranted(userId, Consent.Purpose.NOTIFICATION))
                .isInstanceOf(AuthException.class)
                .extracting(exception -> ((AuthException) exception).getErrorCode())
                .isEqualTo(ErrorCode.CONSENT_REQUIRED);
    }
}
