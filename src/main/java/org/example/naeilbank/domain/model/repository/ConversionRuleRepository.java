package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.entity.ConversionRule;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversionRuleRepository extends JpaRepository<ConversionRule, UUID> {
    List<ConversionRule> findByHabitTypeAndActiveTrueOrderByLabelAsc(ConversionRule.HabitType habitType);

    List<ConversionRule> findByActiveTrueOrderByHabitTypeAscLabelAsc();

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            select r from ConversionRule r
            where r.active = true and r.minutesDelta > 0
            order by r.habitType asc, r.label asc, r.id asc
            """)
    List<ConversionRule> findActiveForPlan();

    boolean existsBySourceIdAndActiveTrue(UUID sourceId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            select r from ConversionRule r
            where r.habitType = :habitType and r.unit = :unit and r.active = true
            order by r.versionNumber desc, r.logicalKey asc
            """)
    List<ConversionRule> findActiveForConversion(
            @Param("habitType") ConversionRule.HabitType habitType,
            @Param("unit") String unit
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ConversionRule r where r.id = :id")
    Optional<ConversionRule> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r from ConversionRule r
            where r.logicalKey = (
                select target.logicalKey from ConversionRule target where target.id = :id
            )
            order by r.versionNumber asc
            """)
    List<ConversionRule> findFamilyByMemberIdForUpdate(@Param("id") UUID id);
}
