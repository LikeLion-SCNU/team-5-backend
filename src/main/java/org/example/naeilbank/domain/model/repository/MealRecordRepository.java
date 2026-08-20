package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.entity.MealRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MealRecordRepository extends JpaRepository<MealRecord, UUID> {
    List<MealRecord> findByUserIdAndRecordDateOrderByCreatedAtDesc(UUID userId, LocalDate recordDate);

    Optional<MealRecord> findByIdAndUserId(UUID id, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MealRecord m where m.id = :id and m.userId = :userId")
    Optional<MealRecord> findByIdAndUserIdForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MealRecord m where m.userId = :userId and m.mediaBlobId = :mediaBlobId")
    Optional<MealRecord> findByUserIdAndMediaBlobIdForUpdate(
            @Param("userId") UUID userId,
            @Param("mediaBlobId") UUID mediaBlobId
    );
}
