package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.FaceSimulationOutput;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FaceSimulationOutputRepository extends JpaRepository<FaceSimulationOutput, UUID> {
    Optional<FaceSimulationOutput> findByIdAndUserId(UUID id, UUID userId);

    Optional<FaceSimulationOutput> findByUserIdAndMediaBlobId(UUID userId, UUID mediaBlobId);

    List<FaceSimulationOutput> findByUserIdAndSimulationIdOrderByLabelAsc(UUID userId, UUID simulationId);

    List<FaceSimulationOutput> findByUserIdAndSimulationIdInOrderBySimulationIdAscLabelAsc(
            UUID userId,
            Collection<UUID> simulationIds
    );

    void deleteByUserIdAndSimulationId(UUID userId, UUID simulationId);
}
