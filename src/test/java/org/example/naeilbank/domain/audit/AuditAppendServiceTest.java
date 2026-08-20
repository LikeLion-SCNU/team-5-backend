package org.example.naeilbank.domain.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.naeilbank.domain.model.entity.AuditEvent;
import org.example.naeilbank.domain.model.entity.Consent;
import org.example.naeilbank.domain.model.repository.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditAppendServiceTest {
    @Mock
    AuditEventRepository auditEventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void consentAuditContainsOnlyNonSensitiveReplayMetadata() throws Exception {
        UUID userId = UUID.randomUUID();
        Consent consent = Consent.create(
                userId,
                Consent.Purpose.MEAL_AI,
                true,
                3,
                "a".repeat(64),
                Instant.parse("2026-01-01T00:00:00Z")
        );
        AuditAppendService service = new AuditAppendService(auditEventRepository, objectMapper);

        service.appendConsentChange(userId, consent, "key-hash", "fingerprint");

        ArgumentCaptor<AuditEvent> eventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventRepository).save(eventCaptor.capture());
        AuditEvent event = eventCaptor.getValue();
        JsonNode detail = objectMapper.readTree(event.getDetailJson());
        assertThat(event.getEventType()).isEqualTo("CONSENT_CHANGED");
        assertThat(detail.get("purpose").asText()).isEqualTo("MEAL_AI");
        assertThat(detail.get("requestKeyHash").asText()).isEqualTo("key-hash");
        assertThat(detail.has("textHash")).isFalse();
        assertThat(detail.has("idempotencyKey")).isFalse();
    }

    @Test
    void lookupParsesTenantScopedReplayMetadata() {
        UUID userId = UUID.randomUUID();
        UUID consentId = UUID.randomUUID();
        AuditEvent event = new AuditEvent(
                userId,
                "CONSENT_CHANGED",
                "CONSENT",
                consentId,
                "{\"requestFingerprint\":\"fingerprint\"}"
        );
        when(auditEventRepository.findConsentChangeByRequestKeyHash(userId, "key-hash"))
                .thenReturn(Optional.of(event));

        var replay = new AuditAppendService(auditEventRepository, objectMapper)
                .findConsentReplay(userId, "key-hash");

        assertThat(replay).contains(new AuditAppendService.ConsentReplay(consentId, "fingerprint"));
    }
}
