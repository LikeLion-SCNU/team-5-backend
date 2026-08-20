package org.example.naeilbank.domain.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.model.entity.AuditEvent;
import org.example.naeilbank.domain.model.entity.Consent;
import org.example.naeilbank.domain.model.repository.AuditEventRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditAppendService {
    private static final String CONSENT_CHANGED = "CONSENT_CHANGED";
    private static final String CONSENT_SUBJECT = "CONSENT";

    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;

    public Optional<ConsentReplay> findConsentReplay(UUID userId, String requestKeyHash) {
        return auditEventRepository.findConsentChangeByRequestKeyHash(userId, requestKeyHash)
                .map(this::toConsentReplay);
    }

    public void appendConsentChange(
            UUID userId,
            Consent consent,
            String requestKeyHash,
            String requestFingerprint
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("purpose", consent.getPurpose().name());
        details.put("action", consent.isGranted() ? "GRANTED" : "WITHDRAWN");
        details.put("requestKeyHash", requestKeyHash);
        details.put("requestFingerprint", requestFingerprint);
        details.put("resultVersion", consent.resourceVersion());
        auditEventRepository.save(new AuditEvent(
                userId,
                CONSENT_CHANGED,
                CONSENT_SUBJECT,
                consent.getId(),
                writeJson(details)
        ));
    }

    public void appendEvidenceMutation(
            UUID adminId,
            String action,
            UUID subjectId,
            UUID logicalKey,
            int versionNumber,
            boolean active,
            long resourceVersion
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", action);
        details.put("logicalKey", logicalKey);
        details.put("versionNumber", versionNumber);
        details.put("active", active);
        details.put("resourceVersion", resourceVersion);
        auditEventRepository.save(new AuditEvent(
                adminId,
                "EVIDENCE_MUTATED",
                action.startsWith("SOURCE_") ? "SOURCE" : "CONVERSION_RULE",
                subjectId,
                writeJson(details)
        ));
    }

    public void appendFaceDeletion(UUID userId, String subjectType, UUID subjectId) {
        auditEventRepository.save(new AuditEvent(
                userId,
                "FACE_DATA_DELETED",
                subjectType,
                subjectId,
                writeJson(Map.of("action", "DELETED"))
        ));
    }

    private ConsentReplay toConsentReplay(AuditEvent event) {
        try {
            JsonNode detail = objectMapper.readTree(event.getDetailJson());
            return new ConsentReplay(
                    event.getSubjectId(),
                    requiredText(detail, "requestFingerprint")
            );
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("Stored consent audit detail is invalid", e);
        }
    }

    private String writeJson(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize consent audit detail", e);
        }
    }

    private String requiredText(JsonNode detail, String field) {
        JsonNode value = detail.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Missing audit detail field: " + field);
        }
        return value.textValue();
    }

    public record ConsentReplay(UUID consentId, String requestFingerprint) {
    }
}
