package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.entity.MealRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface MealRecordRepository extends JpaRepository<MealRecord, UUID> {
    List<MealRecord> findByUserIdAndRecordDateOrderByCreatedAtDesc(UUID userId, LocalDate recordDate);
}
