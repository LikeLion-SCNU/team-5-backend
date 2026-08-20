package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.DeletionRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeletionRequestRepository extends JpaRepository<DeletionRequest, UUID> {
    Optional<DeletionRequest> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    List<DeletionRequest> findByStatusIn(List<DeletionRequest.Status> statuses);
}
