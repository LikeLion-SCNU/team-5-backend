package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.NotificationAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationAttemptRepository extends JpaRepository<NotificationAttempt, UUID> {
    List<NotificationAttempt> findByStatusInAndNextAttemptAtLessThanEqual(
            List<NotificationAttempt.Status> statuses,
            Instant dueAt
    );
}
