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

echo ""
echo "=== MOEX MVP Web ==="
echo "Локально на сервере:  http://127.0.0.1:${PORT}"
if command -v tailscale >/dev/null 2>&1; then
  TS_IP="$(tailscale ip -4 2>/dev/null | head -1 || true)"
  if [[ -n "$TS_IP" ]]; then
    echo "С телефона (Tailscale ВКЛ):  http://${TS_IP}:${PORT}"
  else
    echo "Tailscale: IP не получен — выполните: sudo tailscale up"
  fi
else
  echo "Установите Tailscale на сервере или откройте порт ${PORT} в LAN."
fi
echo "Диагностика:  ./deploy/check-access.sh"
echo ""

cd "$ROOT"
exec uvicorn server.main:app --host "$HOST" --port "$PORT"
