package org.example.naeilbank.domain.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "notification_preferences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationPreference {
    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "timezone", nullable = false)
    private String timezone = "Asia/Seoul";

    @Column(name = "morning_time", nullable = false)
    private LocalTime morningTime = LocalTime.of(8, 0);

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;
}
