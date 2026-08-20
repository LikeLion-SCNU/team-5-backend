package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.entity.LedgerEntry;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    @Query("select coalesce(sum(e.minutesDelta), 0) from LedgerEntry e where e.userId = :userId")
    long sumMinutesByUserId(@Param("userId") UUID userId);
}
