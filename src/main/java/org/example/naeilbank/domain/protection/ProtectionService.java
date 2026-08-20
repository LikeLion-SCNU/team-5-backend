package org.example.naeilbank.domain.protection;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.model.entity.ProtectionEvent;
import org.example.naeilbank.domain.model.entity.ProtectionProposal;
import org.example.naeilbank.domain.model.repository.ProtectionEventRepository;
import org.example.naeilbank.domain.model.repository.ProtectionProposalRepository;
import org.example.naeilbank.domain.protection.ProtectionDtos.ProtectionProposalResponse;
import org.example.naeilbank.domain.protection.ProtectionDtos.ProtectionStatusResponse;
import org.example.naeilbank.entity.User;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProtectionService {
    private final ProtectionProposalRepository proposalRepository;
    private final ProtectionEventRepository eventRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ProtectionStatusResponse status(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        return new ProtectionStatusResponse(user.isProtectionMode(), activeProposal(userId).map(this::toResponse).orElse(null));
    }

    @Transactional
    public ProtectionProposalResponse suggest(UUID userId, String idempotencyKey) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        Optional<ProtectionProposal> replay = proposalRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (replay.isPresent()) {
            return toResponse(replay.get());
        }
        Optional<ProtectionProposal> active = activeProposal(userId);
        if (active.isPresent()) {
            return toResponse(active.get());
        }
        Instant now = Instant.now(clock);
        try {
            ProtectionProposal proposal = proposalRepository.save(ProtectionProposal.proposed(userId, idempotencyKey, now));
            appendEvent(userId, ProtectionEvent.EventType.suggested, "suggested:" + proposal.getId(), "{\"reason\":\"balance_view_frequency\"}", now);
            return toResponse(proposal);
        } catch (DataIntegrityViolationException e) {
            return proposalRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                    .map(this::toResponse)
                    .orElseThrow(() -> e);
        }
    }

    @Transactional
    public ProtectionStatusResponse accept(UUID userId, UUID proposalId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        ProtectionProposal proposal = proposalRepository.findById(proposalId)
                .filter(candidate -> candidate.getUserId().equals(userId))
                .orElseThrow(() -> new AuthException(ErrorCode.ACCESS_DENIED));
        Instant now = Instant.now(clock);
        if (proposal.getStatus() == ProtectionProposal.Status.proposed) {
            proposal.accept(now);
            user.enableProtectionMode();
            appendEvent(userId, ProtectionEvent.EventType.accepted, "accepted:" + proposal.getId(), "{\"protectionMode\":true}", now);
        }
        return status(userId);
    }

    @Transactional
    public ProtectionStatusResponse disable(UUID userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        user.disableProtectionMode();
        appendEvent(userId, ProtectionEvent.EventType.manual_off, "manual-off:" + Instant.now(clock), "{\"protectionMode\":false}", Instant.now(clock));
        return status(userId);
    }

    private Optional<ProtectionProposal> activeProposal(UUID userId) {
        List<ProtectionProposal> proposals = proposalRepository.findByUserIdAndStatus(userId, ProtectionProposal.Status.proposed);
        return proposals.stream().findFirst();
    }

    private void appendEvent(UUID userId, ProtectionEvent.EventType type, String key, String detailJson, Instant now) {
        if (eventRepository.findByUserIdAndIdempotencyKey(userId, key).isEmpty()) {
            eventRepository.save(ProtectionEvent.create(userId, type, detailJson, key, now));
        }
    }

    private ProtectionProposalResponse toResponse(ProtectionProposal proposal) {
        return new ProtectionProposalResponse(proposal.getId(), proposal.getStatus(), proposal.getCreatedAt(), proposal.getRespondedAt());
    }
}
