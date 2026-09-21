#!/usr/bin/env bash
# Smoke-test helper for the RabbitMQ messaging endpoints (/api/v1/message/*).
# Usage:
#   scripts/message-api-demo.sh push      # publish one message
#   scripts/message-api-demo.sh pull      # pull a batch
#   scripts/message-api-demo.sh push-err  # publish against a bad password (error path)
#   scripts/message-api-demo.sh oversize  # publish a >1MB body (should be rejected)
#   scripts/message-api-demo.sh help      # show this help
#
# Overridable environment variables:
#   SEATUNNEL_WEB_BASE_URL   platform base url          (default http://localhost:9527)
#   SEATUNNEL_MESSAGE_API_KEY  api key, empty = skip header
#   MQ_HOST / MQ_PORT / MQ_VHOST / MQ_USER / MQ_PASSWORD
#   MQ_QUEUE                 target queue               (default order.sync)
#
# Note: this script only sends HTTP requests. It never touches RabbitMQ directly.

set -euo pipefail

BASE_URL="${SEATUNNEL_WEB_BASE_URL:-http://localhost:9527}"
API_KEY="${SEATUNNEL_MESSAGE_API_KEY:-}"
MQ_HOST="${MQ_HOST:-localhost}"
MQ_PORT="${MQ_PORT:-5672}"
MQ_VHOST="${MQ_VHOST:-/}"
MQ_USER="${MQ_USER:-guest}"
MQ_PASSWORD="${MQ_PASSWORD:-guest}"
MQ_QUEUE="${MQ_QUEUE:-order.sync}"

usage() {
  sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'
  exit 0
}

# Builds the JSON body. The message itself stays a real JSON object, so no
# string escaping is needed here.
push_body() {
  local password="$1"
  local payload="$2"
  cat <<JSON
{
  "connection": {
    "host": "${MQ_HOST}",
    "port": ${MQ_PORT},
    "virtualHost": "${MQ_VHOST}",
    "username": "${MQ_USER}",
    "password": "${password}"
  },
  "queue": "${MQ_QUEUE}",
  "message": ${payload},
  "persistent": true
}
JSON
}

pull_body() {
  cat <<JSON
{
  "connection": {
    "host": "${MQ_HOST}",
    "port": ${MQ_PORT},
    "virtualHost": "${MQ_VHOST}",
    "username": "${MQ_USER}",
    "password": "${MQ_PASSWORD}"
  },
  "queue": "${MQ_QUEUE}",
  "maxMessages": 10,
  "timeoutMs": 3000
}
JSON
}

post() {
  local path="$1"
  local body="$2"
  local args=(-sS -X POST "${BASE_URL}${path}" -H 'Content-Type: application/json' -w '\nHTTP %{http_code}\n')
  if [[ -n "${API_KEY}" ]]; then
    args+=(-H "X-Api-Key: ${API_KEY}")
  fi
  curl "${args[@]}" -d "${body}"
}

# Same as post(), but reads the body from a file. Required for large payloads:
# passing megabytes of JSON as an argv argument hits the OS argument-length
# limit (ARG_MAX) and fails with "Argument list too long".
post_file() {
  local path="$1"
  local file="$2"
  local args=(-sS -X POST "${BASE_URL}${path}" -H 'Content-Type: application/json' -w '\nHTTP %{http_code}\n')
  if [[ -n "${API_KEY}" ]]; then
    args+=(-H "X-Api-Key: ${API_KEY}")
  fi
  curl "${args[@]}" --data-binary "@${file}"
}

cmd_push() {
  echo "==> POST /api/v1/message/push  queue=${MQ_QUEUE}"
  post /api/v1/message/push "$(push_body "${MQ_PASSWORD}" '{
    "orderId": "A001",
    "amount": 100,
    "items": [ { "sku": "X1" } ],
    "note": "中文与嵌套结构都支持"
  }')"
}

cmd_pull() {
  echo "==> POST /api/v1/message/pull  queue=${MQ_QUEUE}"
  post /api/v1/message/pull "$(pull_body)"
}

cmd_push_err() {
  echo "==> POST /api/v1/message/push  with a wrong password (expect a masked error)"
  post /api/v1/message/push "$(push_body 'definitely-wrong-password' '{"orderId": "ERR"}')"
}

cmd_oversize() {
  echo "==> POST /api/v1/message/push  with a ~1.5MB body (expect rejection)"
  local tmp
  tmp="$(mktemp)"
  trap 'rm -f "${tmp}"' RETURN
  # Build via a temp file: 1.5MB cannot be passed as an argv argument.
  {
    printf '{\n  "connection": {\n    "host": "%s",\n    "port": %s,\n    "virtualHost": "%s",\n    "username": "%s",\n    "password": "%s"\n  },\n' \
      "${MQ_HOST}" "${MQ_PORT}" "${MQ_VHOST}" "${MQ_USER}" "${MQ_PASSWORD}"
    printf '  "queue": "%s",\n  "message": { "blob": "' "${MQ_QUEUE}"
    head -c 1572864 /dev/zero | tr '\0' 'x'
    printf '" },\n  "persistent": true\n}\n'
  } > "${tmp}"
  post_file /api/v1/message/push "${tmp}"
}

case "${1:-help}" in
  push)     cmd_push ;;
  pull)     cmd_pull ;;
  push-err) cmd_push_err ;;
  oversize) cmd_oversize ;;
  help|-h|--help) usage ;;
  *)
    echo "error: unknown command '${1}'" >&2
    echo "run 'scripts/message-api-demo.sh help' for usage" >&2
    exit 1
    ;;
esac
