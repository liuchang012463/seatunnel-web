#!/usr/bin/env bash
# Smoke-test helper for the RabbitMQ messaging endpoints (/api/v1/message/*).
# Usage:
#   scripts/message-api-demo.sh push      # publish one message
#   scripts/message-api-demo.sh pull      # pull a batch
#   scripts/message-api-demo.sh push-err  # publish with unknown clientType (error path)
#   scripts/message-api-demo.sh oversize  # publish a >1MB body (should be rejected)
#   scripts/message-api-demo.sh help      # show this help
#
# Prerequisites -- read before running:
#
#   1) Configure the broker on the SeaTunnel Web server via
#      seatunnel.message.broker.* (application.yml or SEATUNNEL_MESSAGE_BROKER_*).
#      This script no longer sends connection credentials in the request body.
#   2) These endpoints do not require an API key.
#
# Overridable environment variables:
#   SEATUNNEL_WEB_BASE_URL     platform base url        (default http://localhost:9527)
#   MQ_QUEUE                   target queue             (default order.sync)
#
# Note: this script only sends HTTP requests. It never touches RabbitMQ directly.

set -euo pipefail

BASE_URL="${SEATUNNEL_WEB_BASE_URL:-http://localhost:9527}"
MQ_QUEUE="${MQ_QUEUE:-order.sync}"

usage() {
  sed -n '2,21p' "$0" | sed 's/^# \{0,1\}//'
  exit 0
}

# Builds the JSON body. The message itself stays a real JSON object, so no
# string escaping is needed here.
push_body() {
  local payload="$1"
  cat <<JSON
{
  "queue": "${MQ_QUEUE}",
  "message": ${payload},
  "persistent": true
}
JSON
}

pull_body() {
  cat <<JSON
{
  "queue": "${MQ_QUEUE}",
  "maxMessages": 10,
  "timeoutMs": 3000
}
JSON
}

post() {
  local path="$1"
  local body="$2"
  curl -sS -X POST "${BASE_URL}${path}" \
    -H 'Content-Type: application/json' \
    -w '\nHTTP %{http_code}\n' \
    -d "${body}"
}

# Same as post(), but reads the body from a file. Required for large payloads:
# passing megabytes of JSON as an argv argument hits the OS argument-length
# limit (ARG_MAX) and fails with "Argument list too long".
post_file() {
  local path="$1"
  local file="$2"
  curl -sS -X POST "${BASE_URL}${path}" \
    -H 'Content-Type: application/json' \
    -w '\nHTTP %{http_code}\n' \
    --data-binary "@${file}"
}
cmd_push() {
  echo "==> POST /api/v1/message/push  queue=${MQ_QUEUE}"
  post /api/v1/message/push "$(push_body '{
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
  echo "==> POST /api/v1/message/push  with unknown clientType (expect a failure envelope)"
  post /api/v1/message/push '{
  "clientType": "KAFKA",
  "queue": "'"${MQ_QUEUE}"'",
  "message": {"orderId": "ERR"},
  "persistent": true
}'
}

cmd_oversize() {
  echo "==> POST /api/v1/message/push  with a ~1.5MB body (expect rejection)"
  local tmp
  tmp="$(mktemp)"
  trap 'rm -f "${tmp}"' RETURN
  # Build via a temp file: 1.5MB cannot be passed as an argv argument.
  {
    printf '{\n  "queue": "%s",\n  "message": { "blob": "' "${MQ_QUEUE}"
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
