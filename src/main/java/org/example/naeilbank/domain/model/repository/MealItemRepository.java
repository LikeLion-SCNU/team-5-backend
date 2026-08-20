package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.entity.MealItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MealItemRepository extends JpaRepository<MealItem, UUID> {
    List<MealItem> findByMealRecordIdAndDeletedFalse(UUID mealRecordId);
}
