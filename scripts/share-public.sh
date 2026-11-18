#!/usr/bin/env bash
# Publish ParchiAppAI to a public HTTPS URL (Gradio tunnel).
# Requires local stack: gateway :5174
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT/.run-logs"
PY="${SHARE_PYTHON:-/media/user/New/Jatin/phd/obj2/train/venv/bin/python}"
mkdir -p "$LOG"

unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY || true
if ! curl -fsS --max-time 3 http://127.0.0.1:5174/api/health >/dev/null; then
  echo "Gateway not up. Run ./scripts/run.sh first." >&2
  exit 1
fi

export http_proxy="${CORP_PROXY:-http://10.10.78.61:3128}"
export https_proxy="$http_proxy" HTTP_PROXY="$http_proxy" HTTPS_PROXY="$http_proxy"
export no_proxy=localhost,127.0.0.1

pkill -f "$ROOT/scripts/start_public_tunnel.py" 2>/dev/null || true
rm -f "$LOG/public-url.txt"
nohup "$PY" -u "$ROOT/scripts/start_public_tunnel.py" >"$LOG/tunnel.log" 2>&1 &
echo $! >"$LOG/tunnel.pid"
for _ in $(seq 1 90); do
  [[ -s "$LOG/public-url.txt" ]] && break
  sleep 1
done
URL="$(cat "$LOG/public-url.txt" 2>/dev/null | tr -d '\n')"
if [[ -z "$URL" ]]; then
  echo "Tunnel failed. See $LOG/tunnel.log" >&2
  tail -40 "$LOG/tunnel.log" >&2 || true
  exit 1
fi
echo ""
echo "============================================="
echo "  Public link (works outside IITD):"
echo "  $URL"
echo "============================================="
echo "  Customer: 9000000001 / test123"
echo "  Store: 9000000011 / test123"
echo "  Keep this machine online. Stop: ./scripts/stop.sh"
echo "============================================="
