package org.example.naeilbank.domain.protection;

import org.example.naeilbank.domain.ledger.LedgerDtos.GuardedBalance;
import org.example.naeilbank.domain.ledger.LedgerService;
import org.example.naeilbank.domain.model.entity.BalanceViewEvent;
import org.example.naeilbank.domain.model.entity.ProtectionProposal;
import org.example.naeilbank.domain.model.repository.BalanceViewEventRepository;
import org.example.naeilbank.domain.model.repository.LedgerEntryRepository;
import org.example.naeilbank.domain.model.repository.ProtectionEventRepository;
import org.example.naeilbank.domain.model.repository.ProtectionProposalRepository;
import org.example.naeilbank.entity.User;
import org.example.naeilbank.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Todo20ManualQaTest {
    @Test
    void tenthViewOffersOnceAndAcceptanceChangesOnlyModeAndCopy() {
        Instant now = Instant.parse("2026-08-20T12:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        UUID userId = UUID.randomUUID();
        User user = User.local("owner@example.com", "pw");
        UserRepository users = mock(UserRepository.class);
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(users.findById(userId)).thenReturn(Optional.of(user));

        LedgerEntryRepository ledger = mock(LedgerEntryRepository.class);
        when(ledger.sumMinutesByUserId(userId)).thenReturn(-42L);
        BalanceViewEventRepository views = mock(BalanceViewEventRepository.class);
        Set<String> viewKeys = new HashSet<>();
        when(views.findByUserIdAndIdempotencyKey(eq(userId), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(1);
            return viewKeys.contains(key)
                    ? Optional.of(BalanceViewEvent.create(userId, -42L, key, now))
                    : Optional.empty();
        });
        when(views.save(any())).thenAnswer(invocation -> {
            BalanceViewEvent event = invocation.getArgument(0);
            viewKeys.add(event.getIdempotencyKey());
            return event;
        });
        when(views.countByUserIdAndCreatedAtGreaterThanEqual(eq(userId), any())).thenAnswer(invocation -> (long) viewKeys.size());

        ProtectionProposalRepository proposals = mock(ProtectionProposalRepository.class);
        AtomicReference<ProtectionProposal> storedProposal = new AtomicReference<>();
        when(proposals.findByUserIdAndIdempotencyKey(eq(userId), any())).thenAnswer(invocation -> Optional.ofNullable(storedProposal.get())
                .filter(proposal -> proposal.getIdempotencyKey().equals(invocation.getArgument(1))));
        when(proposals.findByUserIdAndStatus(eq(userId), any())).thenAnswer(invocation -> Optional.ofNullable(storedProposal.get())
                .filter(proposal -> proposal.getStatus() == invocation.getArgument(1))
                .map(List::of)
                .orElse(List.of()));
        when(proposals.findFirstByUserIdOrderByCreatedAtDesc(userId)).thenAnswer(invocation -> Optional.ofNullable(storedProposal.get()));
        when(proposals.findById(any())).thenAnswer(invocation -> Optional.ofNullable(storedProposal.get())
                .filter(proposal -> proposal.getId().equals(invocation.getArgument(0))));
        when(proposals.save(any())).thenAnswer(invocation -> {
            ProtectionProposal proposal = invocation.getArgument(0);
            ReflectionTestUtils.setField(proposal, "id", UUID.randomUUID());
            storedProposal.set(proposal);
            return proposal;
        });
        ProtectionEventRepository events = mock(ProtectionEventRepository.class);
        when(events.findByUserIdAndIdempotencyKey(eq(userId), any())).thenReturn(Optional.empty());

        ProtectionService protection = new ProtectionService(proposals, events, users, clock);
        LedgerService ledgerService = new LedgerService(ledger, views, users, protection, clock);

        GuardedBalance ninth = null;
        for (int view = 1; view <= 9; view++) {
            ninth = ledgerService.balance(userId, "view-" + view);
        }
        GuardedBalance tenth = ledgerService.balance(userId, "view-10");
        protection.accept(userId, tenth.protectionProposalId());
        GuardedBalance protectedView = ledgerService.balance(userId, "view-11");

        assertThat(ninth.protectionSuggested()).isFalse();
        assertThat(tenth.protectionSuggested()).isTrue();
        assertThat(tenth.protectionProposalId()).isNotNull();
        assertThat(user.isProtectionMode()).isTrue();
        assertThat(protectedView.balanceMinutes()).isEqualTo(tenth.balanceMinutes()).isEqualTo(-42L);
        assertThat(protectedView.displayText()).isNotEqualTo(tenth.displayText());
        assertThat(storedProposal.get().getStatus()).isEqualTo(ProtectionProposal.Status.accepted);
    }
}
