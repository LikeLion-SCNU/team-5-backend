package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.NotificationAttempt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationAttemptRepository extends JpaRepository<NotificationAttempt, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select a from NotificationAttempt a
            where a.status in :statuses and a.nextAttemptAt <= :dueAt
            order by a.createdAt asc
            """)
    List<NotificationAttempt> findDueForUpdate(
            @Param("statuses") List<NotificationAttempt.Status> statuses,
            @Param("dueAt") Instant dueAt
    );

    Optional<NotificationAttempt> findBySubscriptionIdAndLocalDateAndType(
            UUID subscriptionId,
            LocalDate localDate,
            NotificationAttempt.Type type
    );

    boolean existsByUserIdAndLocalDateAndType(UUID userId, LocalDate localDate, NotificationAttempt.Type type);
}
