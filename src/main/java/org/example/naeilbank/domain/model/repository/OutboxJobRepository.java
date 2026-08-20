package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.OutboxJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxJobRepository extends JpaRepository<OutboxJob, UUID> {
    Optional<OutboxJob> findByUserIdAndJobTypeAndIdempotencyKey(
            UUID userId,
            String jobType,
            String idempotencyKey
    );

    Optional<OutboxJob> findByUserIdIsNullAndJobTypeAndIdempotencyKey(String jobType, String idempotencyKey);

    List<OutboxJob> findByStatusInAndNextAttemptAtLessThanEqual(List<OutboxJob.Status> statuses, Instant dueAt);
}
