#!/usr/bin/env bash
# Start ParchiAppAI: Postgres + Gemini Flash-Lite OCR + Spring API + UI gateway.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT/.run-logs"
mkdir -p "$LOG"

export PATH="/home/user/anaconda3/envs/ani/bin:/home/user/.local/bin:${PATH:-}"
export JAVA_HOME="${JAVA_HOME:-/home/user/Downloads/elasticsearch-8.17.3-linux-x86_64/elasticsearch-8.17.3/jdk}"
export PATH="$JAVA_HOME/bin:$PATH"
PY="${SHARE_PYTHON:-/media/user/New/Jatin/phd/obj2/train/venv/bin/python}"
PARCHI_READER="${PARCHI_READER:-local-ocr}"
OCR_PORT="${OCR_PORT:-7871}"
API_PORT="${API_PORT:-8081}"
UI_PORT="${UI_PORT:-5174}"

local_curl() {
  env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY \
    curl -fsS --max-time 3 "$@"
}

echo "==> Postgres (shared) + DB kirana_ai"
if ! pg_isready -h 127.0.0.1 -p 5432 -q 2>/dev/null; then
  echo "Postgres is not running on :5432  start it (or run the original ParchiApp scripts/run.sh once)." >&2
  exit 1
fi
createdb -h 127.0.0.1 -p 5432 -U kirana kirana_ai 2>/dev/null || true
psql -h 127.0.0.1 -p 5432 -U kirana -d kirana_ai -c "ALTER USER kirana WITH PASSWORD 'kirana';" >/dev/null 2>&1 || true

echo "==> AI OCR service (:$OCR_PORT) Gemini Flash-Lite + base catalogue"
if local_curl "http://127.0.0.1:${OCR_PORT}/health" >/dev/null 2>&1; then
  echo "    already up"
else
  (
    cd "$ROOT/ocr-service"
    export OCR_PORT
    export PARCHI_CATALOGUE_DB="$ROOT/ocr-service/catalogue_output/open/national_retail_catalogue_open.sqlite"
    export PARCHI_AI_CATALOGUE=0
    export PARCHI_AI_OCR_MODEL="${PARCHI_AI_OCR_MODEL:-gemini-flash-lite-latest}"
    # Outbound Gemini needs the campus proxy; local bind does not.
    export http_proxy="${http_proxy:-http://10.10.78.61:3128}"
    export https_proxy="${https_proxy:-http://10.10.78.61:3128}"
    export HTTP_PROXY="$http_proxy" HTTPS_PROXY="$https_proxy"
    export no_proxy="localhost,127.0.0.1"
    exec "$PY" -u -m uvicorn scan_server:app --host 127.0.0.1 --port "$OCR_PORT"
  ) >"$LOG/ocr.log" 2>&1 &
  echo $! >"$LOG/ocr.pid"
  for _ in $(seq 1 40); do
    local_curl "http://127.0.0.1:${OCR_PORT}/health" >/dev/null 2>&1 && break
    sleep 1
  done
fi
local_curl "http://127.0.0.1:${OCR_PORT}/health" || { echo "OCR failed"; tail -40 "$LOG/ocr.log"; exit 1; }

echo "==> Backend (:$API_PORT) reader=$PARCHI_READER"
if local_curl "http://127.0.0.1:${API_PORT}/api/health" >/dev/null 2>&1; then
  echo "    already up"
else
  JAR=$(ls "$ROOT"/backend/target/kirana-backend-*.jar 2>/dev/null | head -1 || true)
  if [[ -z "${JAR:-}" ]]; then
    echo "    building jar..."
    (
      cd "$ROOT/backend"
      export http_proxy="${http_proxy:-http://10.10.78.61:3128}"
      export https_proxy="${https_proxy:-http://10.10.78.61:3128}"
      ./mvnw -s .mvn/settings-proxy.xml -DskipTests package -q
    )
    JAR=$(ls "$ROOT"/backend/target/kirana-backend-*.jar | head -1)
  fi
  (
    cd "$ROOT/backend"
    export http_proxy= https_proxy=
    exec java -jar "$JAR" \
      --server.port="$API_PORT" \
      --app.parchi.reader="$PARCHI_READER" \
      --app.parchi.ocr-base-url="http://127.0.0.1:${OCR_PORT}" \
      --spring.datasource.url="jdbc:postgresql://localhost:5432/kirana_ai"
  ) >"$LOG/backend.log" 2>&1 &
  echo $! >"$LOG/backend.pid"
  for _ in $(seq 1 60); do
    local_curl "http://127.0.0.1:${API_PORT}/api/health" >/dev/null 2>&1 && break
    sleep 2
  done
fi
local_curl "http://127.0.0.1:${API_PORT}/api/health" || { echo "Backend failed"; tail -40 "$LOG/backend.log"; exit 1; }

echo "==> Frontend gateway (:$UI_PORT)"
if local_curl "http://127.0.0.1:${UI_PORT}/" >/dev/null 2>&1; then
  echo "    already up"
else
  if [[ ! -f "$ROOT/frontend/dist/index.html" ]]; then
    echo "frontend/dist missing  building..."
    (
      cd "$ROOT/frontend"
      export PATH="/tmp/node-v22.14.0-linux-x64/bin:$PATH"
      VITE_DEMO=1 node node_modules/vite/bin/vite.js build
    )
  fi
  (
    cd "$ROOT"
    export http_proxy= https_proxy=
    export PARCHI_API="http://127.0.0.1:${API_PORT}"
    export PARCHI_UI_PORT="$UI_PORT"
    exec "$PY" scripts/gateway.py
  ) >"$LOG/frontend.log" 2>&1 &
  echo $! >"$LOG/frontend.pid"
  sleep 2
fi

echo ""
echo "ParchiAppAI up (Gemini Flash-Lite handwriting)"
echo "  UI:  http://127.0.0.1:${UI_PORT}"
echo "  API: http://127.0.0.1:${API_PORT}/api/health"
echo "  OCR: http://127.0.0.1:${OCR_PORT}/health"
echo "  Login: 9000000001 / test123  (shop 9000000011 / test123)"
echo "  Stop:  $ROOT/scripts/stop.sh"
