package org.example.naeilbank.domain.plan;

import jakarta.validation.constraints.NotNull;
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
            UUID ruleId
    ) {
    }

    public record PlanResponse(
            UUID id,
            String title,
            Plan.Status status,
            long currentBalanceMinutes,
            long deficitMinutes,
            Integer expectedWeeklyMinutes,
            LocalDate startDate,
            LocalDate endDate,
            List<PlanActionResponse> actions
    ) {
    }

    public record PlanDecisionRequest(
            @NotNull Boolean accepted
    ) {
    }
}
