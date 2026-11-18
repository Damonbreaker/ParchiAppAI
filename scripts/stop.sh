#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT/.run-logs"
for name in frontend backend ocr; do
  if [[ -f "$LOG/$name.pid" ]]; then
    pid="$(cat "$LOG/$name.pid")"
    if kill -0 "$pid" 2>/dev/null; then
      echo "Stopping $name ($pid)"
      kill "$pid" 2>/dev/null || true
      pkill -P "$pid" 2>/dev/null || true
    fi
    rm -f "$LOG/$name.pid"
  fi
done
pkill -f "$ROOT/scripts/gateway.py" 2>/dev/null || true
echo "Stopped (Postgres left running)."
