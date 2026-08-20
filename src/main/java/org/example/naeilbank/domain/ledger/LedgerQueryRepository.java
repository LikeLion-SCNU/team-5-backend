package org.example.naeilbank.domain.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
@ConditionalOnBean(JdbcTemplate.class)
public class LedgerQueryRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public LedgerQueryRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    long balanceMinutes(UUID userId) {
        return scalar("""
                select coalesce((
                    select total_minutes from v_balance where user_id = ?
                ), 0)
                """, userId);
    }

    long dailyNet(UUID userId, LocalDate date) {
        return scalar("""
                select coalesce((
                    select net_minutes from v_daily_net
                    where user_id = ? and entry_date = ?
                ), 0)
                """, userId, date);
    }

    StatementPage statementPage(UUID userId, LocalDate from, LocalDate to, int limit, long offset) {
        List<StatementPageRow> rows = namedJdbc.query("""
                with ordered_days as (
                    select entry_date, net_minutes,
                           sum(net_minutes) over (
                               order by entry_date rows between unbounded preceding and current row
                           ) as cumulative_minutes,
                           lag(entry_date) over (order by entry_date) as preceding_date,
                           lag(net_minutes, 1, 0) over (order by entry_date) as preceding_net_minutes
                    from v_daily_net
                    where user_id = :userId
                ), filtered_days as (
                    select entry_date, net_minutes, cumulative_minutes,
                           case when preceding_date = entry_date - 1
                                then preceding_net_minutes else 0 end as previous_net_minutes
                    from ordered_days
                    where entry_date between :from and :to
                ), page_days as (
                    select * from filtered_days
                    order by entry_date desc
                    limit :limit offset :offset
                ), metadata as (
                    select count(*) as total_days from filtered_days
                )
                select metadata.total_days, page_days.entry_date, page_days.net_minutes,
                       page_days.cumulative_minutes, page_days.previous_net_minutes,
                       entries.id, entries.created_at, entries.habit_type,
                       entries.minutes_delta, entries.ref_type, entries.ref_id
                from metadata
                left join page_days on true
                left join ledger_entries entries
                  on entries.user_id = :userId
                 and entries.entry_date = page_days.entry_date
                order by page_days.entry_date desc nulls last,
                         entries.created_at desc nulls last, entries.id desc nulls last
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("from", from)
                .addValue("to", to)
                .addValue("limit", limit)
                .addValue("offset", offset), (rs, row) -> statementPageRow(rs));
        if (rows.isEmpty()) {
            return new StatementPage(0, List.of());
        }
        long totalDays = rows.getFirst().totalDays();
        List<StatementPageRow> datedRows = new ArrayList<>(rows.size());
        for (StatementPageRow row : rows) {
            if (row.date() != null) {
                datedRows.add(row);
            }
        }
        return new StatementPage(totalDays, List.copyOf(datedRows));
    }

    List<DailyPointRow> dailyTrend(UUID userId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                with days as (
                    select generate_series(?::date, ?::date, interval '1 day')::date as entry_date
                ), prior as (
                    select coalesce(sum(net_minutes), 0) as before_minutes
                    from v_daily_net where user_id = ? and entry_date < ?
                ), points as (
                    select d.entry_date, coalesce(v.net_minutes, 0) as net_minutes
                    from days d left join v_daily_net v
                      on v.user_id = ? and v.entry_date = d.entry_date
                )
                select entry_date, net_minutes,
                       (select before_minutes from prior)
                         + sum(net_minutes) over (order by entry_date) as cumulative_minutes
                from points order by entry_date
                """, (rs, row) -> new DailyPointRow(
                rs.getObject("entry_date", LocalDate.class),
                rs.getLong("net_minutes"),
                rs.getLong("cumulative_minutes")), from, to, userId, from, userId);
    }

    List<WeeklyPointRow> weeklyTrend(UUID userId, LocalDate firstWeekStart, LocalDate lastWeekStart) {
        return jdbc.query("""
                with weeks as (
                    select generate_series(?::date, ?::date, interval '1 week')::date as week_start
                ), prior as (
                    select coalesce(sum(net_minutes), 0) as before_minutes
                    from v_daily_net where user_id = ? and entry_date < ?
                ), points as (
                    select w.week_start, w.week_start + 6 as week_end,
                           coalesce(sum(v.net_minutes), 0) as net_minutes
                    from weeks w left join v_daily_net v
                      on v.user_id = ? and v.entry_date between w.week_start and w.week_start + 6
                    group by w.week_start
                )
                select week_start, week_end, net_minutes,
                       (select before_minutes from prior)
                         + sum(net_minutes) over (order by week_start) as cumulative_minutes
                from points order by week_start
                """, (rs, row) -> new WeeklyPointRow(
                rs.getObject("week_start", LocalDate.class),
                rs.getObject("week_end", LocalDate.class),
                rs.getLong("net_minutes"),
                rs.getLong("cumulative_minutes")),
                firstWeekStart, lastWeekStart, userId, firstWeekStart, userId);
    }

    private long scalar(String sql, Object... args) {
        Number number = jdbc.queryForObject(sql, Number.class, args);
        return number == null ? 0 : number.longValue();
    }

    private LedgerRow ledgerRow(ResultSet rs) throws SQLException {
        UUID sourceId = rs.getObject("ref_id", UUID.class);
        return new LedgerRow(rs.getLong("id"), rs.getObject("entry_date", LocalDate.class),
                rs.getTimestamp("created_at").toInstant(), rs.getString("habit_type"),
                rs.getInt("minutes_delta"), rs.getString("ref_type"), sourceId);
    }

    private StatementPageRow statementPageRow(ResultSet rs) throws SQLException {
        LocalDate date = rs.getObject("entry_date", LocalDate.class);
        Long entryId = rs.getObject("id", Long.class);
        LedgerRow line = entryId == null ? null : ledgerRow(rs);
        return new StatementPageRow(rs.getLong("total_days"), date,
                date == null ? 0 : rs.getLong("net_minutes"),
                date == null ? 0 : rs.getLong("cumulative_minutes"),
                date == null ? 0 : rs.getLong("previous_net_minutes"), line);
    }

    record LedgerRow(long id, LocalDate entryDate, Instant createdAt, String habitType,
                     int minutesDelta, String sourceType, UUID sourceId) {
    }

    record DailyPointRow(LocalDate date, long netMinutes, long cumulativeBalanceMinutes) {
    }

    record WeeklyPointRow(LocalDate weekStart, LocalDate weekEnd, long netMinutes,
                          long cumulativeBalanceMinutes) {
    }

    record StatementPage(long totalDays, List<StatementPageRow> rows) {
    }

    record StatementPageRow(long totalDays, LocalDate date, long netMinutes,
                            long cumulativeBalanceMinutes, long previousDayDeltaMinutes,
                            LedgerRow line) {
    }
}
