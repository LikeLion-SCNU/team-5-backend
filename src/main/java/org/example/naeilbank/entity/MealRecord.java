package org.example.naeilbank.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "meal_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MealRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "record_date", nullable = false)
    private LocalDate recordDate;

    @Column(name = "media_blob_id", nullable = false)
    private UUID mediaBlobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MealStatus status = MealStatus.analyzing;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public MealRecord(UUID userId, LocalDate recordDate, UUID mediaBlobId, Instant createdAt) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.recordDate = Objects.requireNonNull(recordDate, "recordDate");
        this.mediaBlobId = Objects.requireNonNull(mediaBlobId, "mediaBlobId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.status = MealStatus.analyzing;
    }

    public void markPendingConfirm() {
        if (status != MealStatus.analyzing) {
            throw new IllegalStateException("meal record is not analyzing");
        }
        this.status = MealStatus.pending_confirm;
    }

    public void confirm(Instant confirmedAt) {
        if (status != MealStatus.pending_confirm) {
            throw new IllegalStateException("meal record is not pending confirmation");
        }
        this.status = MealStatus.confirmed;
        this.confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt");
    }

    public void exclude(Instant excludedAt) {
        if (status == MealStatus.confirmed) {
            throw new IllegalStateException("confirmed meal cannot be excluded");
        }
        this.status = MealStatus.excluded;
        this.confirmedAt = Objects.requireNonNull(excludedAt, "excludedAt");
    }
}
