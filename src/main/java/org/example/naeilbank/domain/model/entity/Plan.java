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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "plans")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Plan {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "title", nullable = false)
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actions_json", nullable = false, columnDefinition = "jsonb")
    private String actionsJson = "[]";

    @Column(name = "expected_weekly_minutes")
    private Integer expectedWeeklyMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.proposed;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "progress_days", nullable = false)
    private int progressDays;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    public static Plan proposed(UUID userId, String title, String actionsJson, int expectedWeeklyMinutes, Instant now) {
        Plan plan = new Plan();
        plan.userId = userId;
        plan.title = title;
        plan.actionsJson = actionsJson;
        plan.expectedWeeklyMinutes = expectedWeeklyMinutes;
        plan.status = Status.proposed;
        plan.progressDays = 0;
        plan.createdAt = now;
        return plan;
    }

    public void accept(LocalDate startDate, LocalDate endDate, Instant now) {
        if (endDate.isBefore(startDate) || !endDate.equals(startDate.plusDays(6))) {
            throw new IllegalArgumentException("an advisory plan must span exactly seven days");
        }
        this.status = Status.accepted;
        this.startDate = startDate;
        this.endDate = endDate;
        this.respondedAt = now;
    }

    public void reject(Instant now) {
        this.status = Status.rejected;
        this.respondedAt = now;
    }

    public void applyProgress(int progressDays, int completedMinutes) {
        if (status != Status.accepted && status != Status.completed) {
            throw new IllegalStateException("progress requires an accepted plan");
        }
        this.progressDays = progressDays;
        this.status = completedMinutes >= expectedWeeklyMinutes ? Status.completed : Status.accepted;
    }

    public enum Status {
        proposed,
        accepted,
        rejected,
        completed
    }
}
