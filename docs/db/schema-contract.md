# Database Schema Contract

Source of truth: live develop database captured by GitHub Actions workflow
`Schema Contract Capture`.

Final accepted capture:

- Run: `32295434464`
- Commit: `9da520291dc2a22d2e496b68bf326073d2a405d9`
- Branch: `develop`
- Result: `success`
- Artifact: `develop-schema-contract`, artifact id `9381030682`
- Local evidence: `.omo/evidence/schema-contract/run-32295434464/`
- Artifact checksum: `1ad5d610ac9be958971198fdeb3deeb0e5bf50d835f82472b2892ff17d0ff7ac`
- Normalized baseline checksum: `5f3c38191b7a3f5984023c92b188b5edc2f54871d4a554e15f0a05bd23ed4da7`

The dump is schema-only and was captured with `pg_dump --schema-only
--no-owner --no-privileges`. It must not contain row data, grants, owners, or
database credentials.

## Inventory

Assertion output from the accepted artifact:

```text
table_count=14
view_v_daily_net=true
view_v_balance=true
trigger_count=1
function_count=37
```

Tables:

- `consents`
- `conversion_rules`
- `deletion_logs`
- `face_simulations`
- `health_daily`
- `ledger_entries`
- `meal_items`
- `meal_records`
- `notification_logs`
- `plans`
- `protection_events`
- `refresh_tokens`
- `sources`
- `users`

Views:

- `v_balance`
- `v_daily_net`

User trigger:

- `trg_ledger_no_update` on `ledger_entries`

Core mutation guard:

- `forbid_ledger_mutation()`

Indexes explicitly present in the accepted dump:

- `idx_ledger_user_date`
- `idx_refresh_user`

## Baseline

Fresh database baseline:

- `src/main/resources/db/migration/V1__canonical_baseline.sql`

This file is normalized from the accepted sanitized schema artifact by removing
only lines that begin with a psql meta-command backslash. In the accepted
artifact, the removed lines are exactly one `\restrict ...` line and one
`\unrestrict ...` line emitted by `pg_dump`.

The actual DDL is otherwise unchanged. The proof command is:

```text
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db\test-baseline-normalization.ps1
```

Do not run this baseline destructively against the existing develop database;
that database already has the schema applied.
