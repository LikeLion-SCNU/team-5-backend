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
import java.util.UUID;

@Entity
@Table(
        name = "plan_actions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_plan_actions_plan_position",
                columnNames = {"plan_id", "position"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlanAction {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "action_type", nullable = false)
    private String actionType;

    @Column(name = "target_minutes", nullable = false)
    private int targetMinutes;

    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "rule_id")
    private UUID ruleId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;
}
