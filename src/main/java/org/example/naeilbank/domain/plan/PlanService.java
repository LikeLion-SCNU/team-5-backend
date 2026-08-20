package org.example.naeilbank.domain.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.model.repository.ConversionRuleRepository;
import org.example.naeilbank.domain.model.repository.LedgerEntryRepository;
import org.example.naeilbank.domain.model.repository.PlanActionRepository;
import org.example.naeilbank.domain.model.repository.PlanRepository;
import org.example.naeilbank.domain.plan.PlanDtos.PlanActionResponse;
import org.example.naeilbank.domain.plan.PlanDtos.PlanDecisionRequest;
import org.example.naeilbank.domain.plan.PlanDtos.PlanResponse;
import org.example.naeilbank.entity.ConversionRule;
import org.example.naeilbank.domain.model.entity.Plan;
import org.example.naeilbank.domain.model.entity.PlanAction;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlanService {
    private final PlanRepository planRepository;
    private final PlanActionRepository actionRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final ConversionRuleRepository ruleRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional
    public PlanResponse current(UUID userId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        long balance = ledgerRepository.sumMinutesByUserId(userId);
        List<Plan> accepted = planRepository.findByUserIdAndStatus(userId, Plan.Status.accepted);
        if (!accepted.isEmpty()) {
            return response(accepted.getFirst(), balance);
        }
        List<Plan> proposed = planRepository.findByUserIdAndStatus(userId, Plan.Status.proposed);
        if (!proposed.isEmpty()) {
            return response(proposed.getFirst(), balance);
        }
        long deficit = Math.max(0, -balance);
        if (deficit == 0) {
            return new PlanResponse(null, "No repayment plan needed", Plan.Status.completed, balance, 0, 0, null, null, List.of());
        }
        return response(createProposal(userId, deficit), balance);
    }

    @Transactional
    public PlanResponse decide(UUID userId, UUID planId, PlanDecisionRequest request) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        Plan plan = planRepository.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new AuthException(ErrorCode.ACCESS_DENIED));
        Instant now = Instant.now(clock);
        if (plan.getStatus() != Plan.Status.proposed) {
            return response(plan, ledgerRepository.sumMinutesByUserId(userId));
        }
        if (request.accepted()) {
            LocalDate start = LocalDate.ofInstant(now, ZoneId.systemDefault());
            plan.accept(start, start.plusDays(13), now);
        } else {
            plan.reject(now);
        }
        return response(plan, ledgerRepository.sumMinutesByUserId(userId));
    }

    private Plan createProposal(UUID userId, long deficitMinutes) {
        Instant now = Instant.now(clock);
        List<ConversionRule> rules = ruleRepository.findByActiveTrueOrderByHabitTypeAscLabelAsc().stream()
                .filter(rule -> rule.getMinutesDelta() > 0)
                .sorted(Comparator.comparing(ConversionRule::getHabitType).thenComparing(ConversionRule::getLabel))
                .toList();
        if (rules.isEmpty()) {
            throw new AuthException(ErrorCode.CONVERSION_RULE_UNAVAILABLE);
        }
        List<Map<String, Object>> actionJson = new ArrayList<>();
        long remaining = deficitMinutes;
        int position = 0;
        for (ConversionRule rule : rules) {
            if (remaining <= 0 || position >= 3) {
                break;
            }
            int target = (int) Math.min(Math.max(1, rule.getMinutesDelta()), remaining);
            actionJson.add(Map.of(
                    "position", position,
                    "actionType", rule.getHabitType().name(),
                    "targetMinutes", target,
                    "sourceId", rule.getSourceId(),
                    "ruleId", rule.getId()
            ));
            remaining -= target;
            position++;
        }
        Plan plan = planRepository.save(Plan.proposed(
                userId,
                "Repay current deficit",
                writeJson(actionJson),
                actionJson.stream().mapToInt(action -> (Integer) action.get("targetMinutes")).sum(),
                now
        ));
        for (Map<String, Object> action : actionJson) {
            actionRepository.save(PlanAction.create(
                    plan.getId(),
                    (Integer) action.get("position"),
                    (String) action.get("actionType"),
                    (Integer) action.get("targetMinutes"),
                    (UUID) action.get("sourceId"),
                    (UUID) action.get("ruleId"),
                    now
            ));
        }
        return plan;
    }

    private PlanResponse response(Plan plan, long balance) {
        List<PlanActionResponse> actions = plan.getId() == null ? List.of() : actionRepository.findByPlanIdOrderByPosition(plan.getId()).stream()
                .map(action -> new PlanActionResponse(
                        action.getPosition(),
                        action.getActionType(),
                        action.getTargetMinutes(),
                        action.getSourceId(),
                        action.getRuleId()
                ))
                .toList();
        return new PlanResponse(
                plan.getId(),
                plan.getTitle(),
                plan.getStatus(),
                balance,
                Math.max(0, -balance),
                plan.getExpectedWeeklyMinutes(),
                plan.getStartDate(),
                plan.getEndDate(),
                actions
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("plan actions cannot be serialized", e);
        }
    }
}
