package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.FaceSimulation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FaceSimulationRepository extends JpaRepository<FaceSimulation, UUID> {
    List<FaceSimulation> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
