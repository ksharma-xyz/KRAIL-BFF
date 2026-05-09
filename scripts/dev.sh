#!/usr/bin/env bash
# scripts/dev.sh — one-command local dev environment.
#
#   ./scripts/dev.sh up      Start BFF on :8080 + api-tester on :8000, wait until healthy, print the URL.
#   ./scripts/dev.sh down    Stop both.
#   ./scripts/dev.sh status  Report what's running.
#   ./scripts/dev.sh logs    Tail the BFF log.
#   ./scripts/dev.sh restart Same as down + up.
#
# Idempotent — running `up` when already up just re-checks and prints the URL.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BFF_PORT=8080
TESTER_PORT=8000
LOG_DIR="$ROOT/build/dev"
BFF_LOG="$LOG_DIR/bff.log"
TESTER_LOG="$LOG_DIR/tester.log"
BFF_PID="$LOG_DIR/bff.pid"
TESTER_PID="$LOG_DIR/tester.pid"

# ---- pretty printing ------------------------------------------------------
g() { printf "\033[32m%s\033[0m\n" "$*"; }   # green
y() { printf "\033[33m%s\033[0m\n" "$*"; }   # yellow
r() { printf "\033[31m%s\033[0m\n" "$*"; }   # red
b() { printf "\033[1m%s\033[0m\n" "$*"; }    # bold

# ---- helpers --------------------------------------------------------------
pid_on_port() { lsof -tiTCP:"$1" -sTCP:LISTEN 2>/dev/null | head -1; }

is_up() {
  curl -fsS --max-time 2 "http://localhost:$BFF_PORT/health" >/dev/null 2>&1
}

ensure_local_properties() {
  local file="$ROOT/local.properties"
  if [[ ! -f "$file" ]]; then
    r "✗ local.properties not found"
    echo "  Create it from the template and add your NSW key:"
    echo "    cp local.properties.template local.properties"
    echo "    # then edit and set: nsw.apiKey=<your NSW Open Data key>"
    return 1
  fi
  if ! grep -qE "^nsw\.apiKey=." "$file"; then
    r "✗ nsw.apiKey not set in local.properties"
    echo "  Add: nsw.apiKey=<your NSW Open Data key>"
    return 1
  fi
  # Ensure CORS allows the api-tester served on :8000.
  if ! grep -q "^bff\.cors\.origins=" "$file"; then
    echo "bff.cors.origins=http://localhost:$TESTER_PORT" >> "$file"
    g "✓ added bff.cors.origins=http://localhost:$TESTER_PORT to local.properties"
  elif ! grep -E "^bff\.cors\.origins=" "$file" | grep -q "localhost:$TESTER_PORT"; then
    y "⚠ bff.cors.origins already set but doesn't include http://localhost:$TESTER_PORT — api-tester may hit CORS errors"
    echo "  Current: $(grep -E '^bff\.cors\.origins=' "$file")"
  fi
}

# ---- subcommands ----------------------------------------------------------
cmd_up() {
  cd "$ROOT"
  mkdir -p "$LOG_DIR"

  ensure_local_properties || exit 1

  if is_up; then
    g "✓ BFF already up at http://localhost:$BFF_PORT"
  else
    if pid_on_port "$BFF_PORT" >/dev/null; then
      y "⚠ Port $BFF_PORT is taken by PID $(pid_on_port "$BFF_PORT") — killing"
      lsof -tiTCP:"$BFF_PORT" -sTCP:LISTEN 2>/dev/null | xargs -r kill -9 2>/dev/null || true
      sleep 1
    fi

    b "→ Starting BFF (gradle compile + server boot, ~30–60s on first run)..."
    nohup ./gradlew :server:run --no-daemon > "$BFF_LOG" 2>&1 &
    echo $! > "$BFF_PID"

    # Poll /health until ready.
    local i=0
    while ! is_up; do
      i=$((i + 1))
      if [[ $i -gt 90 ]]; then
        r "✗ BFF didn't come up within 3 min. Last 30 lines of log:"
        tail -30 "$BFF_LOG" | sed 's/^/    /'
        echo "  Full log: $BFF_LOG"
        return 1
      fi
      printf "."
      sleep 2
    done
    echo ""
    g "✓ BFF up at http://localhost:$BFF_PORT (PID $(cat "$BFF_PID"))"
  fi

  # Static server for the api-tester.
  if pid_on_port "$TESTER_PORT" >/dev/null; then
    g "✓ api-tester static server already up at http://localhost:$TESTER_PORT"
  else
    nohup python3 -m http.server -d "$ROOT/docs/tools" "$TESTER_PORT" > "$TESTER_LOG" 2>&1 &
    echo $! > "$TESTER_PID"
    sleep 1
    if curl -fsS -I "http://localhost:$TESTER_PORT/api-tester.html" >/dev/null 2>&1; then
      g "✓ api-tester at http://localhost:$TESTER_PORT/api-tester.html (PID $(cat "$TESTER_PID"))"
    else
      r "✗ Static server didn't come up. Check $TESTER_LOG"
      return 1
    fi
  fi

  echo ""
  b "──────────────────────────────────────────────────────────────"
  b "  Open http://localhost:$TESTER_PORT/api-tester.html"
  b "──────────────────────────────────────────────────────────────"
  echo ""
  echo "In the tester:"
  echo "  • Base URL: http://localhost:$BFF_PORT"
  echo "  • Authorization: leave BLANK (BFF holds the NSW key server-side)"
  echo "  • X-Krail-Version: leave blank (gate disabled by default)"
  echo "  • Click 'Run all GETs' to smoke-test everything"
  echo ""
  echo "Logs:    $BFF_LOG"
  echo "Stop:    ./scripts/dev.sh down"
}

cmd_down() {
  local stopped=0
  for tag in BFF tester; do
    local pidfile pid port
    if [[ "$tag" == BFF ]]; then pidfile="$BFF_PID"; port=$BFF_PORT; else pidfile="$TESTER_PID"; port=$TESTER_PORT; fi
    pid=$(cat "$pidfile" 2>/dev/null || true)
    if [[ -n "${pid:-}" ]] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      g "✓ Stopped $tag (PID $pid)"
      rm -f "$pidfile"
      stopped=$((stopped + 1))
    fi
    # Belt-and-braces: kill anything still on the port.
    local listener
    listener=$(pid_on_port "$port" || true)
    if [[ -n "$listener" ]]; then
      kill -9 "$listener" 2>/dev/null || true
      g "✓ Killed stray listener on :$port (PID $listener)"
      stopped=$((stopped + 1))
    fi
  done
  if [[ $stopped -eq 0 ]]; then y "Nothing was running."; fi
}

cmd_status() {
  if is_up; then
    g "✓ BFF: http://localhost:$BFF_PORT (PID $(pid_on_port "$BFF_PORT" || echo '?'))"
    curl -fsS "http://localhost:$BFF_PORT/health" | head -1
  else
    r "✗ BFF: not running"
  fi
  if curl -fsS -I "http://localhost:$TESTER_PORT/api-tester.html" >/dev/null 2>&1; then
    g "✓ api-tester: http://localhost:$TESTER_PORT/api-tester.html (PID $(pid_on_port "$TESTER_PORT" || echo '?'))"
  else
    r "✗ api-tester: not running"
  fi
}

cmd_logs() {
  [[ -f "$BFF_LOG" ]] || { r "No log at $BFF_LOG (BFF never started in this session)"; exit 1; }
  exec tail -f "$BFF_LOG"
}

cmd_restart() { cmd_down; cmd_up; }

# ---- dispatch -------------------------------------------------------------
case "${1:-up}" in
  up|start)   cmd_up ;;
  down|stop)  cmd_down ;;
  status)     cmd_status ;;
  logs)       cmd_logs ;;
  restart)    cmd_restart ;;
  -h|--help|help)
    sed -n '2,12p' "$0" | sed 's/^# *//'
    ;;
  *) r "Unknown subcommand: $1"; sed -n '2,12p' "$0" | sed 's/^# *//'; exit 1 ;;
esac
