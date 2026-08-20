package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.FaceSimulation;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface FaceSimulationRepository extends JpaRepository<FaceSimulation, UUID> {
    Page<FaceSimulation> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    boolean existsByUserIdAndSourceMediaId(UUID userId, UUID sourceMediaId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select f from FaceSimulation f
            where f.userId = :userId and f.sourceMediaId = :sourceMediaId
            order by f.id
            """)
    List<FaceSimulation> findBySourceForUpdate(
            @Param("userId") UUID userId,
            @Param("sourceMediaId") UUID sourceMediaId
    );

    Optional<FaceSimulation> findByIdAndUserId(UUID id, UUID userId);

    Optional<FaceSimulation> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update public.face_simulations
            set status = 'cancelled',
                cancelled_at = :cancelledAt,
                claim_token = null,
                updated_at = now(),
                version = version + 1
            where id = :id
              and user_id = :userId
              and status in ('queued', 'generating', 'processing')
            """, nativeQuery = true)
    int cancelActive(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("cancelledAt") Instant cancelledAt
    );

    long countByUserIdAndStatusIn(UUID userId, List<FaceSimulation.Status> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FaceSimulation f where f.id = :id and f.userId = :userId")
    Optional<FaceSimulation> findOwnedForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query(value = """
            select id
            from public.face_simulations
            where (status in ('queued', 'generating') and next_attempt_at <= :now)
               or (status = 'processing' and processing_started_at <= :staleBefore)
            order by coalesce(next_attempt_at, created_at) asc, created_at asc
            limit 1
            """, nativeQuery = true)
    Optional<UUID> findNextDueId(
            @Param("now") Instant now,
            @Param("staleBefore") Instant staleBefore
    );
}
