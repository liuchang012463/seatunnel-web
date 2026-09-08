#!/usr/bin/env bash
# Start local backend (:9527) and frontend (:8000) for Agent verification.
# Mirrors .vscode/launch.json (SeaTunnelWebApplication + SeaTunnel Web UI).
# If a port is already taken, exits non-zero — use scripts/dev-restart.sh after code changes.

# shellcheck source=dev-common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/dev-common.sh"

require_tools
load_dotenv

if port_listening "$BACKEND_PORT"; then
  echo "error: backend port $BACKEND_PORT already in use." >&2
  echo "  Port listening does NOT mean the process has your latest code." >&2
  echo "  After code changes run: scripts/dev-restart.sh" >&2
  echo "  To inspect: scripts/dev-status.sh" >&2
  exit 1
fi

if port_listening "$FRONTEND_PORT"; then
  echo "error: frontend port $FRONTEND_PORT already in use." >&2
  echo "  Port listening does NOT mean the process has your latest code." >&2
  echo "  After code changes run: scripts/dev-restart.sh" >&2
  echo "  To inspect: scripts/dev-status.sh" >&2
  exit 1
fi

echo "building backend (./mvnw -pl $BACKEND_MODULE -am -DskipTests package)"
: >"$BACKEND_LOG"
./mvnw -pl "$BACKEND_MODULE" -am -DskipTests package >>"$BACKEND_LOG" 2>&1 || {
  echo "error: backend package failed; see $BACKEND_LOG" >&2
  tail -n 80 "$BACKEND_LOG" >&2 || true
  exit 1
}
if [[ ! -f "$BACKEND_JAR" ]]; then
  echo "error: missing $BACKEND_JAR after package" >&2
  exit 1
fi

echo "starting backend (java -jar $BACKEND_JAR, port=$BACKEND_PORT)"
echo "==== runtime ====" >>"$BACKEND_LOG"
setsid bash -c "
  cd \"$ROOT\"
  export JAVA_HOME=\"$JAVA_HOME\"
  export PATH=\"\$JAVA_HOME/bin:\$PATH\"
  set -a
  # shellcheck disable=SC1090
  source \"$ROOT/.env\"
  set +a
  export SPRING_FLYWAY_REPAIR_ON_MIGRATE=\"\${SPRING_FLYWAY_REPAIR_ON_MIGRATE:-false}\"
  # shellcheck disable=SC2086
  exec java \${JAVA_OPTS:-} -jar \"$BACKEND_JAR\"
" >>"$BACKEND_LOG" 2>&1 &
echo $! >"$BACKEND_PID_FILE"

echo "starting frontend (port=$FRONTEND_PORT)"
: >"$FRONTEND_LOG"
setsid bash -c "
  cd \"$UI_DIR\"
  export CHECK_TIMEOUT=300
  export REACT_APP_ENV=dev
  export MOCK=none
  export UMI_ENV=dev
  export PORT=\"$FRONTEND_PORT\"
  exec yarn exec max dev
" >>"$FRONTEND_LOG" 2>&1 &
echo $! >"$FRONTEND_PID_FILE"

wait_for_port "$BACKEND_PORT" "backend" "$BACKEND_READY_TIMEOUT" "$BACKEND_LOG"
wait_for_port "$FRONTEND_PORT" "frontend" "$FRONTEND_READY_TIMEOUT" "$FRONTEND_LOG"

echo "dev stack ready:"
echo "  backend  http://127.0.0.1:${BACKEND_PORT}  (pid $(read_pid "$BACKEND_PID_FILE" || echo '?'), log $BACKEND_LOG)"
echo "  frontend http://127.0.0.1:${FRONTEND_PORT}  (pid $(read_pid "$FRONTEND_PID_FILE" || echo '?'), log $FRONTEND_LOG)"
