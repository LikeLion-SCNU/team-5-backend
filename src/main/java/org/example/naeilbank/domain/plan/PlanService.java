package org.example.naeilbank.domain.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.model.entity.Plan;
import org.example.naeilbank.domain.model.entity.PlanAction;
import org.example.naeilbank.domain.model.entity.PlanProgress;
import org.example.naeilbank.domain.model.repository.ConversionRuleRepository;
import org.example.naeilbank.domain.model.repository.LedgerEntryRepository;
import org.example.naeilbank.domain.model.repository.PlanActionRepository;
import org.example.naeilbank.domain.model.repository.PlanProgressRepository;
import org.example.naeilbank.domain.model.repository.PlanRepository;
import org.example.naeilbank.domain.plan.PlanDtos.PlanActionResponse;
import org.example.naeilbank.domain.plan.PlanDtos.PlanAvailability;
import org.example.naeilbank.domain.plan.PlanDtos.PlanDecisionRequest;
import org.example.naeilbank.domain.plan.PlanDtos.PlanProgressRequest;
import org.example.naeilbank.domain.plan.PlanDtos.PlanRegenerateRequest;
import org.example.naeilbank.domain.plan.PlanDtos.PlanResponse;
import org.example.naeilbank.domain.protection.ProtectionCopyPolicy;
import org.example.naeilbank.entity.ConversionRule;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlanService {
    private static final int MAX_DAILY_PROGRESS_MINUTES = 1440;
    private static final String STANDARD_TITLE = "주간 회복 플랜 (참고용)";
    private static final String ADVISORY =
            "이 주간 플랜은 참고용 안내이며, 원장(잔고)에는 어떤 영향도 주지 않습니다.";
    private static final String NO_PLAN_NEEDED =
            "잔고가 0 이상이라 지금은 회복 플랜이 필요하지 않습니다.";
    private static final String NO_ACTIVE_PLAN =
            "현재 적자와 저장된 환산 규칙을 바탕으로 주간 회복 플랜을 만들 수 있습니다.";
    private static final String NO_ACTIVE_RULE =
            "저장된 활성 환산 규칙이 없어 회복 플랜을 만들 수 없습니다.";
    private static final String UNREPRESENTABLE =
            "현재 적자를 저장된 환산 규칙으로 정확히 구성할 수 없습니다.";
    private static final String OUT_OF_RANGE =
            "현재 적자가 회복 플랜이 지원하는 범위를 벗어났습니다.";

    private final PlanRepository planRepository;
    private final PlanActionRepository actionRepository;
    private final PlanProgressRepository progressRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final ConversionRuleRepository ruleRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ProtectionCopyPolicy protectionCopyPolicy;

    @Transactional(readOnly = true)
    public PlanResponse current(UUID userId) {
        requireUser(userId);
        long balance = ledgerRepository.sumMinutesByUserId(userId);
        if (balance >= 0) {
            return noPlan(balance, PlanAvailability.NOT_NEEDED, NO_PLAN_NEEDED);
        }
        return activePlan(userId)
                .map(plan -> response(plan, balance))
                .orElseGet(() -> noPlan(balance, PlanAvailability.NOT_GENERATED, NO_ACTIVE_PLAN));
    }

    @Transactional(readOnly = true)
    public PlanResponse read(UUID userId, UUID planId) {
        requireUser(userId);
        return response(ownedPlan(userId, planId), ledgerRepository.sumMinutesByUserId(userId));
    }

    @Transactional
    public PlanResponse generate(UUID userId) {
        requireLockedUser(userId);
        long balance = ledgerRepository.sumMinutesByUserId(userId);
        if (balance >= 0) {
            return noPlan(balance, PlanAvailability.NOT_NEEDED, NO_PLAN_NEEDED);
        }
        return activePlan(userId)
                .map(plan -> response(plan, balance))
                .orElseGet(() -> createProposalResponse(userId, balance));
    }

    @Transactional
    public PlanResponse decide(UUID userId, UUID planId, PlanDecisionRequest request) {
        requireLockedUser(userId);
        Plan plan = ownedPlan(userId, planId);
        requireVersion(plan, request.expectedVersion());
        if (plan.getStatus() != Plan.Status.proposed) {
            throw error(ErrorCode.PLAN_STATE_INVALID);
        }
        Instant now = Instant.now(clock);
        if (request.accepted()) {
            long balance = ledgerRepository.sumMinutesByUserId(userId);
            if (balance >= 0 || deficit(balance).filter(value -> value.equals((long) plan.getExpectedWeeklyMinutes())).isEmpty()
                    || !hasCurrentLineage(plan)) {
                throw error(ErrorCode.PLAN_STALE);
            }
            LocalDate start = LocalDate.ofInstant(now, ZoneId.of("Asia/Seoul"));
            plan.accept(start, start.plusDays(6), now);
        } else {
            plan.reject(now);
        }
        planRepository.saveAndFlush(plan);
        return response(plan, ledgerRepository.sumMinutesByUserId(userId));
    }

    @Transactional
    public PlanResponse regenerate(UUID userId, UUID planId, PlanRegenerateRequest request) {
        requireLockedUser(userId);
        Plan previous = ownedPlan(userId, planId);
        long balance = ledgerRepository.sumMinutesByUserId(userId);
        if (balance >= 0) {
            return noPlan(balance, PlanAvailability.NOT_NEEDED, NO_PLAN_NEEDED);
        }
        if (previous.getStatus() == Plan.Status.rejected) {
            return activePlan(userId)
                    .map(plan -> response(plan, balance))
                    .orElseThrow(() -> error(ErrorCode.PLAN_STATE_INVALID));
        }
        requireVersion(previous, request.expectedVersion());
        if (previous.getStatus() != Plan.Status.proposed) {
            throw error(ErrorCode.PLAN_STATE_INVALID);
        }
        PlanResponse replacement = createProposalResponse(userId, balance);
        if (replacement.availability() != PlanAvailability.AVAILABLE) {
            return replacement;
        }
        previous.reject(Instant.now(clock));
        planRepository.saveAndFlush(previous);
        return replacement;
    }

    @Transactional
    public PlanResponse updateProgress(UUID userId, UUID planId, PlanProgressRequest request) {
        requireLockedUser(userId);
        Plan plan = ownedPlan(userId, planId);
        requireVersion(plan, request.expectedVersion());
        if (plan.getStatus() != Plan.Status.accepted && plan.getStatus() != Plan.Status.completed) {
            throw error(ErrorCode.PLAN_STATE_INVALID);
        }
        if (request.progressDate().isBefore(plan.getStartDate()) || request.progressDate().isAfter(plan.getEndDate())) {
            throw error(ErrorCode.PLAN_PROGRESS_DATE_INVALID);
        }
        if (request.completedMinutes() > MAX_DAILY_PROGRESS_MINUTES) {
            throw error(ErrorCode.VALIDATION_FAILED);
        }
        PlanProgress item = progressRepository.findByPlanIdAndProgressDate(planId, request.progressDate())
                .map(existing -> {
                    existing.replaceCompletedMinutes(request.completedMinutes());
                    return existing;
                })
                .orElseGet(() -> PlanProgress.create(
                        planId, request.progressDate(), request.completedMinutes(), Instant.now(clock)));
        progressRepository.saveAndFlush(item);
        List<PlanProgress> allProgress = progressRepository.findByPlanIdOrderByProgressDate(planId);
        int completed = totalCompleted(allProgress);
        plan.applyProgress(allProgress.size(), completed);
        planRepository.saveAndFlush(plan);
        return response(plan, ledgerRepository.sumMinutesByUserId(userId));
    }

    private PlanResponse createProposalResponse(UUID userId, long balance) {
        Optional<Long> deficit = deficit(balance);
        if (deficit.isEmpty() || deficit.get() > Integer.MAX_VALUE) {
            return noPlan(balance, PlanAvailability.BALANCE_OUT_OF_RANGE, OUT_OF_RANGE);
        }
        Instant now = Instant.now(clock);
        List<ConversionRule> rules = ruleRepository.findActiveForPlan();
        if (rules.isEmpty()) {
            return noPlan(balance, PlanAvailability.NO_ACTIVE_RULE, NO_ACTIVE_RULE);
        }
        List<RuleAllocation> allocations = exactAllocations(deficit.get().intValue(), rules);
        if (allocations.isEmpty()) {
            return noPlan(balance, PlanAvailability.UNREPRESENTABLE_BALANCE, UNREPRESENTABLE);
        }
        List<Map<String, Object>> snapshots = new ArrayList<>();
        int position = 0;
        for (RuleAllocation allocation : allocations) {
            snapshots.add(snapshot(position++, allocation));
        }
        Plan plan = planRepository.saveAndFlush(Plan.proposed(
                userId, protectionCopyPolicy.planTitle(false, STANDARD_TITLE), writeJson(snapshots),
                deficit.get().intValue(), now));
        for (Map<String, Object> snapshot : snapshots) {
            actionRepository.save(PlanAction.create(
                    plan.getId(),
                    (Integer) snapshot.get("position"),
                    (String) snapshot.get("actionType"),
                    (Integer) snapshot.get("targetMinutes"),
                    (UUID) snapshot.get("sourceId"),
                    (UUID) snapshot.get("ruleId"),
                    now
            ));
        }
        return response(plan, balance);
    }

    private List<RuleAllocation> exactAllocations(int deficit, List<ConversionRule> rules) {
        int[] counts = new int[deficit + 1];
        int[] previousRule = new int[deficit + 1];
        Arrays.fill(counts, Integer.MAX_VALUE);
        Arrays.fill(previousRule, -1);
        counts[0] = 0;
        for (int amount = 1; amount <= deficit; amount++) {
            for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
                int value = rules.get(ruleIndex).getMinutesDelta();
                if (value <= amount && counts[amount - value] != Integer.MAX_VALUE
                        && counts[amount - value] + 1 < counts[amount]) {
                    counts[amount] = counts[amount - value] + 1;
                    previousRule[amount] = ruleIndex;
                }
            }
        }
        if (previousRule[deficit] < 0) {
            return List.of();
        }
        Map<Integer, Integer> repetitions = new LinkedHashMap<>();
        for (int amount = deficit; amount > 0; ) {
            int ruleIndex = previousRule[amount];
            repetitions.merge(ruleIndex, 1, Integer::sum);
            amount -= rules.get(ruleIndex).getMinutesDelta();
        }
        return repetitions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new RuleAllocation(rules.get(entry.getKey()), entry.getValue()))
                .toList();
    }

    private Map<String, Object> snapshot(int position, RuleAllocation allocation) {
        ConversionRule rule = allocation.rule();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("position", position);
        snapshot.put("actionType", rule.getHabitType().name());
        snapshot.put("targetMinutes", Math.multiplyExact(rule.getMinutesDelta(), allocation.repetitions()));
        snapshot.put("sourceId", rule.getSourceId());
        snapshot.put("ruleId", rule.getId());
        snapshot.put("ruleLogicalKey", rule.getLogicalKey());
        snapshot.put("ruleVersion", rule.getVersionNumber());
        snapshot.put("ruleUnit", rule.getUnit());
        snapshot.put("ruleMinutes", rule.getMinutesDelta());
        snapshot.put("repetitions", allocation.repetitions());
        return snapshot;
    }

    private PlanResponse response(Plan plan, long balance) {
        List<PlanActionResponse> actionResponses = readSnapshots(plan).stream()
                .map(ActionSnapshot::toResponse)
                .toList();
        int completed = totalCompleted(progressRepository.findByPlanIdOrderByProgressDate(plan.getId()));
        int planned = plan.getExpectedWeeklyMinutes() == null ? 0 : plan.getExpectedWeeklyMinutes();
        return new PlanResponse(
                plan.getId(), plan.getVersion(), plan.getTitle(), plan.getStatus(), PlanAvailability.AVAILABLE,
                ADVISORY, balance, deficitForResponse(balance), planned,
                plan.getStartDate(), plan.getEndDate(), plan.getProgressDays(), completed,
                Math.max(0, planned - completed), actionResponses);
    }

    private boolean validLineage(PlanAction action, ConversionRule rule) {
        return rule != null && action.getSourceId() != null && action.getSourceId().equals(rule.getSourceId())
                && rule.getMinutesDelta() > 0 && action.getTargetMinutes() > 0
                && action.getTargetMinutes() % rule.getMinutesDelta() == 0;
    }

    private Map<UUID, ConversionRule> rulesById(List<PlanAction> actions) {
        List<UUID> ruleIds = actions.stream().map(PlanAction::getRuleId).distinct().toList();
        Map<UUID, ConversionRule> rules = new HashMap<>();
        ruleRepository.findAllById(ruleIds).forEach(rule -> rules.put(rule.getId(), rule));
        return rules;
    }

    private boolean hasCurrentLineage(Plan plan) {
        List<PlanAction> actions = actionRepository.findByPlanIdOrderByPosition(plan.getId());
        Map<UUID, ConversionRule> rules = rulesById(actions);
        Map<Integer, ActionSnapshot> snapshots = new HashMap<>();
        readSnapshots(plan).forEach(snapshot -> snapshots.put(snapshot.position(), snapshot));
        return !actions.isEmpty() && actions.size() == snapshots.size() && actions.stream().allMatch(action -> {
            ConversionRule rule = rules.get(action.getRuleId());
            ActionSnapshot snapshot = snapshots.get(action.getPosition());
            return validLineage(action, rule) && rule.isActive()
                    && snapshot != null && snapshot.matches(action, rule);
        });
    }

    private List<ActionSnapshot> readSnapshots(Plan plan) {
        try {
            List<ActionSnapshot> snapshots = objectMapper.readValue(
                    plan.getActionsJson(), new TypeReference<List<ActionSnapshot>>() { });
            if (snapshots.isEmpty() || snapshots.stream().anyMatch(snapshot -> !snapshot.valid())) {
                throw error(ErrorCode.PLAN_LINEAGE_INVALID);
            }
            return snapshots;
        } catch (JsonProcessingException | ArithmeticException exception) {
            throw error(ErrorCode.PLAN_LINEAGE_INVALID);
        }
    }

    private Optional<Plan> activePlan(UUID userId) {
        Optional<Plan> accepted = planRepository
                .findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, Plan.Status.accepted);
        return accepted.isPresent() ? accepted : planRepository
                .findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, Plan.Status.proposed);
    }

    private PlanResponse noPlan(long balance, PlanAvailability availability, String advisory) {
        return new PlanResponse(null, null, null, null, availability, advisory, balance,
                deficitForResponse(balance), 0, null, null, 0, 0, 0, List.of());
    }

    private int totalCompleted(List<PlanProgress> progress) {
        return progress.stream().mapToInt(PlanProgress::getCompletedMinutes).reduce(0, Math::addExact);
    }

    private Optional<Long> deficit(long balance) {
        try {
            return Optional.of(Math.negateExact(balance));
        } catch (ArithmeticException exception) {
            return Optional.empty();
        }
    }

    private long deficitForResponse(long balance) {
        return balance >= 0 ? 0 : deficit(balance).orElse(Long.MAX_VALUE);
    }

    private void requireUser(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw error(ErrorCode.USER_NOT_FOUND);
        }
    }

    private void requireLockedUser(UUID userId) {
        userRepository.findByIdForUpdate(userId).orElseThrow(() -> error(ErrorCode.USER_NOT_FOUND));
    }

    private Plan ownedPlan(UUID userId, UUID planId) {
        return planRepository.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> error(ErrorCode.ACCESS_DENIED));
    }

    private void requireVersion(Plan plan, long expectedVersion) {
        if (plan.getVersion() != expectedVersion) {
            throw error(ErrorCode.PLAN_VERSION_CONFLICT);
        }
    }

    private AuthException error(ErrorCode code) {
        return new AuthException(code);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("plan actions cannot be serialized", exception);
        }
    }

    private record RuleAllocation(ConversionRule rule, int repetitions) {
    }

    private record ActionSnapshot(
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
        private boolean valid() {
            return position >= 0 && actionType != null && !actionType.isBlank() && targetMinutes > 0
                    && sourceId != null && ruleId != null && ruleLogicalKey != null && ruleVersion > 0
                    && ruleUnit != null && !ruleUnit.isBlank() && ruleMinutes > 0 && repetitions > 0
                    && Math.multiplyExact(ruleMinutes, repetitions) == targetMinutes;
        }

        private boolean matches(PlanAction action, ConversionRule rule) {
            return actionType.equals(action.getActionType())
                    && targetMinutes == action.getTargetMinutes()
                    && sourceId.equals(action.getSourceId())
                    && ruleId.equals(action.getRuleId())
                    && sourceId.equals(rule.getSourceId())
                    && ruleLogicalKey.equals(rule.getLogicalKey())
                    && ruleVersion == rule.getVersionNumber()
                    && ruleUnit.equals(rule.getUnit())
                    && ruleMinutes == rule.getMinutesDelta();
        }

        private PlanActionResponse toResponse() {
            return new PlanActionResponse(position, actionType, targetMinutes, sourceId, ruleId,
                    ruleLogicalKey, ruleVersion, ruleUnit, ruleMinutes, repetitions);
        }
    }
}
