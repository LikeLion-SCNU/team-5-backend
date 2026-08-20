package org.example.naeilbank.domain.protection;

import org.example.naeilbank.domain.model.entity.ProtectionEvent;
import org.example.naeilbank.domain.model.entity.ProtectionProposal;
import org.example.naeilbank.domain.model.repository.ProtectionEventRepository;
import org.example.naeilbank.domain.model.repository.ProtectionProposalRepository;
import org.example.naeilbank.entity.User;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.repository.UserRepository;
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

class ProtectionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private final ProtectionProposalRepository proposals = mock(ProtectionProposalRepository.class);
    private final ProtectionEventRepository events = mock(ProtectionEventRepository.class);
    private final UserRepository users = mock(UserRepository.class);

    @Test
    void recentDeclineAppliesCooldownButExpiredCooldownAllowsNewProposal() {
        UUID userId = UUID.randomUUID();
        User user = User.local("u@example.com", "pw");
        ProtectionProposal recent = proposal(userId, NOW.minusSeconds(3599));
        recent.decline(NOW.minusSeconds(3500));
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(proposals.findByUserIdAndIdempotencyKey(userId, "window-1")).thenReturn(Optional.empty());
        when(proposals.findByUserIdAndStatus(userId, ProtectionProposal.Status.proposed)).thenReturn(List.of());
        when(proposals.findFirstByUserIdOrderByCreatedAtDesc(userId)).thenReturn(Optional.of(recent));

        assertThat(service().suggest(userId, "window-1").status()).isEqualTo(ProtectionProposal.Status.declined);
        verify(proposals, never()).save(any());

        ProtectionProposal expired = proposal(userId, NOW.minusSeconds(3601));
        expired.decline(NOW.minusSeconds(3600));
        when(proposals.findFirstByUserIdOrderByCreatedAtDesc(userId)).thenReturn(Optional.of(expired));
        when(proposals.save(any())).thenAnswer(invocation -> {
            ProtectionProposal saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            return saved;
        });
        when(events.findByUserIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());

        assertThat(service().suggest(userId, "window-1")).isNotNull();
    }

    @Test
    void rejectIsOwnerScopedAuditedAndNeverEnablesMode() {
        UUID userId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        User user = User.local("u@example.com", "pw");
        ProtectionProposal proposal = proposal(userId, NOW.minusSeconds(10));
        ReflectionTestUtils.setField(proposal, "id", proposalId);
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(proposals.findById(proposalId)).thenReturn(Optional.of(proposal));
        when(events.findByUserIdAndIdempotencyKey(userId, "declined:" + proposalId)).thenReturn(Optional.empty());

        service().reject(userId, proposalId);

        assertThat(proposal.getStatus()).isEqualTo(ProtectionProposal.Status.declined);
        assertThat(user.isProtectionMode()).isFalse();
        verify(events).save(any(ProtectionEvent.class));

        UUID otherUser = UUID.randomUUID();
        when(users.findByIdForUpdate(otherUser)).thenReturn(Optional.of(User.local("other@example.com", "pw")));
        assertThatThrownBy(() -> service().reject(otherUser, proposalId)).isInstanceOf(AuthException.class);
    }

    @Test
    void manualOwnerToggleChangesOnlyModeAndWritesAuditForEachRealChange() {
        UUID userId = UUID.randomUUID();
        User user = User.local("u@example.com", "pw");
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(events.findByUserIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());

        service().setMode(userId, true, "on-request");
        assertThat(user.isProtectionMode()).isTrue();
        service().setMode(userId, false, "off-request");
        assertThat(user.isProtectionMode()).isFalse();

        verify(events, org.mockito.Mockito.times(2)).save(any(ProtectionEvent.class));
    }

    private ProtectionService service() {
        return new ProtectionService(proposals, events, users, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ProtectionProposal proposal(UUID userId, Instant createdAt) {
        return ProtectionProposal.proposed(userId, "proposal-" + createdAt, createdAt);
    }
}
