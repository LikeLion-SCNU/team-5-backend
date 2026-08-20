#!/usr/bin/env bash
set -Eeuo pipefail

DB_NAME="${DB_NAME:-naeil_bank_dev}"
DB_USER="${DB_USER:-naeil}"
DB_NETWORK="${DB_NETWORK:-app_default}"
DB_ALIAS="${DB_ALIAS:-naeil-db}"
TIMEOUT_SECONDS="${CANONICAL_VECTOR_TIMEOUT_SECONDS:-15}"

fail() {
  printf '%s\n' "$1" >&2
  exit "${2:-1}"
}

case "$DB_NAME" in
  naeil_bank_dev) ;;
  *) fail 'UNSAFE_DATABASE_TARGET' 2 ;;
esac

case "$DB_USER" in
  naeil) ;;
  *) fail 'UNSAFE_DATABASE_USER' 2 ;;
esac

case "$DB_NETWORK" in
  app_default) ;;
  *) fail 'UNSAFE_DATABASE_NETWORK' 2 ;;
esac

case "$DB_ALIAS" in
  naeil-db) ;;
  *) fail 'UNSAFE_DATABASE_ALIAS' 2 ;;
esac

case "$TIMEOUT_SECONDS" in
  ''|*[!0-9]*) fail 'INVALID_TIMEOUT_SECONDS' 2 ;;
esac

if [ "$TIMEOUT_SECONDS" -lt 1 ] || [ "$TIMEOUT_SECONDS" -gt 15 ]; then
  fail 'INVALID_TIMEOUT_SECONDS' 2
fi

command -v docker >/dev/null 2>&1 || fail 'MISSING_PREREQUISITE docker' 2
command -v timeout >/dev/null 2>&1 || fail 'MISSING_PREREQUISITE timeout' 2

mapfile -t running_containers < <(
  timeout "${TIMEOUT_SECONDS}s" docker ps \
    --filter "network=$DB_NETWORK" \
    --filter 'status=running' \
    --format '{{.ID}}'
)

matches=()
for container_id in "${running_containers[@]}"; do
  aliases="$(
    timeout "${TIMEOUT_SECONDS}s" docker inspect \
      --format '{{range $name, $network := .NetworkSettings.Networks}}{{if eq $name "'"$DB_NETWORK"'"}}{{range $network.Aliases}}{{println .}}{{end}}{{end}}{{end}}' \
      "$container_id"
  )"
  if printf '%s\n' "$aliases" | grep -Fxq "$DB_ALIAS"; then
    matches+=("$container_id")
  fi
done

if [ "${#matches[@]}" -eq 0 ]; then
  fail 'MISSING_PREREQUISITE naeil-db container' 2
fi

if [ "${#matches[@]}" -gt 1 ]; then
  fail 'AMBIGUOUS_DB_CONTAINER' 2
fi

DB_CONTAINER="${matches[0]}"

psql_query() {
  local sql="$1"
  printf '%s\n' "$sql" |
    timeout "${TIMEOUT_SECONDS}s" docker exec -i \
      -e DB_NAME="$DB_NAME" \
      -e DB_USER="$DB_USER" \
      "$DB_CONTAINER" \
      sh -ceu 'PGPASSWORD="${POSTGRES_PASSWORD:?}" psql -X -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" -At'
}

unsupported_count="$(
  psql_query "select count(*) from conversion_rules where is_active is true and (habit_type::text not in ('sleep','activity','screen_time','food','alcohol') or unit not in ('per_unit','per_minute','per_hour','per_1000_steps','per_serving','per_drink'));"
)"
if [ "$unsupported_count" != '0' ]; then
  fail 'UNSUPPORTED_CANONICAL_RULE_SET' 3
fi

inactive_source_count="$(
  psql_query "select count(*) from conversion_rules r join sources s on s.id = r.source_id where r.is_active is true and s.is_active is not true;"
)"
if [ "$inactive_source_count" != '0' ]; then
  fail 'INACTIVE_SOURCE_FOR_ACTIVE_RULE' 3
fi

ambiguous_count="$(
  psql_query "select count(*) from (select habit_type::text, lower(trim(unit)) from conversion_rules where is_active is true group by habit_type::text, lower(trim(unit)) having count(*) > 1) duplicate_active_selectors;"
)"
if [ "$ambiguous_count" != '0' ]; then
  fail 'AMBIGUOUS_CANONICAL_RULE_SET' 3
fi

condition_count="$(
  psql_query "select count(*) from conversion_rules where is_active is true and condition_json <> '{}'::jsonb;"
)"
if [ "$condition_count" != '0' ]; then
  fail 'UNSUPPORTED_CANONICAL_CONDITION_JSON' 3
fi

vector_count="$(
  psql_query "select count(*) from conversion_rules r join sources s on s.id = r.source_id where r.is_active is true;"
)"
if [ "$vector_count" = '0' ]; then
  fail 'EMPTY_CANONICAL_VECTOR_SET' 3
fi

psql_query "
select jsonb_build_object(
  'logical_key_hash', md5(r.logical_key::text),
  'category', r.habit_type::text,
  'unit', lower(trim(r.unit)),
  'minutes_delta', r.minutes_delta,
  'condition_json', r.condition_json,
  'source_active', s.is_active
)::text
from conversion_rules r
join sources s on s.id = r.source_id
where r.is_active is true
order by r.habit_type::text, lower(trim(r.unit)), md5(r.logical_key::text);
"
