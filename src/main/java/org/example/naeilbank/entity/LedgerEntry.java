package org.example.naeilbank.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
@Getter
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "habit_type", nullable = false)
    private ConversionRule.HabitType habitType;

    @Column(name = "minutes_delta", nullable = false)
    private int minutesDelta;

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(name = "ref_type")
    private String referenceType;

    @Column(name = "ref_id")
    private UUID referenceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public static LedgerEntry posted(UUID userId, LocalDate entryDate,
                                     ConversionRule.HabitType habitType, int minutesDelta,
                                     UUID ruleId, String referenceType, UUID referenceId,
                                     Instant createdAt) {
        LedgerEntry entry = new LedgerEntry();
        entry.userId = userId;
        entry.entryDate = entryDate;
        entry.habitType = habitType;
        entry.minutesDelta = minutesDelta;
        entry.ruleId = ruleId;
        entry.referenceType = referenceType;
        entry.referenceId = referenceId;
        entry.createdAt = createdAt;
        return entry;
    }
}
