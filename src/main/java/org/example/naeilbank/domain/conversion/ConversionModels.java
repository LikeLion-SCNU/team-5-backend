package org.example.naeilbank.domain.conversion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public final class ConversionModels {
    private ConversionModels() {
    }

    public record ConversionInput(HabitCategory category, ConversionUnit unit, BigDecimal value) {
    }

    public record RuleTerms(
            HabitCategory category,
            ConversionUnit unit,
            int minutesPerUnit,
            boolean active,
            boolean sourceActive
    ) {
    }

    public record ExactResult(
            BigDecimal normalizedUnits,
            BigDecimal exactSeconds,
            long postedSeconds,
            int ledgerMinutes
    ) {
    }

    public record ConversionCommand(
            UUID sourceEventId,
            ConversionSourceType sourceType,
            HabitCategory category,
            ConversionUnit unit,
            BigDecimal value,
            LocalDate entryDate
    ) {
    }

    public record ConversionReceipt(
            long ledgerEntryId,
            UUID ruleId,
            long postedSeconds,
            int ledgerMinutes,
            boolean replayed
    ) {
    }

    record SnapshotBundle(
            String ruleJson,
            String sourceJson,
            String inputJson,
            String resultJson
    ) {
    }
}
