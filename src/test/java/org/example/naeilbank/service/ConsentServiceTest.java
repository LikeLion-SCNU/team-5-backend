package org.example.naeilbank.service;

import org.example.naeilbank.domain.audit.AuditAppendService;
import org.example.naeilbank.domain.consent.ConsentDtos.ChangeRequest;
import org.example.naeilbank.domain.model.entity.Consent;
import org.example.naeilbank.domain.model.repository.ConsentRepository;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.entity.User;
import org.example.naeilbank.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConsentServiceTest {
    private static final String TEXT_HASH = "a".repeat(64);

    @Mock
    ConsentRepository consentRepository;
    @Mock
    AuditAppendService auditAppendService;
    @Mock
    UserRepository userRepository;

    ConsentService consentService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        consentService = new ConsentService(consentRepository, auditAppendService, userRepository, clock);
    }

    @Test
    void statusAlwaysReturnsFourIndependentPurposesWithoutPregranting() {
        UUID userId = UUID.randomUUID();
        when(consentRepository.findAllByUserId(userId)).thenReturn(List.of());

        var response = consentService.getStatuses(userId);

        assertThat(response.consents()).extracting(status -> status.purpose().name())
                .containsExactly("HEALTH_COLLECTION", "MEAL_AI", "FACE_AI", "NOTIFICATION");
        assertThat(response.consents()).allMatch(status -> !status.granted());
    }

    @Test
    void grantAppendsAuditAndSameRequestReplaysWithoutSecondWrite() {
        UUID userId = UUID.randomUUID();
        UUID consentId = UUID.randomUUID();
        ChangeRequest request = request(true, 1, 0, "grant-key");
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user()));
        when(auditAppendService.findConsentReplay(eq(userId), anyString()))
                .thenReturn(Optional.empty());
        when(consentRepository.findForUpdate(userId, Consent.Purpose.MEAL_AI))
                .thenReturn(Optional.empty());
        when(consentRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var granted = consentService.change(userId, Consent.Purpose.MEAL_AI, request);

        assertThat(granted.granted()).isTrue();
        ArgumentCaptor<String> fingerprint = ArgumentCaptor.forClass(String.class);
        verify(auditAppendService).appendConsentChange(
                any(), any(), anyString(), fingerprint.capture());
        Consent stored = Consent.create(
                userId, Consent.Purpose.MEAL_AI, true, 1, TEXT_HASH,
                Instant.parse("2026-01-01T00:00:00Z"));
        when(auditAppendService.findConsentReplay(any(), anyString())).thenReturn(Optional.of(
                new AuditAppendService.ConsentReplay(consentId, fingerprint.getValue())));
        when(consentRepository.findByIdAndUserId(consentId, userId)).thenReturn(Optional.of(stored));

        assertThat(consentService.change(userId, Consent.Purpose.MEAL_AI, request).replayed()).isTrue();
    }

    @Test
    void reusedKeyWithDifferentPayloadAndStaleVersionAreConflicts() {
        UUID userId = UUID.randomUUID();
        ChangeRequest request = request(false, 2, 0, "same-key");
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user()));
        when(auditAppendService.findConsentReplay(any(), anyString())).thenReturn(Optional.of(
                new AuditAppendService.ConsentReplay(UUID.randomUUID(), "different")));

        assertError(() -> consentService.change(userId, Consent.Purpose.FACE_AI, request),
                ErrorCode.IDEMPOTENCY_CONFLICT);

        when(auditAppendService.findConsentReplay(any(), anyString())).thenReturn(Optional.empty());
        Consent stored = Consent.create(
                userId, Consent.Purpose.FACE_AI, true, 2, TEXT_HASH,
                Instant.parse("2026-01-01T00:00:00Z"));
        when(consentRepository.findForUpdate(userId, Consent.Purpose.FACE_AI))
                .thenReturn(Optional.of(stored));

        assertError(() -> consentService.change(
                userId,
                Consent.Purpose.FACE_AI,
                new ChangeRequest(false, 2, TEXT_HASH, 3L, "new-key")
        ), ErrorCode.CONSENT_VERSION_CONFLICT);

        assertError(() -> consentService.change(
                userId,
                Consent.Purpose.FACE_AI,
                new ChangeRequest(false, 1, TEXT_HASH, 1L, "lower-text-version")
        ), ErrorCode.CONSENT_VERSION_CONFLICT);
    }

    private ChangeRequest request(boolean granted, int version, long expected, String key) {
        return new ChangeRequest(granted, version, TEXT_HASH, expected, key);
    }

    private User user() {
        return User.local("consent-" + UUID.randomUUID() + "@example.com", "hash");
    }

    private void assertError(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(AuthException.class)
                .extracting(exception -> ((AuthException) exception).getErrorCode())
                .isEqualTo(expected);
    }
}
