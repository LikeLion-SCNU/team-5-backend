package org.example.naeilbank.domain.ledger;

import java.util.UUID;

public final class LedgerDtos {
    private LedgerDtos() {
    }

    public record BalanceResponse(
            long balanceMinutes,
            boolean protectionMode,
            String displayText,
            UUID protectionProposalId,
            boolean protectionSuggested
    ) {
    }
}
