package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.Consent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsentRepository extends Repository<Consent, UUID> {
    Consent save(Consent consent);

    Consent saveAndFlush(Consent consent);

    Optional<Consent> findByUserIdAndPurpose(UUID userId, Consent.Purpose purpose);

    Optional<Consent> findByIdAndUserId(UUID id, UUID userId);

    List<Consent> findAllByUserId(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Consent c where c.userId = :userId and c.purpose = :purpose")
    Optional<Consent> findForUpdate(
            @Param("userId") UUID userId,
            @Param("purpose") Consent.Purpose purpose
    );
}
