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

    /**
     * 같은 사진으로 아직 확정하지 않은 기록만 찾는다.
     *
     * 확정·제외까지 끝난 기록을 재사용하면, 같은 음식을 다시 먹어 올렸을 때
     * 지난 기록이 그대로 돌아와 새 등록이 되지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MealRecord m"
            + " where m.userId = :userId"
            + " and m.mediaBlobId = :mediaBlobId"
            + " and m.status in (org.example.naeilbank.entity.MealStatus.analyzing,"
            + " org.example.naeilbank.entity.MealStatus.pending_confirm)"
            + " order by m.createdAt desc")
    List<MealRecord> findUnconfirmedByUserIdAndMediaBlobIdForUpdate(
            @Param("userId") UUID userId,
            @Param("mediaBlobId") UUID mediaBlobId
    );
}
