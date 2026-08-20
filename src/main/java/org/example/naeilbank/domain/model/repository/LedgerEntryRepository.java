package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.entity.LedgerEntry;
import org.springframework.data.repository.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerEntryRepository extends Repository<LedgerEntry, Long> {
    LedgerEntry save(LedgerEntry entry);

    LedgerEntry saveAndFlush(LedgerEntry entry);

    Optional<LedgerEntry> findByIdAndUserId(Long id, UUID userId);

    List<LedgerEntry> findByUserIdAndEntryDateOrderById(UUID userId, LocalDate entryDate);
}
