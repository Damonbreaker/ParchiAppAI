#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

export OCR_PIPELINE=legacy
export PARCHI_CATALOGUE_DB="${PARCHI_CATALOGUE_DB:-$PWD/catalogue_output/open/national_retail_catalogue_open.sqlite}"
export PARCHI_AI_CATALOGUE="${PARCHI_AI_CATALOGUE:-1}"
export PARCHI_AI_DEVICE="${PARCHI_AI_DEVICE:-auto}"
export PARCHI_NUMERIC_OCR="${PARCHI_NUMERIC_OCR:-1}"
export PARCHI_NUMERIC_OCR_DEVICE="${PARCHI_NUMERIC_OCR_DEVICE:-auto}"
export GRADIO_ANALYTICS_ENABLED="${GRADIO_ANALYTICS_ENABLED:-False}"

HOST="${HOST:-0.0.0.0}"
PORT="${PORT:-7866}"
PYTHON="${PYTHON:-python}"

# Dev UI on :7866. For the Spring app use ./run_ocr.sh (scan_server on :7870).
exec "$PYTHON" -m uvicorn app:app --host "$HOST" --port "$PORT"

