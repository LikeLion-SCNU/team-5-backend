package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.Plan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlanRepository extends JpaRepository<Plan, UUID> {
    List<Plan> findByUserIdAndStatus(UUID userId, Plan.Status status);
}
