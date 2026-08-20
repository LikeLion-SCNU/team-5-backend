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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProtectionService {
    private static final Duration PROPOSAL_COOLDOWN = Duration.ofMinutes(60);

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
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        Optional<ProtectionProposal> replay = proposalRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (replay.isPresent()) {
            return toResponse(replay.get());
        }
        if (user.isProtectionMode()) {
            return null;
        }
        Optional<ProtectionProposal> active = activeProposal(userId);
        if (active.isPresent()) {
            return toResponse(active.get());
        }
        Instant now = Instant.now(clock);
        Optional<ProtectionProposal> latest = proposalRepository.findFirstByUserIdOrderByCreatedAtDesc(userId);
        if (latest.filter(previous -> !previous.getCreatedAt().isBefore(now.minus(PROPOSAL_COOLDOWN))).isPresent()) {
            return toResponse(latest.orElseThrow());
        }
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
    public ProtectionStatusResponse reject(UUID userId, UUID proposalId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        ProtectionProposal proposal = proposalRepository.findById(proposalId)
                .filter(candidate -> candidate.getUserId().equals(userId))
                .orElseThrow(() -> new AuthException(ErrorCode.ACCESS_DENIED));
        Instant now = Instant.now(clock);
        if (proposal.getStatus() == ProtectionProposal.Status.proposed) {
            proposal.decline(now);
            appendEvent(userId, ProtectionEvent.EventType.declined, "declined:" + proposal.getId(), "{\"protectionMode\":" + user.isProtectionMode() + "}", now);
        }
        return status(userId);
    }

    @Transactional
    public ProtectionStatusResponse setMode(UUID userId, boolean enabled, String idempotencyKey) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        String eventKey = "manual:" + idempotencyKey;
        if (eventRepository.findByUserIdAndIdempotencyKey(userId, eventKey).isPresent()) {
            return status(userId);
        }
        if (user.isProtectionMode() == enabled) {
            return status(userId);
        }
        if (enabled) {
            user.enableProtectionMode();
        } else {
            user.disableProtectionMode();
        }
        Instant now = Instant.now(clock);
        appendEvent(
                userId,
                enabled ? ProtectionEvent.EventType.manual_on : ProtectionEvent.EventType.manual_off,
                eventKey,
                "{\"protectionMode\":" + enabled + "}",
                now
        );
        return status(userId);
    }

    @Transactional
    public ProtectionStatusResponse disable(UUID userId) {
        return setMode(userId, false, UUID.randomUUID().toString());
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
