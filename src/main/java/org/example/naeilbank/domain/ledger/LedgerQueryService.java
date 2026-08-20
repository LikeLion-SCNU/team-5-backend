package org.example.naeilbank.domain.ledger;

import org.example.naeilbank.domain.ledger.LedgerDtos.BalanceResponse;
import org.example.naeilbank.domain.ledger.LedgerDtos.DailyTrendPoint;
import org.example.naeilbank.domain.ledger.LedgerDtos.LedgerLine;
import org.example.naeilbank.domain.ledger.LedgerDtos.StatementDay;
import org.example.naeilbank.domain.ledger.LedgerDtos.StatementResponse;
import org.example.naeilbank.domain.ledger.LedgerDtos.TrendResponse;
import org.example.naeilbank.domain.ledger.LedgerDtos.WeeklyTrendPoint;
import org.example.naeilbank.domain.protection.ProtectionCopyPolicy;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LedgerQueryService {
    private static final int MAX_STATEMENT_SIZE = 100;
    private static final long MAX_STATEMENT_OFFSET = 1_830;

    private final LedgerQueryRepository queryRepository;
    private final UserRepository userRepository;
    private final LedgerTimezoneResolver timezoneResolver;
    private final ProtectionCopyPolicy copyPolicy;
    private final Clock clock;

    public LedgerQueryService(LedgerQueryRepository queryRepository,
                              UserRepository userRepository,
                              LedgerTimezoneResolver timezoneResolver,
                              ProtectionCopyPolicy copyPolicy,
                              Clock clock) {
        this.queryRepository = queryRepository;
        this.userRepository = userRepository;
        this.timezoneResolver = timezoneResolver;
        this.copyPolicy = copyPolicy;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BalanceResponse balance(UUID userId) {
        ZoneId zone = timezoneResolver.resolve();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        return new BalanceResponse(queryRepository.balanceMinutes(userId),
                queryRepository.dailyNet(userId, today.minusDays(1)), today, zone.getId());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatementResponse statements(UUID userId, LocalDate from, LocalDate to, int page, int size) {
        Range range = validateRange(from, to, timezoneResolver.resolve());
        validatePage(page, size);
        boolean protectionMode = protectionMode(userId);
        long offset = (long) page * size;
        LedgerQueryRepository.StatementPage statementPage = queryRepository.statementPage(
                userId, range.from(), range.to(), size + 1, offset);
        Map<LocalDate, DayRows> grouped = groupStatementRows(statementPage.rows());
        List<LocalDate> dates = List.copyOf(grouped.keySet());
        boolean hasNext = dates.size() > size;
        List<LocalDate> pageDates = hasNext ? dates.subList(0, size) : dates;
        List<StatementDay> days = pageDates.stream()
                .map(date -> statementDay(date, grouped.get(date), protectionMode))
                .toList();
        return new StatementResponse(range.from(), range.to(), page, size, hasNext,
                statementPage.totalDays(), days);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TrendResponse dailyTrend(UUID userId, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now(clock.withZone(timezoneResolver.resolve())) : to;
        LocalDate start = end.minusDays(6);
        List<DailyTrendPoint> points = queryRepository.dailyTrend(userId, start, end).stream()
                .map(row -> new DailyTrendPoint(row.date(), row.netMinutes(),
                        row.cumulativeBalanceMinutes()))
                .toList();
        return new TrendResponse("daily", points);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TrendResponse weeklyTrend(UUID userId, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now(clock.withZone(timezoneResolver.resolve())) : to;
        LocalDate lastStart = end.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate firstStart = lastStart.minusWeeks(3);
        List<WeeklyTrendPoint> points = queryRepository.weeklyTrend(userId, firstStart, lastStart).stream()
                .map(row -> new WeeklyTrendPoint(row.weekStart(), row.weekEnd(),
                        row.netMinutes(), row.cumulativeBalanceMinutes()))
                .toList();
        return new TrendResponse("weekly", points);
    }

    private StatementDay statementDay(LocalDate date, DayRows rows,
                                      boolean protectionMode) {
        long viewNet = rows.netMinutes();
        long lineNet = rows.lines().stream()
                .mapToLong(LedgerQueryRepository.LedgerRow::minutesDelta).sum();
        if (viewNet != lineNet) {
            throw new IllegalStateException("ledger view reconciliation failed for " + date);
        }
        List<LedgerLine> lines = rows.lines().stream()
                .map(row -> new LedgerLine(row.id(), row.entryDate(), row.createdAt(),
                        row.habitType(), row.minutesDelta(),
                        copyPolicy.ledgerLineText(row.minutesDelta(), protectionMode),
                        row.sourceType(), row.sourceId()))
                .toList();
        return new StatementDay(date, viewNet, rows.cumulativeBalanceMinutes(),
                rows.previousDayDeltaMinutes(), lines);
    }

    private Map<LocalDate, DayRows> groupStatementRows(
            List<LedgerQueryRepository.StatementPageRow> rows) {
        Map<LocalDate, DayRows> grouped = new LinkedHashMap<>();
        for (LedgerQueryRepository.StatementPageRow row : rows) {
            DayRows day = grouped.computeIfAbsent(row.date(), ignored -> new DayRows(
                    row.netMinutes(), row.cumulativeBalanceMinutes(),
                    row.previousDayDeltaMinutes()));
            if (row.line() != null) {
                day.lines().add(row.line());
            }
        }
        return grouped;
    }

    private boolean protectionMode(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND))
                .isProtectionMode();
    }

    private Range validateRange(LocalDate from, LocalDate to, ZoneId zone) {
        LocalDate end = to == null ? LocalDate.now(clock.withZone(zone)) : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end)) {
            throw new AuthException(ErrorCode.INVALID_LEDGER_DATE_RANGE);
        }
        if (start.isBefore(end.minusYears(5))) {
            throw new AuthException(ErrorCode.INVALID_LEDGER_DATE_RANGE);
        }
        return new Range(start, end);
    }

    private void validatePage(int page, int size) {
        long offset = (long) page * size;
        if (page < 0 || size < 1 || size > MAX_STATEMENT_SIZE
                || offset > MAX_STATEMENT_OFFSET) {
            throw new AuthException(ErrorCode.INVALID_LEDGER_PAGE);
        }
    }

    private record Range(LocalDate from, LocalDate to) {
    }

    private record DayRows(long netMinutes, long cumulativeBalanceMinutes,
                           long previousDayDeltaMinutes,
                           List<LedgerQueryRepository.LedgerRow> lines) {
        private DayRows(long netMinutes, long cumulativeBalanceMinutes,
                        long previousDayDeltaMinutes) {
            this(netMinutes, cumulativeBalanceMinutes, previousDayDeltaMinutes,
                    new java.util.ArrayList<>());
        }
    }
}
