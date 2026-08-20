package org.example.naeilbank.domain.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class LedgerDtos {
    private LedgerDtos() {
    }

    public record BalanceResponse(
            long balanceMinutes,
            long previousDayDeltaMinutes,
            LocalDate asOfDate,
            String timezone
    ) {
    }

    public record LedgerLine(
            long entryId,
            LocalDate entryDate,
            Instant createdAt,
            String habitType,
            int minutesDelta,
            String displayText,
            String sourceType,
            UUID sourceId
    ) {
    }

    public record StatementDay(
            LocalDate entryDate,
            long dailyNetMinutes,
            long cumulativeBalanceMinutes,
            long previousDayDeltaMinutes,
            List<LedgerLine> lines
    ) {
    }

    public record StatementResponse(
            LocalDate from,
            LocalDate to,
            int page,
            int size,
            boolean hasNext,
            long totalDays,
            List<StatementDay> days
    ) {
    }

    public sealed interface TrendPoint permits DailyTrendPoint, WeeklyTrendPoint {
    }

    public record DailyTrendPoint(LocalDate date, long netMinutes, long cumulativeBalanceMinutes)
            implements TrendPoint {
    }

    public record WeeklyTrendPoint(
            LocalDate weekStart,
            LocalDate weekEnd,
            long netMinutes,
            long cumulativeBalanceMinutes
    ) implements TrendPoint {
    }

    public record TrendResponse(String window, List<? extends TrendPoint> points) {
    }

}
