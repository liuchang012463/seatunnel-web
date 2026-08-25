#!/usr/bin/env bash
set -euo pipefail

# Sprint 0 version guard. This script performs no Airflow HTTP calls.
# It inspects the ingestion container package metadata when Docker is available.

OM_BASE_URL="${OM_BASE_URL:-http://127.0.0.1:8585/api}"
EXPECTED_SERVER_VERSION="${EXPECTED_SERVER_VERSION:-1.12.10}"
EXPECTED_INGESTION_VERSION="${EXPECTED_INGESTION_VERSION:-1.12.10.0}"
EXPECTED_MANAGED_APIS_VERSION="${EXPECTED_MANAGED_APIS_VERSION:-1.12.10.0}"
INGESTION_CONTAINER="${INGESTION_CONTAINER:-openmetadata_ingestion}"
INGESTION_PYTHON="${INGESTION_PYTHON:-/home/airflow/.local/bin/python}"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

case "$OM_BASE_URL" in
  *:8082*|*/airflow*) fail "OM_BASE_URL must point to OpenMetadata /api, not Airflow" ;;
esac

command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v python3 >/dev/null 2>&1 || fail "python3 is required to parse version JSON"

version_json="$(curl --fail --silent --show-error --max-time "${OM_TIMEOUT_SECONDS:-10}" \
  "${OM_BASE_URL%/}/v1/system/version")" \
  || fail "cannot read OpenMetadata version from ${OM_BASE_URL%/}/v1/system/version"

server_version="$(printf '%s' "$version_json" | python3 -c \
  'import json,sys; print(json.load(sys.stdin).get("version", ""))')"
server_revision="$(printf '%s' "$version_json" | python3 -c \
  'import json,sys; print(json.load(sys.stdin).get("revision", ""))')"

[[ "$server_version" == "$EXPECTED_SERVER_VERSION" ]] \
  || fail "Server version ${server_version:-<missing>} != ${EXPECTED_SERVER_VERSION}"

read_runtime_version() {
  local distribution="$1"
  if command -v docker >/dev/null 2>&1 && docker inspect "$INGESTION_CONTAINER" >/dev/null 2>&1; then
    docker exec "$INGESTION_CONTAINER" "$INGESTION_PYTHON" -c \
      "from importlib.metadata import version; print(version('${distribution}'))" 2>/dev/null \
      || fail "cannot read ${distribution} from ${INGESTION_CONTAINER}"
  else
    case "$distribution" in
      openmetadata-ingestion) printf '%s\n' "${ACTUAL_INGESTION_VERSION:-}" ;;
      openmetadata-managed-apis) printf '%s\n' "${ACTUAL_MANAGED_APIS_VERSION:-}" ;;
    esac
  fi
}

ingestion_version="$(read_runtime_version openmetadata-ingestion)"
managed_version="$(read_runtime_version openmetadata-managed-apis)"

[[ "$ingestion_version" == "$EXPECTED_INGESTION_VERSION" ]] \
  || fail "openmetadata-ingestion ${ingestion_version:-<missing>} != ${EXPECTED_INGESTION_VERSION}"
[[ "$managed_version" == "$EXPECTED_MANAGED_APIS_VERSION" ]] \
  || fail "openmetadata-managed-apis ${managed_version:-<missing>} != ${EXPECTED_MANAGED_APIS_VERSION}"

case "$ingestion_version" in 1.12.10.*) ;; *) fail "ingestion is outside 1.12.10.x" ;; esac
case "$managed_version" in 1.12.10.*) ;; *) fail "managed APIs are outside 1.12.10.x" ;; esac

printf 'Server: %s (revision=%s)\n' "$server_version" "${server_revision:-unknown}"
printf 'openmetadata-ingestion: %s\n' "$ingestion_version"
printf 'openmetadata-managed-apis: %s\n' "$managed_version"
printf 'Version guard: PASS (Server=%s, ingestion/managed line=1.12.10.x)\n' "$EXPECTED_SERVER_VERSION"
