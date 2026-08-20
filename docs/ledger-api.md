# Ledger API Contract

Todo11 exposes authenticated ledger read APIs under `/api/v1/ledger`.

## Canonical SQL Source

- Total balance reads `v_balance.total_minutes`.
- Daily net reads `v_daily_net.net_minutes`.
- Statement lines read `ledger_entries` only after the owning user is scoped.
- Statement line sums are reconciled against `v_daily_net` before a response is returned.
- Ordering is stable: `entry_date desc, created_at desc, id desc`.

## Endpoints

- `GET /api/v1/ledger/balance`
  - Returns `balanceMinutes`, `previousDayDeltaMinutes`, `asOfDate`, `timezone`.
- `GET /api/v1/ledger/statements?from=YYYY-MM-DD&to=YYYY-MM-DD&page=0&size=20`
  - Pages statement days, not raw rows.
  - Defaults to the last 30 local dates when `from` or `to` is omitted.
  - `size` is bounded to `1..100`.
- `GET /api/v1/ledger/trends/daily?to=YYYY-MM-DD`
  - Returns a fixed 7-day daily window ending at `to`, or the user's local today.
- `GET /api/v1/ledger/trends/weekly?to=YYYY-MM-DD`
  - Returns a fixed 4-week Monday-start window ending in the week containing `to`.

## Protection And Timezone

Protection mode is never exposed as a field. Numeric values, dates, paging, rows,
and aggregates stay bit-identical. Only statement `displayText` changes for
negative rows.

Ledger dates and notification schedules are fixed to `Asia/Seoul`. The
`notification_preferences.timezone` column remains compatibility-only and is
constrained to `Asia/Seoul`; JVM default timezone and per-user timezone values
are not used.

The platform default morning-statement schedule is `08:00 Asia/Seoul`
(`0 0 8 * * *`). Todo11 fixes that shared contract only. Browser subscription,
VAPID delivery, retry, and the actual scheduled dispatcher remain owned by
Todo30 and are not claimed by this ledger-read slice.

## Performance Evidence

The statement response is assembled from exactly two database reads: one
owner-scoped protection-mode lookup and one atomic ledger page snapshot query.
The integration fixture inserts 10,050 ledger rows across two users and requires
the authenticated statement endpoint to respond within 5 seconds in CI.
