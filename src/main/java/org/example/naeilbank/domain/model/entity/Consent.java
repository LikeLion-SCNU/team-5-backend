package org.example.naeilbank.domain.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "consents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Consent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false)
    private Purpose purpose;

    @Column(name = "granted", nullable = false)
    private boolean granted;

    @Column(name = "consent_version", nullable = false)
    private int consentVersion;

    @Column(name = "text_hash", nullable = false)
    private String textHash;

    @Column(name = "granted_at")
    private Instant grantedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    private Consent(
            UUID userId,
            Purpose purpose,
            boolean granted,
            int consentVersion,
            String textHash,
            Instant changedAt
    ) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        replace(granted, consentVersion, textHash, changedAt);
    }

    public static Consent create(
            UUID userId,
            Purpose purpose,
            boolean granted,
            int consentVersion,
            String textHash,
            Instant changedAt
    ) {
        return new Consent(userId, purpose, granted, consentVersion, textHash, changedAt);
    }

    public void replace(boolean granted, int consentVersion, String textHash, Instant changedAt) {
        if (consentVersion < 1) {
            throw new IllegalArgumentException("consentVersion must be positive");
        }
        if (textHash == null || textHash.isBlank()) {
            throw new IllegalArgumentException("textHash must not be blank");
        }
        Instant transitionTime = Objects.requireNonNull(changedAt, "changedAt");
        this.granted = granted;
        this.consentVersion = consentVersion;
        this.textHash = textHash;
        if (granted) {
            this.grantedAt = transitionTime;
            this.revokedAt = null;
        } else {
            this.revokedAt = transitionTime;
        }
    }

    public boolean matches(boolean granted, int consentVersion, String textHash) {
        return this.granted == granted
                && this.consentVersion == consentVersion
                && this.textHash.equals(textHash);
    }

    public long resourceVersion() {
        return Math.addExact(version, 1L);
    }

    public enum Purpose {
        HEALTH_COLLECTION,
        MEAL_AI,
        FACE_AI,
        NOTIFICATION
    }
}
