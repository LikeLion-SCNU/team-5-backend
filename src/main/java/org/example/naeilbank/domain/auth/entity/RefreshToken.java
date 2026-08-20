package org.example.naeilbank.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "previous_token_hash")
    private String previousTokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "reuse_detected_at")
    private Instant reuseDetectedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public RefreshToken(UUID userId, String tokenHash, UUID familyId, String previousTokenHash, Instant expiresAt, Instant now) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.previousTokenHash = previousTokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = now;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isActive(Instant now) {
        return revokedAt == null && usedAt == null && isExpired(now) == false;
    }

    public void markRotated(Instant now) {
        this.usedAt = now;
        this.revokedAt = now;
    }

    public void revoke(Instant now) {
        if (this.revokedAt == null) {
            this.revokedAt = now;
        }
    }

    public void markReuseDetected(Instant now) {
        this.reuseDetectedAt = now;
        revoke(now);
    }
}
