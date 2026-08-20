package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.Consent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ConsentRepository extends JpaRepository<Consent, UUID> {
    Optional<Consent> findByUserIdAndPurpose(UUID userId, Consent.Purpose purpose);
}
