#!/usr/bin/env bash
# Stop Agent-managed local frontend/backend started by scripts/dev-up.sh.

# shellcheck source=dev-common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/dev-common.sh"

# Load .env only for SERVER_PORT override; missing file is OK when tearing down.
if [[ -f "$ROOT/.env" ]]; then
  load_dotenv
fi

kill_pid_tree "$BACKEND_PID_FILE" "backend" || true
kill_pid_tree "$FRONTEND_PID_FILE" "frontend" || true

free_port "$BACKEND_PORT" "backend" || true
free_port "$FRONTEND_PORT" "frontend" || true

echo "dev stack stopped (ports ${BACKEND_PORT}, ${FRONTEND_PORT})"
