package org.example.naeilbank.domain.ledger;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.ledger.LedgerDtos.GuardedBalance;
import org.example.naeilbank.domain.model.entity.BalanceViewEvent;
import org.example.naeilbank.domain.model.entity.ProtectionProposal;
import org.example.naeilbank.domain.model.repository.BalanceViewEventRepository;
import org.example.naeilbank.domain.model.repository.LedgerEntryRepository;
import org.example.naeilbank.domain.protection.ProtectionCopyPolicy;
import org.example.naeilbank.domain.protection.ProtectionService;
import org.example.naeilbank.entity.User;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LedgerService {
    private static final int PROTECTION_SUGGEST_THRESHOLD = 10;
    private static final Duration WINDOW = Duration.ofMinutes(60);

    private final LedgerEntryRepository ledgerRepository;
    private final BalanceViewEventRepository balanceViewEventRepository;
    private final UserRepository userRepository;
    private final ProtectionService protectionService;
    private final ProtectionCopyPolicy copyPolicy;
    private final Clock clock;

    @Transactional
    public GuardedBalance balance(UUID userId, String idempotencyKey) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        long balance = ledgerRepository.sumMinutesByUserId(userId);
        Instant now = Instant.now(clock);
        balanceViewEventRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .orElseGet(() -> balanceViewEventRepository.save(BalanceViewEvent.create(userId, balance, idempotencyKey, now)));
        long count = balanceViewEventRepository.countByUserIdAndCreatedAtGreaterThanEqual(userId, now.minus(WINDOW));
        UUID proposalId = null;
        boolean suggested = false;
        if (!user.isProtectionMode() && count == PROTECTION_SUGGEST_THRESHOLD) {
            var response = protectionService.suggest(userId, "balance-window:" + now.minus(WINDOW) + ":" + now);
            suggested = response.status() == ProtectionProposal.Status.proposed;
            proposalId = response.id();
        }
        return new GuardedBalance(
                balance,
                user.isProtectionMode(),
                copyPolicy.balanceText(user.isProtectionMode(), balance),
                proposalId,
                suggested
        );
    }
}
