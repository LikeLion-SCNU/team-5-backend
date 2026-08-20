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

    /** 생성 완료 시 원본 사진을 파기하면서 함께 비운다. */
    @Column(name = "source_media_id")
    private UUID sourceMediaId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

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

    public void markProcessing(UUID claimToken, Instant now) {
        this.status = Status.processing;
        this.processingStartedAt = Objects.requireNonNull(now, "now");
        this.claimToken = Objects.requireNonNull(claimToken, "claimToken");
        this.attemptCount += 1;
        this.failureReason = null;
    }

    public boolean matchesClaim(UUID expectedClaimToken) {
        return status == Status.processing && Objects.equals(claimToken, expectedClaimToken);
    }

    public boolean isDue(Instant now, Instant staleBefore) {
        return ((status == Status.queued || status == Status.generating)
                && !nextAttemptAt.isAfter(now))
                || (status == Status.processing
                && processingStartedAt != null
                && !processingStartedAt.isAfter(staleBefore));
    }

    public void scheduleRetry(String reason, Instant retryAt) {
        this.status = Status.queued;
        this.nextAttemptAt = Objects.requireNonNull(retryAt, "retryAt");
        this.processingStartedAt = null;
        this.claimToken = null;
        this.failureReason = requireText(reason, "reason");
    }

    /** 결과 이미지가 저장된 뒤 원본 참조를 끊는다(원본 blob은 호출자가 삭제). */
    public void purgeSource() {
        this.sourceMediaId = null;
    }

    public void markDone(Instant now) {
        this.status = Status.done;
        this.completedAt = Objects.requireNonNull(now, "now");
        this.claimToken = null;
        this.failureReason = null;
    }

    public void markFailed(String reason, Instant now) {
        this.status = Status.failed;
        this.completedAt = Objects.requireNonNull(now, "now");
        this.claimToken = null;
        this.failureReason = requireText(reason, "reason");
    }

    public void markCancelled(Instant now) {
        this.status = Status.cancelled;
        this.cancelledAt = Objects.requireNonNull(now, "now");
        this.claimToken = null;
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
