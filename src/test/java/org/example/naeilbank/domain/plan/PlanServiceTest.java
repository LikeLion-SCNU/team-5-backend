package org.example.naeilbank.domain.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.naeilbank.domain.model.entity.Plan;
import org.example.naeilbank.domain.model.entity.PlanAction;
import org.example.naeilbank.domain.model.entity.PlanProgress;
import org.example.naeilbank.domain.model.repository.ConversionRuleRepository;
import org.example.naeilbank.domain.model.repository.LedgerEntryRepository;
import org.example.naeilbank.domain.model.repository.PlanActionRepository;
import org.example.naeilbank.domain.model.repository.PlanProgressRepository;
import org.example.naeilbank.domain.model.repository.PlanRepository;
import org.example.naeilbank.domain.protection.ProtectionCopyPolicy;
import org.example.naeilbank.entity.ConversionRule;
import org.example.naeilbank.entity.User;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanServiceTest {
    private final PlanRepository plans = mock(PlanRepository.class);
    private final PlanActionRepository actions = mock(PlanActionRepository.class);
    private final PlanProgressRepository progress = mock(PlanProgressRepository.class);
    private final LedgerEntryRepository ledger = mock(LedgerEntryRepository.class);
    private final ConversionRuleRepository rules = mock(ConversionRuleRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ProtectionCopyPolicy copy = new ProtectionCopyPolicy();
    private final UUID userId = UUID.randomUUID();
    private PlanService service;

    @BeforeEach
    void setUp() {
        service = new PlanService(plans, actions, progress, ledger, rules, users, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC), copy);
        when(users.existsById(userId)).thenReturn(true);
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(User.local("owner@example.com", "pw")));
        when(progress.findByPlanIdOrderByProgressDate(any())).thenReturn(List.of());
    }

    @Test
    void baselineCurrentReturnsExistingProposalWithoutCreatingAnotherPlan() {
        ConversionRule rule = rule(30, 1, true);
        Plan proposal = plan(userId, rule, 1);
        when(ledger.sumMinutesByUserId(userId)).thenReturn(-30L);
        activePlan(proposal);
        stubLineage(proposal, rule);

        assertThat(service.current(userId).id()).isEqualTo(proposal.getId());
        verify(plans, never()).save(any());
    }

    @Test
    void positiveBalanceReturnsTypedNoPlanWithoutInventingActions() {
        when(ledger.sumMinutesByUserId(userId)).thenReturn(5L);

        PlanDtos.PlanResponse response = service.generate(userId);

        assertThat(response.availability()).isEqualTo(PlanDtos.PlanAvailability.NOT_NEEDED);
        assertThat(response.id()).isNull();
        assertThat(response.actions()).isEmpty();
        verify(rules, never()).findActiveForPlan();
        verify(plans, never()).saveAndFlush(any());
    }

    @Test
    void negativeBalanceUsesOnlyExactMultiplesAndExposesLineage() {
        ConversionRule thirty = rule(30, 4, true);
        ConversionRule twenty = rule(20, 2, true);
        when(ledger.sumMinutesByUserId(userId)).thenReturn(-50L);
        noActivePlan();
        when(rules.findActiveForPlan()).thenReturn(List.of(thirty, twenty));
        when(plans.saveAndFlush(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));
        when(actions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(actions.findByPlanIdOrderByPosition(any())).thenAnswer(invocation -> List.of(
                action(invocation.getArgument(0), 0, thirty, 30),
                action(invocation.getArgument(0), 1, twenty, 20)));
        when(rules.findAllById(any())).thenReturn(List.of(thirty, twenty));

        PlanDtos.PlanResponse response = service.generate(userId);

        assertThat(response.expectedWeeklyMinutes()).isEqualTo(50);
        assertThat(response.actions()).extracting(PlanDtos.PlanActionResponse::targetMinutes)
                .containsExactly(30, 20);
        assertThat(response.actions().getFirst().ruleVersion()).isEqualTo(4);
        assertThat(response.actions().getFirst().sourceId()).isEqualTo(thirty.getSourceId());
        assertThat(response.advisoryCopy()).contains("never changes the ledger");
    }

    @Test
    void zeroBalanceReturnsTypedNoPlanWithoutInventingActions() {
        when(ledger.sumMinutesByUserId(userId)).thenReturn(0L);

        PlanDtos.PlanResponse response = service.generate(userId);

        assertThat(response.availability()).isEqualTo(PlanDtos.PlanAvailability.NOT_NEEDED);
        assertThat(response.actions()).isEmpty();
        verify(rules, never()).findActiveForPlan();
    }

    @Test
    void missingRuleReturnsTypedNoPlanAndPersistsNothing() {
        when(ledger.sumMinutesByUserId(userId)).thenReturn(-30L);
        noActivePlan();
        when(rules.findActiveForPlan()).thenReturn(List.of());

        PlanDtos.PlanResponse response = service.generate(userId);

        assertThat(response.availability()).isEqualTo(PlanDtos.PlanAvailability.NO_ACTIVE_RULE);
        assertThat(response.actions()).isEmpty();
        verify(plans, never()).saveAndFlush(any());
        verify(actions, never()).save(any());
    }

    @Test
    void impossibleDeficitReturnsTypedValidationAndPersistsNothing() {
        when(ledger.sumMinutesByUserId(userId)).thenReturn(-25L);
        noActivePlan();
        when(rules.findActiveForPlan()).thenReturn(List.of(rule(20, 1, true), rule(30, 1, true)));

        PlanDtos.PlanResponse response = service.generate(userId);

        assertThat(response.availability()).isEqualTo(PlanDtos.PlanAvailability.UNREPRESENTABLE_BALANCE);
        assertThat(response.actions()).isEmpty();
        verify(plans, never()).saveAndFlush(any());
        verify(actions, never()).save(any());
    }

    @Test
    void regeneratePreservesRejectedHistoryAndUsesCurrentActiveRuleVersion() {
        ConversionRule oldRule = rule(30, 1, true);
        ConversionRule currentRule = rule(15, 2, true);
        Plan previous = plan(userId, oldRule, 1);
        when(ledger.sumMinutesByUserId(userId)).thenReturn(-30L);
        when(plans.findByIdAndUserId(previous.getId(), userId)).thenReturn(Optional.of(previous));
        when(rules.findActiveForPlan()).thenReturn(List.of(currentRule));
        when(plans.saveAndFlush(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));

        PlanDtos.PlanResponse response = service.regenerate(
                userId, previous.getId(), new PlanDtos.PlanRegenerateRequest(0L));

        assertThat(previous.getStatus()).isEqualTo(Plan.Status.rejected);
        assertThat(response.id()).isNotEqualTo(previous.getId());
        assertThat(response.actions().getFirst().ruleVersion()).isEqualTo(2);
        assertThat(response.actions().getFirst().repetitions()).isEqualTo(2);
        verify(plans).saveAndFlush(previous);
    }

    @Test
    void repeatedRegenerateOnRejectedPlanReturnsCurrentProposalIdempotently() {
        ConversionRule oldRule = rule(30, 1, true);
        ConversionRule currentRule = rule(15, 2, true);
        Plan previous = plan(userId, oldRule, 1);
        previous.reject(Instant.parse("2026-08-20T00:00:00Z"));
        Plan current = plan(userId, currentRule, 2);
        when(ledger.sumMinutesByUserId(userId)).thenReturn(-30L);
        when(plans.findByIdAndUserId(previous.getId(), userId)).thenReturn(Optional.of(previous));
        activePlan(current);

        PlanDtos.PlanResponse response = service.regenerate(
                userId, previous.getId(), new PlanDtos.PlanRegenerateRequest(0L));

        assertThat(response.id()).isEqualTo(current.getId());
        verify(rules, never()).findActiveForPlan();
    }

    @Test
    void acceptedWeeklyPlanTracksProgressWithoutMutatingLedger() {
        ConversionRule rule = rule(30, 1, true);
        Plan accepted = plan(userId, rule, 1);
        accepted.accept(java.time.LocalDate.parse("2026-08-21"),
                java.time.LocalDate.parse("2026-08-27"), Instant.parse("2026-08-21T00:00:00Z"));
        PlanProgress progressItem = PlanProgress.create(
                accepted.getId(), java.time.LocalDate.parse("2026-08-22"), 10,
                Instant.parse("2026-08-22T00:00:00Z"));
        when(plans.findByIdAndUserId(accepted.getId(), userId)).thenReturn(Optional.of(accepted));
        when(ledger.sumMinutesByUserId(userId)).thenReturn(-30L);
        when(progress.findByPlanIdAndProgressDate(accepted.getId(), java.time.LocalDate.parse("2026-08-22")))
                .thenReturn(Optional.empty());
        when(progress.findByPlanIdOrderByProgressDate(accepted.getId())).thenReturn(List.of(progressItem));

        PlanDtos.PlanResponse response = service.updateProgress(userId, accepted.getId(),
                new PlanDtos.PlanProgressRequest(java.time.LocalDate.parse("2026-08-22"), 10, 0L));

        assertThat(response.endDate()).isEqualTo(java.time.LocalDate.parse("2026-08-27"));
        assertThat(response.completedMinutes()).isEqualTo(10);
        assertThat(response.remainingMinutes()).isEqualTo(20);
        verify(progress).saveAndFlush(any());
        verify(ledger, never()).save(any());
    }

    @Test
    void secondUserCannotReadOwnersHistoricalPlan() {
        UUID secondUser = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        when(users.existsById(secondUser)).thenReturn(true);
        when(plans.findByIdAndUserId(planId, secondUser)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.read(secondUser, planId))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
    }

    @Test
    void acceptingPlanWithInactiveHistoricalRuleRequiresRegeneration() {
        ConversionRule inactive = rule(30, 1, false);
        Plan proposal = plan(userId, inactive, 1);
        when(plans.findByIdAndUserId(proposal.getId(), userId)).thenReturn(Optional.of(proposal));
        when(ledger.sumMinutesByUserId(userId)).thenReturn(-30L);
        stubLineage(proposal, inactive);

        assertThatThrownBy(() -> service.decide(userId, proposal.getId(),
                new PlanDtos.PlanDecisionRequest(true, 0L)))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PLAN_STALE));
    }

    @Test
    void acceptingPlanMatchesLongDeficitToIntegerExpectedMinutesByValue() {
        ConversionRule active = rule(30, 1, true);
        Plan proposal = plan(userId, active, 1);
        when(plans.findByIdAndUserId(proposal.getId(), userId)).thenReturn(Optional.of(proposal));
        when(ledger.sumMinutesByUserId(userId)).thenReturn(-30L);
        stubLineage(proposal, active);
        when(plans.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.decide(userId, proposal.getId(), new PlanDtos.PlanDecisionRequest(true, 0L));

        assertThat(proposal.getStatus()).isEqualTo(Plan.Status.accepted);
        verify(plans).saveAndFlush(proposal);
    }

    private Plan plan(UUID owner, ConversionRule rule, int repetitions) {
        int minutes = rule.getMinutesDelta() * repetitions;
        String snapshot = """
                [{"position":0,"actionType":"%s","targetMinutes":%d,"sourceId":"%s",\
                "ruleId":"%s","ruleLogicalKey":"%s","ruleVersion":%d,"ruleUnit":"%s",\
                "ruleMinutes":%d,"repetitions":%d}]
                """.formatted(rule.getHabitType().name(), minutes, rule.getSourceId(), rule.getId(),
                rule.getLogicalKey(), rule.getVersionNumber(), rule.getUnit(), rule.getMinutesDelta(), repetitions);
        return withId(Plan.proposed(owner, copy.planTitle(false, "Optional weekly advisory plan"), snapshot,
                minutes, Instant.parse("2026-08-21T00:00:00Z")));
    }

    private Plan withId(Plan plan) {
        if (plan.getId() == null) {
            ReflectionTestUtils.setField(plan, "id", UUID.randomUUID());
        }
        return plan;
    }

    private ConversionRule rule(int minutes, int version, boolean active) {
        ConversionRule rule = ConversionRule.create(ConversionRule.HabitType.activity, "Rule " + minutes,
                "{}", minutes, "per_unit", UUID.randomUUID(), active,
                Instant.parse("2026-08-20T00:00:00Z"));
        ReflectionTestUtils.setField(rule, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(rule, "versionNumber", version);
        return rule;
    }

    private PlanAction action(UUID planId, int position, ConversionRule rule, int target) {
        return PlanAction.create(planId, position, rule.getHabitType().name(), target,
                rule.getSourceId(), rule.getId(), Instant.parse("2026-08-21T00:00:00Z"));
    }

    private void activePlan(Plan plan) {
        when(plans.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, Plan.Status.accepted))
                .thenReturn(Optional.empty());
        when(plans.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, Plan.Status.proposed))
                .thenReturn(Optional.of(plan));
    }

    private void noActivePlan() {
        when(plans.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, Plan.Status.accepted))
                .thenReturn(Optional.empty());
        when(plans.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, Plan.Status.proposed))
                .thenReturn(Optional.empty());
    }

    private void stubLineage(Plan plan, ConversionRule rule) {
        when(actions.findByPlanIdOrderByPosition(plan.getId()))
                .thenReturn(List.of(action(plan.getId(), 0, rule, 30)));
        when(rules.findAllById(any())).thenReturn(List.of(rule));
    }
}
