package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.PlanAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlanActionRepository extends JpaRepository<PlanAction, UUID> {
    List<PlanAction> findByPlanIdOrderByPosition(UUID planId);
}
