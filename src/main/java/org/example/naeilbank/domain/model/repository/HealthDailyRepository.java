package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.entity.HealthDaily;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface HealthDailyRepository extends JpaRepository<HealthDaily, UUID> {
    Optional<HealthDaily> findByUserIdAndRecordDate(UUID userId, LocalDate recordDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from HealthDaily h where h.userId = :userId and h.recordDate = :recordDate")
    Optional<HealthDaily> findByUserIdAndRecordDateForUpdate(
            @Param("userId") UUID userId,
            @Param("recordDate") LocalDate recordDate
    );

    boolean existsByIdAndUserIdAndRecordDate(UUID id, UUID userId, LocalDate recordDate);
}
