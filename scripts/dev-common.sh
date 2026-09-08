#!/usr/bin/env bash
# Shared helpers for Agent-facing frontend/backend lifecycle scripts.
# Sourced by: scripts/dev-{up,down,restart,status}.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

JAVA_HOME_DEFAULT="/opt/jdk-21.0.11+10"
export JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"
export PATH="$JAVA_HOME/bin:$PATH"

BACKEND_PORT="${SERVER_PORT:-9527}"
FRONTEND_PORT="${FRONTEND_PORT:-8000}"
BACKEND_MODULE="seatunnel-web-api"
BACKEND_JAR="$ROOT/$BACKEND_MODULE/target/seatunnel-web-api.jar"
UI_DIR="$ROOT/seatunnel-web-ui"

LOG_DIR="$ROOT/logs"
mkdir -p "$LOG_DIR"

BACKEND_PID_FILE="$LOG_DIR/dev-backend.pid"
FRONTEND_PID_FILE="$LOG_DIR/dev-frontend.pid"
BACKEND_LOG="$LOG_DIR/dev-backend.log"
FRONTEND_LOG="$LOG_DIR/dev-frontend.log"

BACKEND_READY_TIMEOUT="${BACKEND_READY_TIMEOUT:-300}"
FRONTEND_READY_TIMEOUT="${FRONTEND_READY_TIMEOUT:-180}"

port_listening() {
  local port="$1"
  ss -ltn "( sport = :${port} )" 2>/dev/null | grep -q LISTEN
}

# Parse KEY=VALUE without bash interpreting &, $, #, spaces in values.
# VS Code envFile does the same; `source .env` breaks JDBC URLs that contain `&`.
load_dotenv() {
  local env_file="$ROOT/.env"
  if [[ ! -f "$env_file" ]]; then
    echo "error: missing $env_file (required for local API; copy from .env.example)" >&2
    exit 1
  fi
  local line key val
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ -z "${line//[[:space:]]/}" || "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" == *"="* ]] || continue
    key="${line%%=*}"
    val="${line#*=}"
    key="${key#"${key%%[![:space:]]*}"}"
    key="${key%"${key##*[![:space:]]}"}"
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    if [[ "$val" =~ ^\"(.*)\"$ ]]; then
      val="${BASH_REMATCH[1]}"
    elif [[ "$val" =~ ^\'(.*)\'$ ]]; then
      val="${BASH_REMATCH[1]}"
    fi
    export "$key=$val"
  done <"$env_file"
  export SPRING_FLYWAY_REPAIR_ON_MIGRATE="${SPRING_FLYWAY_REPAIR_ON_MIGRATE:-false}"
  BACKEND_PORT="${SERVER_PORT:-$BACKEND_PORT}"
}

pid_alive() {
  local pid_file="$1"
  [[ -f "$pid_file" ]] || return 1
  local pid
  pid="$(tr -d '[:space:]' <"$pid_file")"
  [[ -n "$pid" ]] || return 1
  kill -0 "$pid" 2>/dev/null
}

read_pid() {
  local pid_file="$1"
  [[ -f "$pid_file" ]] || return 1
  tr -d '[:space:]' <"$pid_file"
}

kill_pid_tree() {
  local pid_file="$1"
  local label="$2"
  if ! pid_alive "$pid_file"; then
    rm -f "$pid_file"
    return 0
  fi
  local pid
  pid="$(read_pid "$pid_file")"
  echo "stopping $label (pid=$pid)"
  # Kill process group if started with setsid; fall back to pid then children.
  kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
  local i
  for i in $(seq 1 30); do
    if ! kill -0 "$pid" 2>/dev/null; then
      rm -f "$pid_file"
      return 0
    fi
    sleep 1
  done
  kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
  # Best-effort: reap direct children left by Maven/spring-boot:run
  pkill -KILL -P "$pid" 2>/dev/null || true
  rm -f "$pid_file"
}

free_port() {
  local port="$1"
  local label="$2"
  if ! port_listening "$port"; then
    return 0
  fi
  echo "freeing $label port $port"
  fuser -k "${port}/tcp" 2>/dev/null || true
  local i
  for i in $(seq 1 20); do
    if ! port_listening "$port"; then
      return 0
    fi
    sleep 0.5
  done
  echo "error: port $port still in use after free attempt" >&2
  return 1
}

wait_for_port() {
  local port="$1"
  local label="$2"
  local timeout="$3"
  local log_file="$4"
  local elapsed=0
  echo "waiting for $label on :$port (timeout ${timeout}s)"
  while (( elapsed < timeout )); do
    if port_listening "$port"; then
      echo "$label is listening on :$port"
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  echo "error: $label did not become ready on :$port within ${timeout}s" >&2
  if [[ -f "$log_file" ]]; then
    echo "---- last 80 lines of $log_file ----" >&2
    tail -n 80 "$log_file" >&2 || true
  fi
  return 1
}

require_tools() {
  local missing=0
  local t
  for t in ss fuser curl; do
    if ! command -v "$t" >/dev/null 2>&1; then
      echo "error: required tool not found: $t" >&2
      missing=1
    fi
  done
  if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
    echo "error: java not found at $JAVA_HOME/bin/java" >&2
    missing=1
  fi
  if [[ ! -x "$ROOT/mvnw" ]]; then
    echo "error: mvnw not found at $ROOT/mvnw" >&2
    missing=1
  fi
  if [[ ! -d "$UI_DIR" ]]; then
    echo "error: frontend dir missing: $UI_DIR" >&2
    missing=1
  fi
  (( missing == 0 ))
}
