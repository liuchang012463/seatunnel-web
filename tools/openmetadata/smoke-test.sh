#!/usr/bin/env bash
set -euo pipefail

# Reproducible Sprint 0 smoke. Every network request in this file goes to
# OpenMetadata's /api base URL. It never calls Airflow's REST API.

OM_BASE_URL="${OM_BASE_URL:-http://127.0.0.1:8585/api}"
OM_TOKEN="${OM_TOKEN:-}"
SMOKE_PREFIX="${SMOKE_PREFIX:-codex_sprint0_$(date -u +%Y%m%d%H%M%S)}"
SMOKE_SERVICE_NAME="${SMOKE_SERVICE_NAME:-${SMOKE_PREFIX}_service}"
SMOKE_SERVICE_TYPE="${SMOKE_SERVICE_TYPE:-Mysql}"
SMOKE_CONNECTION_FILE="${SMOKE_CONNECTION_FILE:-}"
SMOKE_EXISTING_SERVICE_ID="${SMOKE_EXISTING_SERVICE_ID:-}"
SMOKE_EXISTING_SERVICE_FQN="${SMOKE_EXISTING_SERVICE_FQN:-}"
SMOKE_DATABASE_FQN="${SMOKE_DATABASE_FQN:-}"
SMOKE_TABLE_FQN="${SMOKE_TABLE_FQN:-}"
SMOKE_PROFILER_TABLE_FILTER="${SMOKE_PROFILER_TABLE_FILTER:-}"
SMOKE_ASSERT_MARK_DELETED_FQN="${SMOKE_ASSERT_MARK_DELETED_FQN:-}"
SMOKE_CLEANUP="${SMOKE_CLEANUP:-1}"
SMOKE_RUN_PIPELINES="${SMOKE_RUN_PIPELINES:-1}"
SMOKE_KILL_RUNNING="${SMOKE_KILL_RUNNING:-0}"
SMOKE_WAIT_SECONDS="${SMOKE_WAIT_SECONDS:-180}"
SMOKE_POLL_SECONDS="${SMOKE_POLL_SECONDS:-5}"

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/openmetadata-sprint0.XXXXXX")"
CURL_CONFIG="${TMP_DIR}/curl.conf"
LAST_BODY=""
LAST_STATUS=""
SERVICE_ID=""
SERVICE_FQN=""
SERVICE_OWNED=0
METADATA_ID=""
METADATA_FQN=""
PROFILER_ID=""
PROFILER_FQN=""
RUNNING_PIPELINE_ID=""

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

cleanup() {
  local previous_exit=$?
  set +e
  if [[ "$SMOKE_CLEANUP" == "1" ]]; then
    if [[ -n "$RUNNING_PIPELINE_ID" ]]; then
      om_call POST "/v1/services/ingestionPipelines/kill/${RUNNING_PIPELINE_ID}" "" >/dev/null 2>&1 || true
    fi
    if [[ -r "${TMP_DIR}/pipeline_ids" ]]; then
      while IFS= read -r cleanup_id; do
        [[ -n "$cleanup_id" ]] || continue
        om_call DELETE "/v1/services/ingestionPipelines/${cleanup_id}?hardDelete=true" "" >/dev/null 2>&1 || true
      done < "${TMP_DIR}/pipeline_ids"
    fi
    if [[ -n "$METADATA_ID" ]]; then
      om_call DELETE "/v1/services/ingestionPipelines/${METADATA_ID}?hardDelete=true" "" >/dev/null 2>&1 || true
    fi
    if [[ -n "$PROFILER_ID" ]]; then
      om_call DELETE "/v1/services/ingestionPipelines/${PROFILER_ID}?hardDelete=true" "" >/dev/null 2>&1 || true
    fi
    if [[ "$SERVICE_OWNED" == "1" && -n "$SERVICE_ID" ]]; then
      om_call DELETE "/v1/services/databaseServices/${SERVICE_ID}?recursive=true&hardDelete=true" "" >/dev/null 2>&1 || true
    fi
  fi
  rm -rf "$TMP_DIR"
  exit "$previous_exit"
}
trap cleanup EXIT

[[ -n "$OM_TOKEN" ]] || fail "OM_TOKEN is required and must not be committed"
command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v python3 >/dev/null 2>&1 || fail "python3 is required"
case "$SMOKE_PREFIX" in
  codex_sprint0_*) ;;
  *) fail "SMOKE_PREFIX must start with codex_sprint0_ to isolate disposable Sprint 0 resources" ;;
esac
printf 'header = "Authorization: Bearer %s"\nheader = "Accept: application/json"\n' "$OM_TOKEN" > "$CURL_CONFIG"
chmod 600 "$CURL_CONFIG"

case "$OM_BASE_URL" in
  *:8082*|*/airflow*) fail "OM_BASE_URL must point to OpenMetadata /api, not Airflow" ;;
esac

urlencode() {
  python3 - "$1" <<'PY'
import sys
from urllib.parse import quote
print(quote(sys.argv[1], safe=""))
PY
}

json_field() {
  local file="$1"
  local expression="$2"
  python3 - "$file" "$expression" <<'PY'
import json
import sys

path = sys.argv[2].split('.') if sys.argv[2] else []
try:
    value = json.load(open(sys.argv[1]))
    for segment in path:
        if isinstance(value, list):
            value = value[int(segment)]
        else:
            value = value.get(segment)
    if value is None:
        value = ""
    if isinstance(value, bool):
        print("true" if value else "false")
    elif isinstance(value, (dict, list)):
        print(json.dumps(value, ensure_ascii=False, separators=(",", ":")))
    else:
        print(value)
except (OSError, ValueError, KeyError, IndexError, TypeError):
    print("")
PY
}

json_has_data() {
  local file="$1"
  python3 - "$file" <<'PY'
import json
import sys
try:
    data = json.load(open(sys.argv[1])).get("data")
    print("1" if isinstance(data, list) and data else "0")
except (OSError, ValueError, TypeError):
    print("0")
PY
}

om_call() {
  local method="$1"
  local path="$2"
  local payload="${3:-}"
  local body_file="${TMP_DIR}/response.$RANDOM"
  local payload_file="${TMP_DIR}/payload.$RANDOM"
  local status
  local -a args

  [[ "$path" != *":8082"* && "$path" != *"airflow"* ]] \
    || fail "refusing a non-OpenMetadata path: ${path}"

  args=(--config "$CURL_CONFIG" --silent --show-error --max-time "${OM_TIMEOUT_SECONDS:-30}" \
    --request "$method" --output "$body_file" \
    --write-out '%{http_code}' "${OM_BASE_URL%/}${path}")
  if [[ -n "$payload" ]]; then
    printf '%s' "$payload" > "$payload_file"
    args+=(--header 'Content-Type: application/json' --data-binary "@${payload_file}")
  fi
  status="$(curl "${args[@]}")" || fail "OM request failed: ${method} ${path}"
  LAST_STATUS="$status"
  LAST_BODY="$body_file"
  echo "${method} ${path} -> HTTP ${status}" >&2
}

expect_status() {
  local expected="$1"
  [[ "$LAST_STATUS" == "$expected" ]] || {
    echo "Response (secrets omitted by server or fixture policy):" >&2
    python3 - "$LAST_BODY" <<'PY' >&2
import json
import sys
try:
    value = json.load(open(sys.argv[1]))
    def redact(obj):
        if isinstance(obj, dict):
            return {k: ("<redacted>" if any(x in k.lower() for x in ("password", "token", "secret", "jwt")) else redact(v)) for k, v in obj.items()}
        if isinstance(obj, list):
            return [redact(v) for v in obj]
        return obj
    print(json.dumps(redact(value), ensure_ascii=False, indent=2)[:6000])
except Exception:
    print("<non-json response>")
PY
    fail "expected HTTP ${expected}, got ${LAST_STATUS}"
  }
}

expect_created_status() {
  [[ "$LAST_STATUS" == "200" || "$LAST_STATUS" == "201" ]] || expect_status 200
}

delete_pipeline_and_assert_absent() {
  local pipeline_id="$1"
  [[ -n "$pipeline_id" ]] || return 0
  om_call DELETE "/v1/services/ingestionPipelines/${pipeline_id}?hardDelete=true" ""
  case "$LAST_STATUS" in
    200|204|404) ;;
    *) fail "pipeline hard delete returned HTTP ${LAST_STATUS}: ${pipeline_id}" ;;
  esac
  om_call GET "/v1/services/ingestionPipelines/${pipeline_id}?include=all" ""
  [[ "$LAST_STATUS" == "404" ]] \
    || fail "pipeline remains readable after hard delete: ${pipeline_id} (HTTP ${LAST_STATUS})"
  echo "pipeline delete: PASS (${pipeline_id})"
}

build_service_payload() {
  [[ -n "$SMOKE_CONNECTION_FILE" ]] || fail "SMOKE_CONNECTION_FILE is required when creating a service"
  [[ -r "$SMOKE_CONNECTION_FILE" ]] || fail "connection file is not readable: ${SMOKE_CONNECTION_FILE}"
  python3 - "$SMOKE_SERVICE_NAME" "$SMOKE_SERVICE_TYPE" "$SMOKE_CONNECTION_FILE" <<'PY'
import json
import sys

name, service_type, connection_file = sys.argv[1:]
connection = json.load(open(connection_file))
if not isinstance(connection, dict):
    raise SystemExit("connection file must contain one JSON object")
print(json.dumps({
    "name": name,
    "displayName": name,
    "serviceType": service_type,
    "connection": {"config": connection},
}, ensure_ascii=False, separators=(",", ":")))
PY
}

build_pipeline_payload() {
  local pipeline_name="$1"
  local pipeline_type="$2"
  local database_name="${3:-}"
  python3 - "$pipeline_name" "$pipeline_type" "$SERVICE_ID" "$SERVICE_FQN" "$database_name" "$SMOKE_PROFILER_TABLE_FILTER" <<'PY'
import json
import sys

name, pipeline_type, service_id, service_fqn, database_name, table_filter = sys.argv[1:]
service = {
    "id": service_id,
    "type": "databaseService",
    "name": service_fqn,
    "fullyQualifiedName": service_fqn,
}
if pipeline_type == "metadata":
    config = {
        "type": "DatabaseMetadata",
        "markDeletedTables": True,
        "markDeletedSchemas": True,
        "markDeletedDatabases": True,
        "includeTables": True,
        "includeViews": False,
        "includeStoredProcedures": False,
        "includeTags": False,
        "schemaFilterPattern": {"includes": [], "excludes": []},
    }
    schedule = "0 0 1 1 *"
else:
    config = {
        "type": "Profiler",
        "databaseFilterPattern": {"includes": [database_name] if database_name else [], "excludes": []},
        "includeViews": False,
        "computeMetrics": True,
        "computeTableMetrics": True,
        "computeColumnMetrics": True,
        "profileSampleType": "PERCENTAGE",
        "profileSample": 100,
    }
    if table_filter:
        config["tableFilterPattern"] = {"includes": [table_filter], "excludes": []}
    schedule = "0 0 1 1 *"
print(json.dumps({
    "name": name,
    "displayName": name,
    "service": service,
    "pipelineType": pipeline_type,
    "sourceConfig": {"config": config},
    "airflowConfig": {
        "pausePipeline": False,
        "concurrency": 1,
        "scheduleInterval": schedule,
        "pipelineCatchup": False,
        "maxActiveRuns": 1,
        "retries": 0,
        "retryDelay": 300,
    },
    "loggerLevel": "INFO",
    "raiseOnError": True,
}, ensure_ascii=False, separators=(",", ":")))
PY
}

ensure_pipeline() {
  local pipeline_name="$1"
  local pipeline_type="$2"
  local database_name="${3:-}"
  local encoded_name payload
  encoded_name="$(urlencode "${SERVICE_FQN}.${pipeline_name}")"
  om_call GET "/v1/services/ingestionPipelines/name/${encoded_name}?include=non-deleted" ""
  if [[ "$LAST_STATUS" == "200" ]]; then
    payload="$(build_pipeline_payload "$pipeline_name" "$pipeline_type" "$database_name")"
    om_call PUT "/v1/services/ingestionPipelines" "$payload"
    expect_status 200
  elif [[ "$LAST_STATUS" == "404" ]]; then
    payload="$(build_pipeline_payload "$pipeline_name" "$pipeline_type" "$database_name")"
    om_call POST "/v1/services/ingestionPipelines" "$payload"
    expect_created_status
  else
    expect_status 200
  fi
  local id fqn
  id="$(json_field "$LAST_BODY" id)"
  fqn="$(json_field "$LAST_BODY" fullyQualifiedName)"
  [[ -n "$id" && -n "$fqn" ]] || fail "pipeline response lacks id/FQN"
  printf '%s\n' "$id" >> "${TMP_DIR}/pipeline_ids"
  printf '%s\t%s\n' "$id" "$fqn"
}

wait_for_run() {
  local fqn="$1"
  local run_id="${2:-}"
  local encoded_fqn state elapsed=0
  encoded_fqn="$(urlencode "$fqn")"
  while (( elapsed <= SMOKE_WAIT_SECONDS )); do
    if [[ -n "$run_id" ]]; then
      om_call GET "/v1/services/ingestionPipelines/${encoded_fqn}/pipelineStatus/${run_id}" ""
    else
      om_call GET "/v1/services/ingestionPipelines/${encoded_fqn}/pipelineStatus?limit=1" ""
    fi
    if [[ "$LAST_STATUS" == "200" ]]; then
      state="$(json_field "$LAST_BODY" pipelineState)"
      [[ -n "$state" ]] || state="$(json_field "$LAST_BODY" data.0.pipelineState)"
      echo "pipeline state=${state:-unknown}"
      case "$state" in
        success) return 0 ;;
        partialSuccess) [[ "${SMOKE_ALLOW_PARTIAL_SUCCESS:-0}" == "1" ]] && return 0 || fail "pipeline completed partialSuccess" ;;
        failed|stopped) fail "pipeline completed ${state}" ;;
      esac
    fi
    sleep "$SMOKE_POLL_SECONDS"
    elapsed=$((elapsed + SMOKE_POLL_SECONDS))
  done
  fail "pipeline ${fqn} did not reach success within ${SMOKE_WAIT_SECONDS}s"
}

kill_running_run_and_assert_stopped() {
  local pipeline_id="$1"
  local fqn="$2"
  local run_id="$3"
  local encoded_fqn state elapsed=0
  encoded_fqn="$(urlencode "$fqn")"
  while (( elapsed <= SMOKE_WAIT_SECONDS )); do
    if [[ -n "$run_id" ]]; then
      om_call GET "/v1/services/ingestionPipelines/${encoded_fqn}/pipelineStatus/${run_id}" ""
    else
      om_call GET "/v1/services/ingestionPipelines/${encoded_fqn}/pipelineStatus?limit=1" ""
    fi
    if [[ "$LAST_STATUS" == "200" ]]; then
      state="$(json_field "$LAST_BODY" pipelineState)"
      [[ -n "$state" ]] || state="$(json_field "$LAST_BODY" data.0.pipelineState)"
      echo "pre-kill pipeline state=${state:-unknown}"
      case "$state" in
        queued|running)
          om_call POST "/v1/services/ingestionPipelines/kill/${pipeline_id}" ""
          expect_status 200
          break
          ;;
        success|partialSuccess|failed|stopped) fail "pipeline reached ${state} before running kill could be tested" ;;
      esac
    fi
    sleep "$SMOKE_POLL_SECONDS"
    elapsed=$((elapsed + SMOKE_POLL_SECONDS))
  done
  (( elapsed <= SMOKE_WAIT_SECONDS )) || fail "pipeline never became killable within ${SMOKE_WAIT_SECONDS}s"

  elapsed=0
  while (( elapsed <= SMOKE_WAIT_SECONDS )); do
    if [[ -n "$run_id" ]]; then
      om_call GET "/v1/services/ingestionPipelines/${encoded_fqn}/pipelineStatus/${run_id}" ""
    else
      om_call GET "/v1/services/ingestionPipelines/${encoded_fqn}/pipelineStatus?limit=1" ""
    fi
    if [[ "$LAST_STATUS" == "200" ]]; then
      state="$(json_field "$LAST_BODY" pipelineState)"
      [[ -n "$state" ]] || state="$(json_field "$LAST_BODY" data.0.pipelineState)"
      echo "post-kill pipeline state=${state:-unknown}"
      if [[ -z "$run_id" && -z "$state" && "$(json_has_data "$LAST_BODY")" == "0" ]]; then
        echo "kill lifecycle: PASS (1.12.10 returned no active run after accepted kill)"
        return 0
      fi
      [[ "$state" == "stopped" ]] && {
        echo "kill lifecycle: PASS (running pipeline reached stopped)"
        return 0
      }
      [[ "$state" == "success" || "$state" == "partialSuccess" || "$state" == "failed" ]] \
        && fail "killed pipeline reached unexpected terminal state ${state}"
    fi
    sleep "$SMOKE_POLL_SECONDS"
    elapsed=$((elapsed + SMOKE_POLL_SECONDS))
  done
  fail "killed pipeline did not reach stopped within ${SMOKE_WAIT_SECONDS}s"
}

read_hierarchy() {
  local encoded service_db schema table
  if [[ -z "$SMOKE_DATABASE_FQN" ]]; then
    encoded="$(urlencode "$SERVICE_FQN")"
    om_call GET "/v1/databases?service=${encoded}&limit=100&include=non-deleted" ""
    expect_status 200
    SMOKE_DATABASE_FQN="$(json_field "$LAST_BODY" data.0.fullyQualifiedName)"
  fi
  [[ -n "$SMOKE_DATABASE_FQN" ]] || fail "metadata scan returned no database"
  if [[ -z "$SMOKE_TABLE_FQN" ]]; then
    encoded="$(urlencode "$SMOKE_DATABASE_FQN")"
    om_call GET "/v1/databaseSchemas?database=${encoded}&limit=100&include=non-deleted" ""
    expect_status 200
    schema="$(json_field "$LAST_BODY" data.0.fullyQualifiedName)"
    [[ -n "$schema" ]] || fail "metadata scan returned no database schema"
    encoded="$(urlencode "$schema")"
    om_call GET "/v1/tables?databaseSchema=${encoded}&fields=columns,tableConstraints&include=non-deleted&limit=100" ""
    expect_status 200
    SMOKE_TABLE_FQN="$(json_field "$LAST_BODY" data.0.fullyQualifiedName)"
  fi
  [[ -n "$SMOKE_TABLE_FQN" ]] || fail "metadata scan returned no table"
  echo "hierarchy: database=${SMOKE_DATABASE_FQN} table=${SMOKE_TABLE_FQN}"
}

assert_profile() {
  local encoded="$1"
  om_call GET "/v1/tables/${encoded}/tableProfile/latest?includeColumnProfile=true" ""
  expect_status 200
  python3 - "$LAST_BODY" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1]))
profile = data.get("profile")
columns = data.get("columns") or []
profiled_columns = [column for column in columns if column.get("profile")]
if not profile or not profiled_columns:
    raise SystemExit("latest table profile or column profiles are missing")
print("profile: table profile and %d column profiles present" % len(profiled_columns))
PY
}

# Service is either supplied by ID/FQN (never deleted by this script), or is
# created from an operator-provided secret-bearing file and then owned/cleaned.
if [[ -n "$SMOKE_EXISTING_SERVICE_ID" ]]; then
  SERVICE_ID="$SMOKE_EXISTING_SERVICE_ID"
  om_call GET "/v1/services/databaseServices/${SERVICE_ID}?include=non-deleted" ""
  expect_status 200
  SERVICE_FQN="$(json_field "$LAST_BODY" fullyQualifiedName)"
elif [[ -n "$SMOKE_EXISTING_SERVICE_FQN" ]]; then
  om_call GET "/v1/services/databaseServices?limit=100&include=non-deleted" ""
  expect_status 200
  service_pair="$(python3 - "$LAST_BODY" "$SMOKE_EXISTING_SERVICE_FQN" <<'PY'
import json
import sys
for item in json.load(open(sys.argv[1])).get("data", []):
    if item.get("fullyQualifiedName") == sys.argv[2] or item.get("name") == sys.argv[2]:
        print("%s\t%s" % (item.get("id", ""), item.get("fullyQualifiedName") or item.get("name", "")))
        break
PY
)"
  SERVICE_ID="${service_pair%%$'\t'*}"
  SERVICE_FQN="${service_pair#*$'\t'}"
  [[ -n "$SERVICE_ID" ]] || fail "existing service not found: ${SMOKE_EXISTING_SERVICE_FQN}"
  [[ -n "$SERVICE_FQN" ]] || fail "existing service response lacks fullyQualifiedName: ${SMOKE_EXISTING_SERVICE_FQN}"
else
  [[ "$SMOKE_SERVICE_NAME" == "${SMOKE_PREFIX}"* ]] || fail "owned service name must be prefixed by SMOKE_PREFIX"
  payload="$(build_service_payload)"
  om_call POST "/v1/services/databaseServices" "$payload"
  expect_created_status
  SERVICE_ID="$(json_field "$LAST_BODY" id)"
  SERVICE_FQN="$(json_field "$LAST_BODY" fullyQualifiedName)"
  [[ -n "$SERVICE_ID" && -n "$SERVICE_FQN" ]] || fail "service response lacks id/FQN"
  SERVICE_OWNED=1
fi

echo "service: ${SERVICE_FQN} (${SERVICE_ID})"

metadata_pair="$(ensure_pipeline "${SMOKE_PREFIX}_metadata" metadata)"
METADATA_ID="${metadata_pair%%$'\t'*}"
METADATA_FQN="${metadata_pair#*$'\t'}"
profiler_pair="$(ensure_pipeline "${SMOKE_PREFIX}_profiler" profiler "${SMOKE_DATABASE_FQN}")"
PROFILER_ID="${profiler_pair%%$'\t'*}"
PROFILER_FQN="${profiler_pair#*$'\t'}"

om_call POST "/v1/services/ingestionPipelines/deploy/${METADATA_ID}" ""
expect_status 200
om_call POST "/v1/services/ingestionPipelines/deploy/${PROFILER_ID}" ""
expect_status 200

if [[ "$SMOKE_RUN_PIPELINES" == "1" ]]; then
  om_call POST "/v1/services/ingestionPipelines/trigger/${METADATA_ID}" ""
  expect_status 200
  trigger_run_id="$(json_field "$LAST_BODY" pipelineRunId)"
  [[ -n "$trigger_run_id" ]] || trigger_run_id="$(json_field "$LAST_BODY" runId)"
  RUNNING_PIPELINE_ID="$METADATA_ID"
  if [[ "$SMOKE_KILL_RUNNING" == "1" ]]; then
    kill_running_run_and_assert_stopped "$METADATA_ID" "$METADATA_FQN" "$trigger_run_id"
    RUNNING_PIPELINE_ID=""
    echo "Sprint 0 running-kill smoke: PASS (dedicated mode; cleanup=${SMOKE_CLEANUP})"
    exit 0
  fi
  wait_for_run "$METADATA_FQN" "$trigger_run_id"
  RUNNING_PIPELINE_ID=""
  read_hierarchy

  # The database name is known only after metadata discovery. Update the
  # profiler with the 1.12.10 databaseFilterPattern, then trigger it.
  profiler_pair="$(ensure_pipeline "${SMOKE_PREFIX}_profiler" profiler "${SMOKE_DATABASE_FQN##*.}")"
  PROFILER_ID="${profiler_pair%%$'\t'*}"
  PROFILER_FQN="${profiler_pair#*$'\t'}"
  om_call POST "/v1/services/ingestionPipelines/deploy/${PROFILER_ID}" ""
  expect_status 200
  om_call POST "/v1/services/ingestionPipelines/trigger/${PROFILER_ID}" ""
  expect_status 200
  trigger_run_id="$(json_field "$LAST_BODY" pipelineRunId)"
  [[ -n "$trigger_run_id" ]] || trigger_run_id="$(json_field "$LAST_BODY" runId)"
  RUNNING_PIPELINE_ID="$PROFILER_ID"
  wait_for_run "$PROFILER_FQN" "$trigger_run_id"
  RUNNING_PIPELINE_ID=""
  encoded="$(urlencode "$SMOKE_TABLE_FQN")"
  assert_profile "$encoded"
else
  echo "execution: SKIPPED (SMOKE_RUN_PIPELINES=${SMOKE_RUN_PIPELINES})"
fi

if [[ -n "$SMOKE_ASSERT_MARK_DELETED_FQN" ]]; then
  encoded="$(urlencode "$SMOKE_ASSERT_MARK_DELETED_FQN")"
  om_call GET "/v1/tables/name/${encoded}?include=all" ""
  if [[ "$LAST_STATUS" == "404" ]]; then
    echo "markDeleted: PASS (source-deleted table is absent)"
  else
    expect_status 200
    deleted="$(json_field "$LAST_BODY" deleted)"
    [[ "$deleted" == "true" ]] || fail "markDeleted assertion failed for ${SMOKE_ASSERT_MARK_DELETED_FQN}"
    echo "markDeleted: PASS (table is soft-deleted)"
  fi
else
  echo "markDeleted: SKIPPED (set SMOKE_ASSERT_MARK_DELETED_FQN after deleting a source object)"
fi

if [[ "$SMOKE_RUN_PIPELINES" == "1" ]]; then
  # A completed run has no live workflow to kill. 1.12.10 returns 400 in that
  # case; keep it as evidence rather than pretending a kill happened. To
  # verify an actual running kill, invoke this endpoint while a source run is
  # queued and set SMOKE_KILL_RUNNING=1 in a dedicated fixture environment.
  om_call POST "/v1/services/ingestionPipelines/kill/${METADATA_ID}" ""
  case "$LAST_STATUS" in
    200) echo "kill lifecycle: PASS (OM accepted kill)" ;;
    400) echo "kill lifecycle: OBSERVED 400 (no active run after successful wait)" ;;
    404) echo "kill lifecycle: OBSERVED 404 (pipeline/run already absent)" ;;
    *) fail "unexpected kill status ${LAST_STATUS}" ;;
  esac
else
  echo "kill lifecycle: SKIPPED (SMOKE_RUN_PIPELINES=${SMOKE_RUN_PIPELINES})"
fi

if [[ "$SMOKE_CLEANUP" == "1" ]]; then
  delete_pipeline_and_assert_absent "$METADATA_ID"
  delete_pipeline_and_assert_absent "$PROFILER_ID"
  METADATA_ID=""
  PROFILER_ID=""
  : > "${TMP_DIR}/pipeline_ids"
  if [[ "$SERVICE_OWNED" == "1" ]]; then
    om_call DELETE "/v1/services/databaseServices/${SERVICE_ID}?recursive=true&hardDelete=true" ""
    case "$LAST_STATUS" in
      200|204|404) ;;
      *) fail "owned service hard delete returned HTTP ${LAST_STATUS}: ${SERVICE_ID}" ;;
    esac
    om_call GET "/v1/services/databaseServices/${SERVICE_ID}?include=all" ""
    [[ "$LAST_STATUS" == "404" ]] \
      || fail "owned service remains readable after hard delete: ${SERVICE_ID} (HTTP ${LAST_STATUS})"
    echo "service delete: PASS (${SERVICE_ID})"
    SERVICE_ID=""
    SERVICE_OWNED=0
  fi
fi

echo "Sprint 0 smoke: PASS (all requested steps reached; cleanup=${SMOKE_CLEANUP})"
