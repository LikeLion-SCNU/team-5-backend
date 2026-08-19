package org.example.naeilbank.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

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
public class HealthDaily extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "record_date", nullable = false)
    private LocalDate recordDate;

    @Column(name = "sleep_minutes")
    private Integer sleepMinutes; // 누락 시 null

    @Column(name = "step_count")
    private Integer stepCount; // 누락 시 null

    @Column(name = "heart_rate_avg")
    private Integer heartRateAvg; // 누락 시 null
}