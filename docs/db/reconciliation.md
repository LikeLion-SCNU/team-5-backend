# Schema Reconciliation

The existing develop database is treated as authoritative for Todo1. The
accepted GitHub Actions capture proves the live schema has:

- 14 public base tables
- `v_daily_net`
- `v_balance`
- 1 non-internal trigger
- append-only protection on `ledger_entries`

## Existing Develop Database

The develop deployment already has the schema applied. For that environment,
the baseline file should be used only as a contract/reference for backend code
and future migrations.

If Flyway or another migration runner is introduced later, do not execute
`V1__canonical_baseline.sql` against the populated develop DB as a normal
migration. First baseline the migration history at version `1` after confirming
the live schema still matches this contract.

## Fresh Database

For a new empty PostgreSQL database, apply:

```text
src/main/resources/db/migration/V1__canonical_baseline.sql
```

Expected resulting objects are listed in `docs/db/schema-contract.md`.

## Current JPA Entity Mapping

The current Java code has seven JPA entities under
`src/main/java/org/example/naeilbank/entity/`. The live database has 14 public
base tables. The mappings below describe the current code as observed, not a
target design.

| JPA entity | Live table | Key observed mismatches |
| --- | --- | --- |
| `User` | `users` | Entity id is `Long`/IDENTITY; live `users.id` is `uuid DEFAULT gen_random_uuid()`. Entity has `password`, `name`, `role`, `provider`; live columns are `password_hash`, `nickname`, `auth_provider`, `kakao_id`, `notify_enabled`. Entity inherits `updated_at`; live `users` has `created_at` only. Live `auth_provider` check allows `email`/`kakao`; entity enum has `LOCAL`/`KAKAO`. |
| `HealthDaily` | `health_daily` | Entity id is `Long`; live id is `uuid`. Entity column `step_count` does not exist; live column is `steps`. Entity column `heart_rate_avg` does not exist. Live columns `screen_minutes` and non-null `sync_status` are not mapped. Entity inherits `created_at`/`updated_at`; live table has neither. Entity unique constraint name is `uk_health_daily_user_date`; live constraint is `health_daily_user_id_record_date_key` on the same columns. |
| `LedgerEntry` | `ledger_entries` | Entity id type `Long` matches live `bigint`, but live id is backed by `ledger_entries_id_seq`. Entity maps nullable `rule_id`; live `rule_id` is `uuid NOT NULL`. Entity fields `amount` and `description` do not exist in live DDL. Live required columns `entry_date`, `habit_type`, and `minutes_delta` are not mapped by the entity. Live optional `ref_type` and `ref_id` are not mapped. Live table has append-only trigger `trg_ledger_no_update`, which is not represented in JPA. |
| `MealRecord` | `meal_records` | Entity id is `Long`; live id is `uuid`. Entity maps `image_url`; live column is `photo_url`. Entity enum values are uppercase Java names; live status check allows lowercase `analyzing`, `pending_confirm`, `confirmed`, `excluded`. Live required `record_date` is not mapped. Live `confirmed_at` is not mapped. Entity inherits `updated_at`; live table has `created_at` only. |
| `MealItem` | `meal_items` | Entity id is `Long`; live id is `uuid`. Entity maps `meal_record_id`, `rule_id`, `food_name`, `est_minutes`, and `is_deleted`. Live columns `portion` and `is_user_added` are not mapped. Entity inherits `created_at`/`updated_at`; live table has neither. |
| `Source` | `sources` | Entity id is `Long`; live id is `uuid`. Entity requires `doi_url`; live `doi_url` is nullable `text`. Live columns `authors`, `journal`, and `pub_year` are not mapped. Entity inherits `updated_at`; live table has `created_at` only. |
| `ConversionRule` | `conversion_rules` | Entity id is `Long`; live id is `uuid`. Entity fields `category`, `bmj_coefficient`, and `description` do not exist in live DDL. Live required columns `habit_type`, `label`, `condition_json`, `minutes_delta`, `unit`, and `is_active` are not mapped. Live `habit_type` has a check constraint allowing `sleep`, `activity`, `screen_time`, `food`, `alcohol`. |

## Live Tables Without JPA Entities

These seven live tables currently have no JPA entity mapping:

- `consents`
- `deletion_logs`
- `face_simulations`
- `notification_logs`
- `plans`
- `protection_events`
- `refresh_tokens`

## Evidence

Accepted capture evidence:

- `.omo/evidence/schema-contract/run-32295434464/dev-schema-sanitized.sql`
- `.omo/evidence/schema-contract/run-32295434464/schema-assertions.txt`
- `.omo/evidence/schema-contract/run-32295434464/schema-contract-verdict.txt`
- `.omo/evidence/schema-contract/run-32295434464/jobs.json`

Preserved earlier captures:

- `.omo/evidence/schema-contract/run-32294894731/`
- `.omo/evidence/schema-contract/run-32295283311/`

`run-32295283311` is intentionally preserved because it exposed the failed
assertion gate after artifact upload. The failure was caused by a boolean output
expectation mismatch and was corrected in commit `9da5202`.

Normalization evidence:

- `scripts/db/test-baseline-normalization.ps1`
- `.omo/evidence/schema-contract/run-32295434464/dev-schema-normalized-for-compare.sql`
