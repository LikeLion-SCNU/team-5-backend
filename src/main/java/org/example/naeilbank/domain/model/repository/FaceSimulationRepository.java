package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.FaceSimulation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FaceSimulationRepository extends JpaRepository<FaceSimulation, UUID> {
    List<FaceSimulation> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<FaceSimulation> findByUserIdAndSourceMediaId(UUID userId, UUID sourceMediaId);

    Optional<FaceSimulation> findByIdAndUserId(UUID id, UUID userId);

    Optional<FaceSimulation> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FaceSimulation f where f.id = :id and f.userId = :userId")
    Optional<FaceSimulation> findOwnedForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FaceSimulation f where f.id = :id")
    Optional<FaceSimulation> findForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<FaceSimulation> findFirstByStatusInOrderByCreatedAtAsc(List<FaceSimulation.Status> statuses);
}
