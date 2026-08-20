package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.AuditEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditEventRepository extends Repository<AuditEvent, UUID> {
    AuditEvent save(AuditEvent event);

    Optional<AuditEvent> findByIdAndUserId(UUID id, UUID userId);

    List<AuditEvent> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
