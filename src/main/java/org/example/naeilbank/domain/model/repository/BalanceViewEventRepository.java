package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.BalanceViewEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface BalanceViewEventRepository extends JpaRepository<BalanceViewEvent, UUID> {
    Optional<BalanceViewEvent> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    long countByUserIdAndCreatedAtGreaterThanEqual(UUID userId, Instant since);
}
