package org.example.naeilbank.domain.ledger;

import org.example.naeilbank.domain.ledger.LedgerDtos.GuardedBalance;
import org.example.naeilbank.domain.model.entity.BalanceViewEvent;
import org.example.naeilbank.domain.model.entity.ProtectionProposal;
import org.example.naeilbank.domain.model.repository.BalanceViewEventRepository;
import org.example.naeilbank.domain.model.repository.LedgerEntryRepository;
import org.example.naeilbank.domain.protection.ProtectionDtos.ProtectionProposalResponse;
import org.example.naeilbank.domain.protection.ProtectionService;
import org.example.naeilbank.entity.User;
import org.example.naeilbank.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LedgerServiceTest {
    private final LedgerEntryRepository ledger = mock(LedgerEntryRepository.class);
    private final BalanceViewEventRepository views = mock(BalanceViewEventRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ProtectionService protection = mock(ProtectionService.class);

    @Test
    void ninthBalanceViewDoesNotSuggestAndTenthSuggestsOnce() {
        UUID userId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        User user = User.local("u@example.com", "pw");
        AtomicLong count = new AtomicLong();
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(ledger.sumMinutesByUserId(userId)).thenReturn(-30L);
        when(views.findByUserIdAndIdempotencyKey(eq(userId), any())).thenReturn(Optional.empty());
        when(views.save(any(BalanceViewEvent.class))).thenAnswer(invocation -> {
            count.incrementAndGet();
            return invocation.getArgument(0);
        });
        when(views.countByUserIdAndCreatedAtGreaterThanEqual(eq(userId), any())).thenAnswer(invocation -> count.get());
        when(protection.suggest(eq(userId), any())).thenReturn(new ProtectionProposalResponse(
                proposalId,
                ProtectionProposal.Status.proposed,
                Instant.parse("2026-08-20T00:00:00Z"),
                null
        ));
        LedgerService service = service(Instant.parse("2026-08-20T00:00:00Z"));

        for (int i = 1; i <= 9; i++) {
            assertThat(service.balance(userId, "key-" + i).protectionSuggested()).isFalse();
        }
        GuardedBalance tenth = service.balance(userId, "key-10");
        GuardedBalance eleventh = service.balance(userId, "key-11");

        assertThat(tenth.protectionSuggested()).isTrue();
        assertThat(tenth.protectionProposalId()).isEqualTo(proposalId);
        assertThat(eleventh.protectionSuggested()).isFalse();
        verify(protection).suggest(eq(userId), any());
    }

    @Test
    void expiredWindowDoesNotSuggestBelowThreshold() {
        UUID userId = UUID.randomUUID();
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(User.local("u@example.com", "pw")));
        when(ledger.sumMinutesByUserId(userId)).thenReturn(-30L);
        when(views.findByUserIdAndIdempotencyKey(eq(userId), any())).thenReturn(Optional.empty());
        when(views.save(any(BalanceViewEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(views.countByUserIdAndCreatedAtGreaterThanEqual(eq(userId), any())).thenReturn(1L);

        GuardedBalance response = service(Instant.parse("2026-08-20T02:00:00Z")).balance(userId, "after-window");

        assertThat(response.protectionSuggested()).isFalse();
        verify(protection, never()).suggest(any(), any());
    }

    @Test
    void protectionModeChangesOnlyDisplayTextNotBalance() {
        UUID userId = UUID.randomUUID();
        User user = User.local("u@example.com", "pw");
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(ledger.sumMinutesByUserId(userId)).thenReturn(-42L);
        when(views.findByUserIdAndIdempotencyKey(eq(userId), any())).thenReturn(Optional.empty());
        when(views.save(any(BalanceViewEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(views.countByUserIdAndCreatedAtGreaterThanEqual(eq(userId), any())).thenReturn(1L);
        LedgerService service = service(Instant.parse("2026-08-20T00:00:00Z"));

        GuardedBalance before = service.balance(userId, "before");
        user.enableProtectionMode();
        GuardedBalance after = service.balance(userId, "after");

        assertThat(after.balanceMinutes()).isEqualTo(before.balanceMinutes());
        assertThat(after.displayText()).isNotEqualTo(before.displayText());
    }

    private LedgerService service(Instant instant) {
        return new LedgerService(ledger, views, users, protection, Clock.fixed(instant, ZoneOffset.UTC));
    }
}
