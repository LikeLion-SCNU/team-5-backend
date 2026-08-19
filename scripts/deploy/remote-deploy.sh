#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  printf 'REMOTE_DEPLOY_ERROR: %s\n' "$*" >&2
  exit 1
}

usage() {
  die "usage: remote-deploy.sh <project_name> <app_version> <app_port> <db_name> <app_profile> <env_file>"
}

require_value() {
  local name="$1"
  local value="$2"
  if [ -z "$value" ]; then
    die "$name is required"
  fi
}

wait_readiness() {
  local port="$1"
  local attempts="${DEPLOY_READINESS_ATTEMPTS:-30}"
  local delay="${DEPLOY_READINESS_DELAY_SECONDS:-2}"

  if [ "${DEPLOY_FORCE_READINESS_FAILURE:-0}" = "1" ]; then
    printf 'readiness=forced_failure\n' >&2
    return 1
  fi

  for attempt in $(seq 1 "$attempts"); do
    if curl -fsS --max-time 5 "http://127.0.0.1:${port}/actuator/health/readiness" >/dev/null; then
      printf 'readiness=pass attempt=%s\n' "$attempt"
      return 0
    fi
    sleep "$delay"
  done

  printf 'readiness=fail attempts=%s\n' "$attempts" >&2
  return 1
}

container_image() {
  local container_id="$1"
  if [ -z "$container_id" ]; then
    return 0
  fi
  docker inspect --format '{{.Config.Image}}' "$container_id" 2>/dev/null || true
}

image_version() {
  local image="$1"
  case "$image" in
    naeil-bank-backend:*) printf '%s\n' "${image#naeil-bank-backend:}" ;;
    *) return 1 ;;
  esac
}

write_state() {
  local state_file="$1"
  local version="$2"
  local port="$3"
  local db_name="$4"
  local profile="$5"

  umask 077
  {
    printf 'APP_VERSION=%s\n' "$version"
    printf 'APP_PORT=%s\n' "$port"
    printf 'DB_NAME=%s\n' "$db_name"
    printf 'APP_PROFILE=%s\n' "$profile"
  } > "$state_file"
}

restore_previous() {
  local project_name="$1"
  local env_file="$2"
  local state_file="$3"
  local fallback_version="$4"
  local fallback_port="$5"
  local fallback_db="$6"
  local fallback_profile="$7"

  local rollback_version="$fallback_version"
  local rollback_port="$fallback_port"
  local rollback_db="$fallback_db"
  local rollback_profile="$fallback_profile"

  if [ -s "$state_file" ]; then
    . "$state_file"
    rollback_version="${APP_VERSION:-$rollback_version}"
    rollback_port="${APP_PORT:-$rollback_port}"
    rollback_db="${DB_NAME:-$rollback_db}"
    rollback_profile="${APP_PROFILE:-$rollback_profile}"
  fi

  require_value ROLLBACK_APP_VERSION "$rollback_version"
  printf 'rollback=start image=naeil-bank-backend:%s\n' "$rollback_version" >&2

  APP_VERSION="$rollback_version" \
  APP_PORT="$rollback_port" \
  DB_NAME="$rollback_db" \
  APP_PROFILE="$rollback_profile" \
    docker compose --env-file "$env_file" -p "$project_name" up -d --no-deps api

  wait_readiness "$rollback_port" || die "rollback image failed readiness"
  printf 'rollback=restored image=naeil-bank-backend:%s\n' "$rollback_version" >&2
}

main() {
  if [ "$#" -ne 6 ]; then
    usage
  fi

  local project_name="$1"
  local app_version="$2"
  local app_port="$3"
  local db_name="$4"
  local app_profile="$5"
  local env_file="$6"

  require_value PROJECT_NAME "$project_name"
  require_value APP_VERSION "$app_version"
  require_value APP_PORT "$app_port"
  require_value DB_NAME "$db_name"
  require_value APP_PROFILE "$app_profile"
  require_value ENV_FILE "$env_file"
  [ -r "$env_file" ] || die "env file is not readable"

  case "$app_profile" in
    dev|prod) ;;
    *) die "APP_PROFILE must be dev or prod" ;;
  esac

  local state_dir=".deploy-state"
  install -d -m 700 "$state_dir"
  local state_file="${state_dir}/${project_name}.env"
  local previous_container
  previous_container="$(
    APP_VERSION="$app_version" \
    APP_PORT="$app_port" \
    DB_NAME="$db_name" \
    APP_PROFILE="$app_profile" \
      docker compose --env-file "$env_file" -p "$project_name" ps -q api 2>/dev/null || true
  )"
  local previous_image
  previous_image="$(container_image "$previous_container")"
  local previous_version=""
  if [ -n "$previous_image" ]; then
    previous_version="$(image_version "$previous_image" || true)"
  fi

  printf 'deploy=start project=%s image=naeil-bank-backend:%s profile=%s port=%s db=%s\n' \
    "$project_name" "$app_version" "$app_profile" "$app_port" "$db_name"

  APP_VERSION="$app_version" \
  APP_PORT="$app_port" \
  DB_NAME="$db_name" \
  APP_PROFILE="$app_profile" \
    docker compose --env-file "$env_file" -p "$project_name" build api

  APP_VERSION="$app_version" \
  APP_PORT="$app_port" \
  DB_NAME="$db_name" \
  APP_PROFILE="$app_profile" \
    docker compose --env-file "$env_file" -p "$project_name" up -d --no-deps api

  if wait_readiness "$app_port"; then
    write_state "$state_file" "$app_version" "$app_port" "$db_name" "$app_profile"
    printf 'deploy=success image=naeil-bank-backend:%s\n' "$app_version"
    exit 0
  fi

  printf 'deploy=candidate_failed image=naeil-bank-backend:%s\n' "$app_version" >&2
  if [ -n "$previous_version" ] || [ -s "$state_file" ]; then
    restore_previous "$project_name" "$env_file" "$state_file" "$previous_version" "$app_port" "$db_name" "$app_profile"
  else
    die "candidate failed and no previous healthy image/config was found"
  fi
  exit 1
}

main "$@"
