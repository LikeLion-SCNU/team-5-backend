package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.AuditEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditEventRepository extends Repository<AuditEvent, UUID> {
    AuditEvent save(AuditEvent event);

    Optional<AuditEvent> findByIdAndUserId(UUID id, UUID userId);

    List<AuditEvent> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    @Query(value = """
            select *
            from audit_events
            where user_id = :userId
              and event_type = 'CONSENT_CHANGED'
              and detail_json ->> 'requestKeyHash' = :requestKeyHash
            order by created_at desc
            limit 1
            """, nativeQuery = true)
    Optional<AuditEvent> findConsentChangeByRequestKeyHash(
            @Param("userId") UUID userId,
            @Param("requestKeyHash") String requestKeyHash
    );
}
