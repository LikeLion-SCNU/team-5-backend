package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.entity.MealItem;
import org.example.naeilbank.entity.MealStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface MealItemRepository extends JpaRepository<MealItem, UUID> {
    List<MealItem> findByMealRecordIdAndDeletedFalse(UUID mealRecordId);

    List<MealItem> findByMealRecordIdOrderById(UUID mealRecordId);

    @Query("""
            select i from MealItem i, MealRecord r
            where i.mealRecordId = r.id and r.userId = :userId
              and r.recordDate = :entryDate and r.status = :status and i.deleted = false
            order by i.id
            """)
    List<MealItem> findOwnedActiveItemsForDate(@Param("userId") UUID userId,
                                                @Param("entryDate") LocalDate entryDate,
                                                @Param("status") MealStatus status);

    @Query("""
            select case when count(i) > 0 then true else false end
            from MealItem i, MealRecord r
            where i.id = :eventId and i.mealRecordId = r.id
              and r.userId = :userId and r.recordDate = :entryDate and i.deleted = false
              and r.status = :status
            """)
    boolean existsOwnedEvent(@Param("eventId") UUID eventId,
                             @Param("userId") UUID userId,
                             @Param("entryDate") LocalDate entryDate,
                             @Param("status") MealStatus status);
}
