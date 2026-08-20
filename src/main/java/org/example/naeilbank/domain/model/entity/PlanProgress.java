package org.example.naeilbank.domain.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
        name = "plan_progress",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_plan_progress_plan_date",
                columnNames = {"plan_id", "progress_date"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlanProgress {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "progress_date", nullable = false)
    private LocalDate progressDate;

    @Column(name = "completed_minutes", nullable = false)
    private int completedMinutes;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    public static PlanProgress create(UUID planId, LocalDate progressDate, int completedMinutes, Instant now) {
        PlanProgress progress = new PlanProgress();
        progress.planId = planId;
        progress.progressDate = progressDate;
        progress.completedMinutes = completedMinutes;
        progress.createdAt = now;
        return progress;
    }
}
