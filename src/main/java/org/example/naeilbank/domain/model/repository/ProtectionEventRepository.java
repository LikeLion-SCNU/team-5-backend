package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.ProtectionEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProtectionEventRepository extends JpaRepository<ProtectionEvent, UUID> {
    Optional<ProtectionEvent> findByIdAndUserId(UUID id, UUID userId);

    Optional<ProtectionEvent> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    List<ProtectionEvent> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
