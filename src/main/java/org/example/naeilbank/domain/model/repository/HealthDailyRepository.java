package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.entity.HealthDaily;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface HealthDailyRepository extends JpaRepository<HealthDaily, UUID> {
    Optional<HealthDaily> findByUserIdAndRecordDate(UUID userId, LocalDate recordDate);

    boolean existsByIdAndUserIdAndRecordDate(UUID id, UUID userId, LocalDate recordDate);
}
