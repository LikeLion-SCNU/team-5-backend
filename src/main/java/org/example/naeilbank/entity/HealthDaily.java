package org.example.naeilbank.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "health_daily",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_health_daily_user_date",
                        columnNames = {"user_id", "record_date"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HealthDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "record_date", nullable = false)
    private LocalDate recordDate;

    @Column(name = "sleep_minutes")
    private Integer sleepMinutes; // 누락 시 null

    @Column(name = "steps")
    private Integer steps;

    @Column(name = "moderate_activity_minutes")
    private Integer moderateActivityMinutes;

    @Column(name = "screen_minutes")
    private Integer screenMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false)
    private SyncStatus syncStatus = SyncStatus.synced;

    public HealthDaily(UUID userId, LocalDate recordDate) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.recordDate = Objects.requireNonNull(recordDate, "recordDate");
    }

    public void mergeMissing(Integer sleepMinutes, Integer steps,
                             Integer moderateActivityMinutes, Integer screenMinutes) {
        this.sleepMinutes = merge(this.sleepMinutes, sleepMinutes);
        this.steps = merge(this.steps, steps);
        this.moderateActivityMinutes = merge(this.moderateActivityMinutes, moderateActivityMinutes);
        this.screenMinutes = merge(this.screenMinutes, screenMinutes);
        this.syncStatus = syncStatus(this.sleepMinutes, this.steps,
                this.moderateActivityMinutes, this.screenMinutes);
    }

    private Integer merge(Integer current, Integer incoming) {
        return current == null ? incoming : current;
    }

    private SyncStatus syncStatus(Integer sleepMinutes, Integer steps,
                                  Integer moderateActivityMinutes, Integer screenMinutes) {
        int present = 0;
        if (sleepMinutes != null) {
            present++;
        }
        if (steps != null) {
            present++;
        }
        if (moderateActivityMinutes != null) {
            present++;
        }
        if (screenMinutes != null) {
            present++;
        }
        if (present == 0) {
            return SyncStatus.missing;
        }
        return present == 4 ? SyncStatus.synced : SyncStatus.partial;
    }

    public enum SyncStatus {
        synced,
        partial,
        missing
    }
}
