#!/usr/bin/env bash
set -Eeuo pipefail

die_missing() {
  printf '%s\n' "MISSING_PREREQUISITE $*" >&2
  exit 2
}

die_malformed() {
  printf '%s\n' "MALFORMED_PREREQUISITE $*" >&2
  exit 3
}

require_name() {
  case "${2:-}" in
    (*[!A-Za-z0-9_.-]*|'') die_malformed "$1" ;;
  esac
}

require_port() {
  case "${2:-}" in
    (''|*[!0-9]*) die_malformed "$1" ;;
  esac
  if [ "$2" -lt 1 ] || [ "$2" -gt 65535 ]; then
    die_malformed "$1"
  fi
}

require_timeout() {
  case "${2:-}" in
    (''|*[!0-9]*) die_malformed "$1" ;;
  esac
  if [ "$2" -lt 1 ] || [ "$2" -gt 15 ]; then
    die_malformed "$1"
  fi
}

escape_pgpass_field() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//:/\\:}"
  printf '%s' "$value"
}

validate_pgpass_secret_file() {
  local secret_path="$1"
  local original_size safe_size
  original_size="$(wc -c < "$secret_path" | tr -d ' ')"
  safe_size="$(LC_ALL=C tr -d '\000-\037\177' < "$secret_path" | wc -c | tr -d ' ')"
  if [ "$original_size" != "$safe_size" ]; then
    die_malformed POSTGRES_PASSWORD
  fi
}

DB_HOST="${DB_HOST:-naeil-db}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USER:-naeil}"
DB_NAME="${DB_NAME:-naeil_bank_dev}"
DOCKER_NETWORK="${DOCKER_NETWORK:-app_default}"
POSTGRES_IMAGE="${POSTGRES_IMAGE:-postgres:16-alpine}"
SCHEMA_CONTRACT_TIMEOUT_SECONDS="${SCHEMA_CONTRACT_TIMEOUT_SECONDS:-15}"
SCHEMA_CONTRACT_MODE="${SCHEMA_CONTRACT_MODE:-dump}"

require_name DB_HOST "$DB_HOST"
require_port DB_PORT "$DB_PORT"
require_name DB_USER "$DB_USER"
require_name DB_NAME "$DB_NAME"
require_name DOCKER_NETWORK "$DOCKER_NETWORK"
require_timeout SCHEMA_CONTRACT_TIMEOUT_SECONDS "$SCHEMA_CONTRACT_TIMEOUT_SECONDS"

if ! command -v docker >/dev/null 2>&1; then
  die_missing DOCKER_COMMAND
fi

temp_dir="$(mktemp -d)"
pgpass_path="$temp_dir/pgpass"
cleanup() {
  rm -rf "$temp_dir"
}
trap cleanup EXIT HUP INT TERM

find_db_container() {
  local ids id aliases matches=()
  ids="$(timeout "$SCHEMA_CONTRACT_TIMEOUT_SECONDS" docker ps --filter "network=$DOCKER_NETWORK" -q)"
  if [ -z "$ids" ]; then
    die_missing DB_CONTAINER
  fi

  while IFS= read -r id; do
    [ -n "$id" ] || continue
    aliases="$(timeout "$SCHEMA_CONTRACT_TIMEOUT_SECONDS" docker inspect \
      --format '{{range .NetworkSettings.Networks}}{{range .Aliases}}{{.}} {{end}}{{end}}' \
      "$id" 2>/dev/null || true)"
    case " $aliases " in
      (*" $DB_HOST "*) matches+=("$id") ;;
    esac
  done <<< "$ids"

  if [ "${#matches[@]}" -eq 0 ]; then
    die_missing DB_CONTAINER
  fi
  if [ "${#matches[@]}" -ne 1 ]; then
    die_malformed DB_CONTAINER
  fi
  printf '%s' "${matches[0]}"
}

db_container_id="$(find_db_container)"
timeout "$SCHEMA_CONTRACT_TIMEOUT_SECONDS" docker exec "$db_container_id" sh -c \
  'test -n "${POSTGRES_PASSWORD:-}" && printf "%s" "$POSTGRES_PASSWORD"' > "$temp_dir/password"
if [ ! -s "$temp_dir/password" ]; then
  die_missing POSTGRES_PASSWORD
fi
validate_pgpass_secret_file "$temp_dir/password"

printf '%s:%s:%s:%s:%s\n' \
  "$(escape_pgpass_field "$DB_HOST")" \
  "$(escape_pgpass_field "$DB_PORT")" \
  "$(escape_pgpass_field "$DB_NAME")" \
  "$(escape_pgpass_field "$DB_USER")" \
  "$(escape_pgpass_field "$(cat "$temp_dir/password")")" > "$pgpass_path"
rm -f "$temp_dir/password"
chmod 600 "$pgpass_path"

run_postgres_tool() {
  timeout "$SCHEMA_CONTRACT_TIMEOUT_SECONDS" docker run --rm --network "$DOCKER_NETWORK" \
    --mount "type=bind,src=$pgpass_path,dst=/tmp/schema-contract.pgpass,readonly" \
    -e PGPASSFILE=/tmp/schema-contract.pgpass \
    -e PGCONNECT_TIMEOUT=5 \
    -e DB_HOST="$DB_HOST" \
    -e DB_PORT="$DB_PORT" \
    -e DB_USER="$DB_USER" \
    -e DB_NAME="$DB_NAME" \
    "$POSTGRES_IMAGE" "$@"
}

case "$SCHEMA_CONTRACT_MODE" in
  dump)
    run_postgres_tool pg_dump --schema-only --no-owner --no-privileges -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME"
    ;;
  assert)
    run_postgres_tool psql -X -v ON_ERROR_STOP=1 -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -At <<'SQL'
SELECT 'table_count=' || count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE';
SELECT 'view_v_daily_net=' || (to_regclass('public.v_daily_net') IS NOT NULL);
SELECT 'view_v_balance=' || (to_regclass('public.v_balance') IS NOT NULL);
SELECT 'trigger_count=' || count(*) FROM pg_trigger WHERE NOT tgisinternal;
SELECT 'function_count=' || count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public';
SELECT 'table=' || tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename;
SQL
    ;;
  *)
    die_malformed SCHEMA_CONTRACT_MODE
    ;;
esac
