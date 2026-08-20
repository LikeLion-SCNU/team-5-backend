package org.example.naeilbank.domain.conversion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.naeilbank.domain.conversion.ConversionModels.ConversionCommand;
import org.example.naeilbank.domain.model.repository.ConversionRuleRepository;
import org.example.naeilbank.domain.model.repository.LedgerEntryRepository;
import org.example.naeilbank.domain.model.repository.SourceRepository;
import org.example.naeilbank.entity.ConversionRule;
import org.example.naeilbank.entity.LedgerEntry;
import org.example.naeilbank.entity.Source;
import org.example.naeilbank.entity.User;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversionServiceTest {
    @Mock UserRepository userRepository;
    @Mock ConversionRuleRepository ruleRepository;
    @Mock SourceRepository sourceRepository;
    @Mock LedgerEntryRepository ledgerRepository;
    @Mock ConversionPostingRepository postingRepository;
    @Mock ConversionSourceEventGuard sourceEventGuard;
    private ConversionService service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        service = new ConversionService(userRepository, ruleRepository, sourceRepository,
                ledgerRepository, postingRepository, sourceEventGuard, new ExactConversionEngine(),
                new ObjectMapper(), Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC));
        userId = UUID.randomUUID();
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(mock(User.class)));
        when(postingRepository.findByUserIdAndSourceEventTypeAndSourceEventIdAndHabitType(
                any(), any(), any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void persistedFixtureRuleCreatesExactReceiptAndAtomicLineagePair() {
        Source source = source(true);
        ConversionRule rule = rule(source.getId(), true, "{}");
        when(ruleRepository.findActiveForConversion(ConversionRule.HabitType.activity,
                "per_1000_steps")).thenReturn(List.of(rule));
        when(sourceRepository.findByIdAndActiveTrueForShare(source.getId())).thenReturn(Optional.of(source));
        LedgerEntry savedLedger = mock(LedgerEntry.class);
        when(savedLedger.getId()).thenReturn(42L);
        when(ledgerRepository.saveAndFlush(any())).thenReturn(savedLedger);
        when(postingRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var receipt = service.convert(userId, command());

        assertThat(receipt.ledgerEntryId()).isEqualTo(42L);
        assertThat(receipt.postedSeconds()).isEqualTo(1050L);
        assertThat(receipt.ledgerMinutes()).isEqualTo(18);
        assertThat(receipt.replayed()).isFalse();
        verify(ledgerRepository).saveAndFlush(any());
        verify(postingRepository).saveAndFlush(any());
    }

    @Test
    void activeRuleTurnoverRetriesOneFreshSnapshotBeforeFailingClosed() {
        Source source = source(true);
        ConversionRule replacement = rule(source.getId(), true, "{}");
        when(ruleRepository.findActiveForConversion(ConversionRule.HabitType.activity,
                "per_1000_steps")).thenReturn(List.of(), List.of(replacement));
        when(sourceRepository.findByIdAndActiveTrueForShare(source.getId())).thenReturn(Optional.of(source));
        LedgerEntry savedLedger = mock(LedgerEntry.class);
        when(savedLedger.getId()).thenReturn(42L);
        when(ledgerRepository.saveAndFlush(any())).thenReturn(savedLedger);
        when(postingRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var receipt = service.convert(userId, command());

        assertThat(receipt.ruleId()).isEqualTo(replacement.getId());
        verify(ruleRepository, times(2)).findActiveForConversion(
                ConversionRule.HabitType.activity, "per_1000_steps");
    }

    @Test
    void missingAmbiguousInactiveAndConditionalRulesWriteNothing() {
        when(ruleRepository.findActiveForConversion(any(), any()))
                .thenReturn(List.of())
                .thenReturn(List.of())
                .thenReturn(List.of(rule(UUID.randomUUID(), true, "{}"),
                        rule(UUID.randomUUID(), true, "{}")))
                .thenReturn(List.of(rule(UUID.randomUUID(), true, "{}")))
                .thenReturn(List.of(rule(UUID.randomUUID(), true, "{\"threshold\":1}")));

        assertError(ErrorCode.CONVERSION_RULE_UNAVAILABLE);
        assertError(ErrorCode.CONVERSION_RULE_AMBIGUOUS);
        assertError(ErrorCode.CONVERSION_RULE_UNAVAILABLE);
        Source active = source(true);
        when(sourceRepository.findByIdAndActiveTrueForShare(any())).thenReturn(Optional.of(active));
        assertError(ErrorCode.CONVERSION_CONDITION_UNSUPPORTED);

        verify(ledgerRepository, never()).saveAndFlush(any());
        verify(postingRepository, never()).saveAndFlush(any());
    }

    @Test
    void unverifiableSourceEventAndHugeExponentFailBeforeAnyAppendOnlyWrite() {
        doThrow(new AuthException(ErrorCode.CONVERSION_SOURCE_EVENT_NOT_FOUND))
                .when(sourceEventGuard).requireOwned(any(), any());
        assertError(ErrorCode.CONVERSION_SOURCE_EVENT_NOT_FOUND);
        verify(ledgerRepository, never()).saveAndFlush(any());
        verify(postingRepository, never()).saveAndFlush(any());

        ConversionCommand huge = new ConversionCommand(UUID.randomUUID(),
                ConversionSourceType.HEALTH_DAILY, HabitCategory.ACTIVITY,
                ConversionUnit.PER_1000_STEPS,
                new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE), LocalDate.of(2026, 8, 20));
        assertThatThrownBy(() -> service.convert(userId, huge))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.CONVERSION_VALUE_OUT_OF_RANGE));
    }

    private void assertError(ErrorCode code) {
        assertThatThrownBy(() -> service.convert(userId, command()))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }

    private ConversionCommand command() {
        return new ConversionCommand(UUID.randomUUID(), ConversionSourceType.HEALTH_DAILY,
                HabitCategory.ACTIVITY, ConversionUnit.PER_1000_STEPS,
                new BigDecimal("2500"), LocalDate.of(2026, 8, 20));
    }

    private ConversionRule rule(UUID sourceId, boolean active, String condition) {
        ConversionRule rule = ConversionRule.create(ConversionRule.HabitType.activity, "TEST_FIXTURE activity",
                condition, 7, "per_1000_steps", sourceId, active,
                Instant.parse("2026-08-20T00:00:00Z"));
        ReflectionTestUtils.setField(rule, "id", UUID.randomUUID());
        return rule;
    }

    private Source source(boolean active) {
        Source source = Source.create("TEST_FIXTURE source", null, null, null,
                "https://example.test/fixture", "fixture", "fixture", "fixture", active,
                Instant.parse("2026-08-20T00:00:00Z"));
        ReflectionTestUtils.setField(source, "id", UUID.randomUUID());
        return source;
    }
}
