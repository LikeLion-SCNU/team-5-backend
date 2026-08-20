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
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
        name = "notification_attempts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notification_attempts_subscription_date_type",
                columnNames = {"subscription_id", "local_date", "type"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "local_date", nullable = false)
    private LocalDate localDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.pending;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    public static NotificationAttempt pending(UUID userId, UUID subscriptionId, LocalDate localDate, Type type, Instant now) {
        NotificationAttempt attempt = new NotificationAttempt();
        attempt.userId = userId;
        attempt.subscriptionId = subscriptionId;
        attempt.localDate = localDate;
        attempt.type = type;
        attempt.status = Status.pending;
        attempt.attemptCount = 0;
        attempt.nextAttemptAt = now;
        attempt.createdAt = now;
        return attempt;
    }

    public void markProcessing() {
        this.status = Status.processing;
    }

    public void markSent() {
        this.status = Status.sent;
        this.nextAttemptAt = null;
    }

    public void markRetry(Instant nextAttemptAt) {
        this.status = Status.retry;
        this.attemptCount++;
        this.nextAttemptAt = nextAttemptAt;
    }

    public void markFailed() {
        this.status = Status.failed;
        this.nextAttemptAt = null;
    }

    public void markCancelled() {
        this.status = Status.cancelled;
        this.nextAttemptAt = null;
    }

    public enum Type {
        morning_statement,
        plan_reminder,
        protection_alert
    }

    public enum Status {
        pending,
        processing,
        sent,
        retry,
        failed,
        cancelled
    }
}
