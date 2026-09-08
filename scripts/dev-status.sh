#!/usr/bin/env bash
# Report whether local frontend/backend ports and Agent PID files look healthy.

# shellcheck source=dev-common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/dev-common.sh"

if [[ -f "$ROOT/.env" ]]; then
  load_dotenv
fi

status_one() {
  local label="$1"
  local port="$2"
  local pid_file="$3"
  local listening="no"
  local pid_state="missing"
  local pid="-"

  if port_listening "$port"; then
    listening="yes"
  fi
  if [[ -f "$pid_file" ]]; then
    pid="$(read_pid "$pid_file" || true)"
    if pid_alive "$pid_file"; then
      pid_state="alive"
    else
      pid_state="stale"
    fi
  fi
  printf "%-9s port=%-5s listening=%-3s pid_file=%-6s pid=%s\n" \
    "$label" "$port" "$listening" "$pid_state" "$pid"
}

status_one "backend" "$BACKEND_PORT" "$BACKEND_PID_FILE"
status_one "frontend" "$FRONTEND_PORT" "$FRONTEND_PID_FILE"

if port_listening "$BACKEND_PORT" && port_listening "$FRONTEND_PORT"; then
  echo "note: both ports listen, but that may still be OLD code."
  echo "      After source changes, run: scripts/dev-restart.sh"
  exit 0
fi

echo "stack not fully up; start with: scripts/dev-up.sh"
exit 1
