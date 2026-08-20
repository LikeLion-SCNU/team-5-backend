package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.entity.ConversionRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConversionRuleRepository extends JpaRepository<ConversionRule, UUID> {
    List<ConversionRule> findByHabitTypeAndActiveTrue(ConversionRule.HabitType habitType);
}
