package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.PlanProgress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanProgressRepository extends JpaRepository<PlanProgress, UUID> {
    Optional<PlanProgress> findByPlanIdAndProgressDate(UUID planId, LocalDate progressDate);
    List<PlanProgress> findByPlanIdOrderByProgressDate(UUID planId);
}
