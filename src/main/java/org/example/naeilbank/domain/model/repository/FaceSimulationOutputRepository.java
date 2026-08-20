package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.FaceSimulationOutput;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FaceSimulationOutputRepository extends JpaRepository<FaceSimulationOutput, UUID> {
    Optional<FaceSimulationOutput> findByIdAndUserId(UUID id, UUID userId);

    Optional<FaceSimulationOutput> findByUserIdAndMediaBlobId(UUID userId, UUID mediaBlobId);

    List<FaceSimulationOutput> findByUserIdAndSimulationId(UUID userId, UUID simulationId);

    void deleteByUserIdAndSimulationId(UUID userId, UUID simulationId);
}
