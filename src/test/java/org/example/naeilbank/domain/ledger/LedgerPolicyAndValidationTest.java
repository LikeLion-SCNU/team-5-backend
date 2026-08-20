package org.example.naeilbank.domain.ledger;

import org.example.naeilbank.domain.protection.ProtectionCopyPolicy;
import org.example.naeilbank.entity.User;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class LedgerPolicyAndValidationTest {
    private final LedgerQueryRepository queryRepository = mock(LedgerQueryRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-20T07:30:00Z"),
            ZoneOffset.UTC);
    private final LedgerTimezoneResolver timezoneResolver = new LedgerTimezoneResolver();
    private final LedgerQueryService service = new LedgerQueryService(queryRepository,
            userRepository, timezoneResolver, new ProtectionCopyPolicy(), clock);

    @Test
    void protectionCopyChangesOnlyStatementTextForNegativeValues() {
        ProtectionCopyPolicy policy = new ProtectionCopyPolicy();

        assertThat(policy.ledgerLineText(-10, false)).isEqualTo("출금 10분");
        assertThat(policy.ledgerLineText(-10, true)).isEqualTo("회복 조정 10분");
        assertThat(policy.ledgerLineText(25, false)).isEqualTo(policy.ledgerLineText(25, true));
        assertThat(policy.ledgerLineText(Integer.MIN_VALUE, false))
                .isEqualTo("출금 2147483648분");
    }

    @Test
    void timezoneResolverAlwaysUsesFixedKstPolicy() {
        assertThat(timezoneResolver.resolve().getId()).isEqualTo("Asia/Seoul");
    }

    @Test
    void statementsRejectInvalidDateRangeAndPageShape() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> service.statements(userId, LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 19), 0, 20))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_LEDGER_DATE_RANGE));

        assertThatThrownBy(() -> service.statements(userId, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 20), -1, 20))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_LEDGER_PAGE));
        assertThatThrownBy(() -> service.statements(userId, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 20), Integer.MAX_VALUE, 100))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_LEDGER_PAGE));
    }

    @Test
    void balanceUsesKstDateAcrossUtcMidnightBoundary() {
        UUID userId = UUID.randomUUID();
        Clock boundaryClock = Clock.fixed(Instant.parse("2026-08-19T15:30:00Z"), ZoneOffset.UTC);
        LedgerQueryService boundaryService = new LedgerQueryService(queryRepository,
                userRepository, timezoneResolver, new ProtectionCopyPolicy(), boundaryClock);
        when(queryRepository.balanceMinutes(userId)).thenReturn(10L);
        when(queryRepository.dailyNet(userId, LocalDate.of(2026, 8, 19))).thenReturn(3L);

        var response = boundaryService.balance(userId);

        assertThat(response.asOfDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(response.previousDayDeltaMinutes()).isEqualTo(3L);
        assertThat(response.timezone()).isEqualTo("Asia/Seoul");
    }

    @Test
    void weeklyTrendUsesIsoMondayBoundaries() {
        UUID userId = UUID.randomUUID();
        when(queryRepository.weeklyTrend(userId, LocalDate.of(2026, 7, 27),
                LocalDate.of(2026, 8, 17))).thenReturn(List.of(
                new LedgerQueryRepository.WeeklyPointRow(LocalDate.of(2026, 7, 27),
                        LocalDate.of(2026, 8, 2), 10, 10)
        ));

        var response = service.weeklyTrend(userId, LocalDate.of(2026, 8, 23));

        assertThat(response.window()).isEqualTo("weekly");
        assertThat(response.points()).hasSize(1);
        verify(queryRepository).weeklyTrend(userId, LocalDate.of(2026, 7, 27),
                LocalDate.of(2026, 8, 17));
    }

    @Test
    void statementsReadOneAtomicPageSnapshot() {
        UUID userId = UUID.randomUUID();
        LocalDate first = LocalDate.of(2026, 8, 19);
        LocalDate second = LocalDate.of(2026, 8, 18);
        when(userRepository.findById(userId)).thenReturn(Optional.of(User.local("ledger@example.com", "hash")));
        when(queryRepository.statementPage(userId, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 20), 3, 0L)).thenReturn(
                new LedgerQueryRepository.StatementPage(2, List.of(
                        new LedgerQueryRepository.StatementPageRow(2, first, -5, 5, 10,
                                new LedgerQueryRepository.LedgerRow(2L, first,
                                        Instant.parse("2026-08-19T03:00:01Z"), "sleep", -5,
                                        "meal_item", UUID.randomUUID())),
                        new LedgerQueryRepository.StatementPageRow(2, second, 10, 10, 0,
                                new LedgerQueryRepository.LedgerRow(1L, second,
                                        Instant.parse("2026-08-18T03:00:01Z"), "sleep", 10,
                                        "health_daily", UUID.randomUUID()))
                )));

        var response = service.statements(userId, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 20), 0, 2);

        assertThat(response.days()).hasSize(2);
        assertThat(response.days().get(0).lines()).hasSize(1);
        verify(userRepository).findById(userId);
        verify(queryRepository).statementPage(userId, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 20), 3, 0L);
        verifyNoMoreInteractions(userRepository);
        verifyNoMoreInteractions(queryRepository);
    }
}
