package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.FaceSimulationOutput;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FaceSimulationOutputRepository extends Repository<FaceSimulationOutput, UUID> {
    FaceSimulationOutput save(FaceSimulationOutput output);

    Optional<FaceSimulationOutput> findByIdAndUserId(UUID id, UUID userId);

    List<FaceSimulationOutput> findByUserIdAndSimulationId(UUID userId, UUID simulationId);
}
