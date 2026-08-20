package org.example.naeilbank.domain.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "protection_proposals",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_protection_proposals_user_idempotency",
                columnNames = {"user_id", "idempotency_key"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProtectionProposal {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.proposed;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    public static ProtectionProposal proposed(UUID userId, String idempotencyKey, Instant now) {
        ProtectionProposal proposal = new ProtectionProposal();
        proposal.userId = userId;
        proposal.status = Status.proposed;
        proposal.idempotencyKey = idempotencyKey;
        proposal.createdAt = now;
        return proposal;
    }

    public void accept(Instant now) {
        this.status = Status.accepted;
        this.respondedAt = now;
    }

    public void decline(Instant now) {
        this.status = Status.declined;
        this.respondedAt = now;
    }

    public enum Status {
        proposed,
        accepted,
        declined,
        expired,
        cancelled
    }
}
