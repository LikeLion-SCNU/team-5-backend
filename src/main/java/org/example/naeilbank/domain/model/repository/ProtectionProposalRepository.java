package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.ProtectionProposal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProtectionProposalRepository extends JpaRepository<ProtectionProposal, UUID> {
    Optional<ProtectionProposal> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    List<ProtectionProposal> findByUserIdAndStatus(UUID userId, ProtectionProposal.Status status);

    Optional<ProtectionProposal> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);
}
