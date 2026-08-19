package org.example.naeilbank.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "meal_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MealItem extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meal_record_id", nullable = false)
    private MealRecord mealRecord;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id")
    private ConversionRule conversionRule;

    @Column(name = "food_name", nullable = false)
    private String foodName;

    @Column(name = "est_minutes", nullable = false)
    private Integer estMinutes = 0; // 환산 예정 영향 (분)

    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted = false;
}