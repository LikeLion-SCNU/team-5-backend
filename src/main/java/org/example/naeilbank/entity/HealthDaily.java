package org.example.naeilbank.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
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

    @Column(name = "screen_minutes")
    private Integer screenMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false)
    private SyncStatus syncStatus = SyncStatus.synced;

    public enum SyncStatus {
        synced,
        partial,
        missing
    }
}
