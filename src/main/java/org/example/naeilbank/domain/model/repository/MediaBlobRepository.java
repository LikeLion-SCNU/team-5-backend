package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.MediaBlob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MediaBlobRepository extends JpaRepository<MediaBlob, UUID> {
    Optional<MediaBlob> findByIdAndUserId(UUID id, UUID userId);

    Optional<MediaBlob> findByUserIdAndPurposeAndSha256(UUID userId, MediaBlob.Purpose purpose, String sha256);
}
