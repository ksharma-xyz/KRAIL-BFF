#!/usr/bin/env bash
# Start the KRAIL Dispatch API tester (docs/tools/api-tester.html).
#
# One command, three things:
#   1. static server for docs/tools on :8000 (skipped if already running)
#   2. opens the tester in your browser
#   3. BFF on :8080 with the NSW passthrough + metrics enabled (foreground;
#      Ctrl-C stops it — the static server keeps running, it's harmless)
#
# Requires local.properties with nsw.apiKey (see local.properties.template).

set -euo pipefail
cd "$(dirname "$0")/.."

if ! lsof -tiTCP:8000 -sTCP:LISTEN >/dev/null 2>&1; then
  (cd docs/tools && python3 -m http.server 8000 >/dev/null 2>&1 &)
  echo "▶ static server started on http://localhost:8000"
else
  echo "▶ :8000 already serving — reusing it"
fi

if lsof -tiTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "▶ BFF already running on :8080 — reusing it. Opening tester."
  open "http://localhost:8000/api-tester.html"
  exit 0
fi

open "http://localhost:8000/api-tester.html"
echo "▶ starting BFF on :8080 (Ctrl-C to stop)…"
BFF_DEV_PASSTHROUGH=true BFF_METRICS_ENABLED=true exec ./gradlew :server:run
