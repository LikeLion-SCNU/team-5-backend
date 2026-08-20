package org.example.naeilbank.domain.protection;

import jakarta.validation.constraints.NotNull;
import org.example.naeilbank.domain.model.entity.ProtectionProposal;

import java.time.Instant;
import java.util.UUID;

public final class ProtectionDtos {
    private ProtectionDtos() {
    }

    public record ProtectionProposalResponse(
            UUID id,
            ProtectionProposal.Status status,
            Instant createdAt,
            Instant respondedAt
    ) {
    }

    public record ProtectionStatusResponse(
            boolean protectionMode,
            ProtectionProposalResponse activeProposal
    ) {
    }

    public record ProtectionModeRequest(@NotNull Boolean enabled) {
    }
}
