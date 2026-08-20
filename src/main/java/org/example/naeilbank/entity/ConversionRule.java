package org.example.naeilbank.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "conversion_rules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

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

    public enum HabitType {
        sleep,
        activity,
        screen_time,
        food,
        alcohol
    }
}
