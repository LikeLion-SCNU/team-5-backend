package org.example.naeilbank.domain.face;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.model.entity.FaceSimulation;
import org.example.naeilbank.domain.model.repository.FaceSimulationRepository;
import org.example.naeilbank.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class FaceSimulationProcessor {
    private static final Duration STALE_PROCESSING_AFTER = Duration.ofMinutes(15);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_BASE_DELAY = Duration.ofMinutes(1);

    private final FaceSimulationRepository simulationRepository;
    private final UserRepository userRepository;
    private final FaceSimulationGenerationService generationService;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public boolean processOneDue() {
        ClaimedSimulation claimed = inTransaction(this::claimNext);
        if (claimed == null) {
            return false;
        }
        try {
            generationService.process(claimed);
        } catch (FaceGenerationException e) {
            inTransaction(() -> retryOrFail(claimed, e.reason(), isRetryable(e.reason())));
        } catch (RuntimeException e) {
            inTransaction(() -> retryOrFail(
                    claimed,
                    FaceGenerationException.Reason.upstream_failure,
                    true
            ));
        }
        return true;
    }

    ClaimedSimulation claimNext() {
        Instant now = Instant.now(clock);
        Instant staleBefore = now.minus(STALE_PROCESSING_AFTER);
        UUID candidateId = simulationRepository.findNextDueId(now, staleBefore)
                .orElse(null);
        if (candidateId == null) {
            return null;
        }
        FaceSimulation candidate = simulationRepository.findById(candidateId).orElse(null);
        if (candidate == null || userRepository.findByIdForUpdate(candidate.getUserId()).isEmpty()) {
            return null;
        }
        FaceSimulation simulation = simulationRepository
                .findOwnedForUpdate(candidateId, candidate.getUserId())
                .orElse(null);
        if (simulation == null || !simulation.isDue(now, staleBefore)) {
            return null;
        }
        if (simulation.getAttemptCount() >= MAX_ATTEMPTS) {
            simulation.markFailed("retry_exhausted", now);
            return null;
        }
        UUID claimToken = UUID.randomUUID();
        simulation.markProcessing(claimToken, now);
        return new ClaimedSimulation(
                simulation.getId(),
                simulation.getUserId(),
                simulation.getSourceMediaId(),
                simulation.getTrendDescription(),
                claimToken
        );
    }

    void retryOrFail(
            ClaimedSimulation claimed,
            FaceGenerationException.Reason reason,
            boolean retryable
    ) {
        if (userRepository.findByIdForUpdate(claimed.userId()).isEmpty()) {
            return;
        }
        FaceSimulation simulation = simulationRepository
                .findOwnedForUpdate(claimed.id(), claimed.userId())
                .orElse(null);
        if (simulation == null || !simulation.matchesClaim(claimed.claimToken())) {
            return;
        }
        Instant now = Instant.now(clock);
        if (retryable && simulation.getAttemptCount() < MAX_ATTEMPTS) {
            simulation.scheduleRetry(reason.name(), now.plus(retryDelay(simulation.getAttemptCount())));
        } else {
            simulation.markFailed(reason.name(), now);
        }
    }

    private boolean isRetryable(FaceGenerationException.Reason reason) {
        return reason == FaceGenerationException.Reason.timeout
                || reason == FaceGenerationException.Reason.rate_limited
                || reason == FaceGenerationException.Reason.upstream_failure;
    }

    private Duration retryDelay(int attemptCount) {
        return RETRY_BASE_DELAY.multipliedBy(Math.max(1, attemptCount));
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transactionTemplate.execute(status -> action.get());
    }

    private void inTransaction(Runnable action) {
        transactionTemplate.executeWithoutResult(status -> action.run());
    }

    record ClaimedSimulation(
            UUID id,
            UUID userId,
            UUID sourceMediaId,
            String trendDescription,
            UUID claimToken
    ) {
    }
}
