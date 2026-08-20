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
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "face_simulations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_face_simulations_user_id_id",
                columnNames = {"user_id", "id"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FaceSimulation {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "trend_desc")
    private String trendDescription;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.generating;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "source_media_id", nullable = false)
    private UUID sourceMediaId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    public FaceSimulation(
            UUID userId,
            UUID sourceMediaId,
            String trendDescription,
            String idempotencyKey,
            String requestHash
    ) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.sourceMediaId = Objects.requireNonNull(sourceMediaId, "sourceMediaId");
        this.trendDescription = trendDescription;
        this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        this.requestHash = requireText(requestHash, "requestHash");
        this.status = Status.queued;
    }

    public boolean sameRequest(String requestHash) {
        return this.requestHash.equals(requestHash);
    }

    public boolean canCancel() {
        return status == Status.queued || status == Status.generating || status == Status.processing;
    }

    public void markProcessing(Instant now) {
        this.status = Status.processing;
        this.processingStartedAt = Objects.requireNonNull(now, "now");
        this.failureReason = null;
    }

    public void markDone(Instant now) {
        this.status = Status.done;
        this.completedAt = Objects.requireNonNull(now, "now");
        this.failureReason = null;
    }

    public void markFailed(String reason, Instant now) {
        this.status = Status.failed;
        this.completedAt = Objects.requireNonNull(now, "now");
        this.failureReason = requireText(reason, "reason");
    }

    public void markCancelled(Instant now) {
        this.status = Status.cancelled;
        this.cancelledAt = Objects.requireNonNull(now, "now");
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public enum Status {
        queued,
        processing,
        generating,
        done,
        failed,
        cancelled
    }
}
