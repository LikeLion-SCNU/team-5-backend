package org.example.naeilbank.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;
import java.time.Instant;

@Entity
@Table(name = "conversion_rules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "logical_key", nullable = false, updatable = false)
    private UUID logicalKey;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "habit_type", nullable = false)
    private HabitType habitType;

    @Column(name = "label", nullable = false)
    private String label;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "condition_json", nullable = false, columnDefinition = "jsonb")
    private String conditionJson = "{}";

    @Column(name = "minutes_delta", nullable = false)
    private int minutesDelta;

    @Column(name = "unit", nullable = false)
    private String unit = "per_unit";

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public static ConversionRule create(
            HabitType habitType,
            String label,
            String conditionJson,
            int minutesDelta,
            String unit,
            UUID sourceId,
            boolean active,
            Instant now
    ) {
        return version(null, UUID.randomUUID(), 1, habitType, label, conditionJson,
                minutesDelta, unit, sourceId, active, now);
    }

    public static ConversionRule nextVersion(
            ConversionRule previous,
            int versionNumber,
            HabitType habitType,
            String label,
            String conditionJson,
            int minutesDelta,
            String unit,
            UUID sourceId,
            boolean active,
            Instant now
    ) {
        return version(previous, previous.logicalKey, versionNumber, habitType, label,
                conditionJson, minutesDelta, unit, sourceId, active, now);
    }

    public void setActive(boolean active, Instant now) {
        this.active = active;
        this.updatedAt = now;
    }

    public void markVersioned(Instant now) {
        this.updatedAt = now;
    }

    public long resourceVersion() {
        return rowVersion + 1L;
    }

    private static ConversionRule version(
            ConversionRule previous,
            UUID logicalKey,
            int versionNumber,
            HabitType habitType,
            String label,
            String conditionJson,
            int minutesDelta,
            String unit,
            UUID sourceId,
            boolean active,
            Instant now
    ) {
        ConversionRule rule = new ConversionRule();
        rule.logicalKey = logicalKey;
        rule.versionNumber = versionNumber;
        rule.habitType = habitType;
        rule.label = label;
        rule.conditionJson = conditionJson;
        rule.minutesDelta = minutesDelta;
        rule.unit = unit;
        rule.sourceId = sourceId;
        rule.active = active;
        rule.createdAt = now;
        rule.updatedAt = now;
        return rule;
    }

    public enum HabitType {
        sleep,
        activity,
        screen_time,
        food,
        alcohol
    }
}
