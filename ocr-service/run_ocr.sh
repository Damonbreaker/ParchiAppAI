#!/usr/bin/env bash
# Start legacy dual-OCR service for Parchi backend (default http://127.0.0.1:7870).
set -euo pipefail
cd "$(dirname "$0")"

export OCR_PIPELINE=legacy
export HF_HUB_OFFLINE="${HF_HUB_OFFLINE:-1}"
export TRANSFORMERS_OFFLINE="${TRANSFORMERS_OFFLINE:-1}"
export HF_HUB_DISABLE_TELEMETRY=1
export OCR_HOST="${OCR_HOST:-127.0.0.1}"
export OCR_PORT="${OCR_PORT:-7870}"

if [[ -x .venv/bin/python ]]; then
  PY=.venv/bin/python
elif command -v python3 >/dev/null 2>&1; then
  PY=python3
else
  echo "Python 3 required" >&2
  exit 1
fi

echo "Parchi OCR (legacy) on http://${OCR_HOST}:${OCR_PORT}"
exec "$PY" -m uvicorn scan_server:app --host "$OCR_HOST" --port "$OCR_PORT"
