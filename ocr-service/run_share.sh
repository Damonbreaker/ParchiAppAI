#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

export PARCHI_CATALOGUE_DB="${PARCHI_CATALOGUE_DB:-$PWD/catalogue_output/open/national_retail_catalogue_open.sqlite}"
export PARCHI_AI_CATALOGUE="${PARCHI_AI_CATALOGUE:-1}"
export PARCHI_AI_DEVICE="${PARCHI_AI_DEVICE:-auto}"
export PARCHI_NUMERIC_OCR="${PARCHI_NUMERIC_OCR:-1}"
export PARCHI_NUMERIC_OCR_DEVICE="${PARCHI_NUMERIC_OCR_DEVICE:-auto}"
export PARCHI_TROCR="${PARCHI_TROCR:-0}"
export GRADIO_ANALYTICS_ENABLED="${GRADIO_ANALYTICS_ENABLED:-False}"

PYTHON="${PYTHON:-python}"
exec "$PYTHON" -u share_app.py

