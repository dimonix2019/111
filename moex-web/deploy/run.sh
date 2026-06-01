#!/usr/bin/env bash
# MOEX MVP Web — FastAPI + статика, доступ по Tailscale.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

HOST="${MOEX_WEB_HOST:-0.0.0.0}"
PORT="${MOEX_WEB_PORT:-8080}"

if [[ ! -d .venv ]]; then
  python3 -m venv .venv 2>/dev/null || {
    echo "Установите python3-venv: sudo apt install python3-venv"
    exit 1
  }
fi
# shellcheck disable=SC1091
source .venv/bin/activate
pip install -q -U pip
pip install -q -r requirements.txt
pip install -q -r ../strategy-web/requirements.txt 2>/dev/null || true

export PYTHONPATH="${ROOT}:${ROOT}/../strategy-web:${PYTHONPATH:-}"

echo "MOEX MVP Web: http://${HOST}:${PORT}"
echo "Tailscale: http://<tailscale-ip>:${PORT}"
cd "$ROOT"
exec uvicorn server.main:app --host "$HOST" --port "$PORT"
