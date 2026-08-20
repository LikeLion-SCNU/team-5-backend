package org.example.naeilbank.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "meal_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MealItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "meal_record_id", nullable = false)
    private UUID mealRecordId;

    @Column(name = "rule_id")
    private UUID ruleId;

    @Column(name = "food_name", nullable = false)
    private String foodName;

    @Column(name = "portion")
    private String portion;

    @Column(name = "est_minutes", nullable = false)
    private int estMinutes;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    @Column(name = "is_user_added", nullable = false)
    private boolean userAdded;
}
