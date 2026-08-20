package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.BalanceViewEvent;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface BalanceViewEventRepository extends Repository<BalanceViewEvent, UUID> {
    BalanceViewEvent save(BalanceViewEvent event);

    Optional<BalanceViewEvent> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
}
