package org.example.naeilbank.domain.plan;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.example.naeilbank.domain.model.entity.Plan;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class PlanDtos {
    private PlanDtos() {
    }

    public record PlanActionResponse(
            int position,
            String actionType,
            int targetMinutes,
            UUID sourceId,
            UUID ruleId,
            UUID ruleLogicalKey,
            int ruleVersion,
            String ruleUnit,
            int ruleMinutes,
            int repetitions
    ) {
    }

    public record PlanResponse(
            UUID id,
            Long version,
            String title,
            Plan.Status status,
            PlanAvailability availability,
            String advisoryCopy,
            long currentBalanceMinutes,
            long deficitMinutes,
            Integer expectedWeeklyMinutes,
            LocalDate startDate,
            LocalDate endDate,
            int progressDays,
            int completedMinutes,
            int remainingMinutes,
            List<PlanActionResponse> actions
    ) {
    }

    public record PlanDecisionRequest(
            @NotNull Boolean accepted,
            @NotNull @PositiveOrZero Long expectedVersion
    ) {
    }

    public record PlanRegenerateRequest(
            @NotNull @PositiveOrZero Long expectedVersion
    ) {
    }

    public record PlanProgressRequest(
            @NotNull LocalDate progressDate,
            @PositiveOrZero int completedMinutes,
            @NotNull @PositiveOrZero Long expectedVersion
    ) {
    }

    public enum PlanAvailability {
        AVAILABLE,
        NOT_NEEDED,
        NOT_GENERATED,
        NO_ACTIVE_RULE,
        UNREPRESENTABLE_BALANCE,
        BALANCE_OUT_OF_RANGE
    }
}
