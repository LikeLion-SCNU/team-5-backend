# Deterministic conversion engine

The conversion engine never embeds habit coefficients or evidence citations in Java. It selects one
active persisted `conversion_rules` row for the requested habit category and unit, verifies that its
`sources` row is active, and snapshots both rows when it posts the result. If zero or more than one
rule matches, conversion fails without a ledger or outbox write.

The checked-in migrations contain no canonical source or rule data. Development canonical rows must
therefore be imported through the evidence administration workflow. Test rules and sources are named
`TEST_FIXTURE` and are not production recommendations.

## Arithmetic contract

- Inputs use `BigDecimal` with at most 12 fractional digits.
- Rule coefficients remain persisted integer minutes per normalized unit.
- Supported units are `per_unit`, `per_minute`, `per_hour`, `per_1000_steps`, `per_serving`, and
  `per_drink`. Only `per_1000_steps` divides the input, by exactly 1000.
- Intermediate calculations use scale 12 and `HALF_EVEN`; the immutable snapshot records scale 6.
- Exact snapshot seconds are `normalized input × rule minutes × 60`.
- Posted seconds and the compatibility `ledger_entries.minutes_delta` are rounded independently with
  `HALF_EVEN` to whole seconds and whole minutes.
- Inputs must be between 0 and 1,000,000,000, rule rates must be within ±525,600 minutes,
  and absolute results must not exceed 31,536,000,000 seconds.
- Non-empty `condition_json` is currently unsupported and fails closed. This avoids silently applying
  a condition format that is not present in the repository's canonical schema contract.

## Health caller normalization

- Sleep is categorical: an observed `sleep_minutes` below 420 emits one `sleep/per_unit` input;
  420 or more emits no sleep posting. The `-36` rule is a product-level derivation from categorical
  short-sleep evidence, not a measured per-minute dose response or an individual medical prediction.
- Activity stores the observed step count unchanged but caps the conversion input at 2,000 steps
  per day before passing it to `activity/per_1000_steps`. The canonical
  `+30` per 1,000 steps is derived by assuming 100 steps per minute (1,000 steps = 10 minutes) and
  applying the separate `+60` per 20 minutes activity mapping, so the cap limits credit to +60 per
  day. Both assumptions must remain visible because ordinary total steps are only a proxy and the
  health record does not measure cadence or moderate-intensity minutes directly.
- `screen_minutes` is accepted only with `screen_metric: "sedentary_tv_equivalent"`. It means
  sedentary TV-equivalent viewing, not aggregate phone/computer/general screen use. The caller divides
  minutes by 60 at scale 12 with `HALF_EVEN` and sends the decimal value to `screen_time/per_hour`;
  absent metric affirmation fails closed before the health row is saved.

## Idempotency and lineage

`conversion_postings` has one immutable row per user, source event, and habit category. A single
`health_daily` aggregate can therefore post its sleep, activity, and screen-time metrics independently.
The row contains the
request fingerprint, exact input/result snapshots, rule/source snapshots, and the linked append-only
ledger entry. Replaying the same event and identical canonical input returns the original receipt;
reusing an event ID for different input is a conflict. A per-user database lock serializes concurrent
first posts, while the database unique key provides a second invariant.

Before a first post, `HEALTH_DAILY` must resolve to an owned row on the same local date and is limited
to sleep/activity/screen-time. `MEAL_ITEM` must resolve through an owned, confirmed meal on that date,
must not be deleted, and is limited to food/alcohol. Missing and wrong-owner events share the same
failure. Rule and source access follows one lock order—rule family/rule first, then source—across
conversion and evidence administration.

The ledger entry and conversion posting are flushed in one Spring transaction. Any posting failure
rolls the ledger insert back. A composite database foreign key also binds the posting user to the
ledger-entry owner. Conversion does not enqueue outbox work.
