#!/usr/bin/env bash
# Диагностика: почему с телефона «не удаётся получить доступ к сайту»
set -uo pipefail

PORT="${MOEX_WEB_PORT:-8080}"
HOST_BIND="${MOEX_WEB_HOST:-0.0.0.0}"

echo "=== MOEX MVP Web — проверка доступа ==="
echo

echo "1) Tailscale на этом сервере:"
if command -v tailscale >/dev/null 2>&1; then
  if tailscale status >/dev/null 2>&1; then
    TS_IP="$(tailscale ip -4 2>/dev/null | head -1 || true)"
    TS_NAME="$(tailscale status --self 2>/dev/null | head -1 || true)"
    echo "   OK  IP: ${TS_IP:-?}"
    echo "   URL для телефона (тот же tailnet, Tailscale ВКЛ):"
    echo "       http://${TS_IP}:${PORT}"
    if [[ -n "$TS_IP" ]]; then
      echo "   Проверка с сервера:"
      if curl -fsS -m 3 "http://127.0.0.1:${PORT}/api/health" >/dev/null 2>&1; then
        echo "       curl localhost:${PORT}/api/health → OK"
      else
        echo "       curl localhost:${PORT}/api/health → НЕТ ОТВЕТА (сервис не запущен?)"
        echo "       Запустите: cd moex-web && ./deploy/run.sh"
      fi
    fi
  else
    echo "   FAIL  tailscale не подключён. Выполните: sudo tailscale up"
  fi
else
  echo "   WARN  tailscale не установлен"
fi
echo

echo "2) Слушает ли порт ${PORT} (должен быть 0.0.0.0, не только 127.0.0.1):"
if command -v ss >/dev/null 2>&1; then
  ss -tlnp 2>/dev/null | grep -E ":${PORT}\\s" || echo "   FAIL  порт ${PORT} не слушается"
elif command -v netstat >/dev/null 2>&1; then
  netstat -tlnp 2>/dev/null | grep -E ":${PORT}\\s" || echo "   FAIL  порт ${PORT} не слушается"
else
  echo "   (установите ss или netstat)"
fi
echo

echo "3) Фаервол (ufw):"
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
  if ufw status | grep -q "${PORT}"; then
    echo "   OK  правило для ${PORT} есть"
  else
    echo "   WARN  ufw активен, порт ${PORT} может быть закрыт:"
    echo "         sudo ufw allow ${PORT}/tcp"
  fi
else
  echo "   ufw не активен или не установлен"
fi
echo

echo "4) На ТЕЛЕФОНЕ обязательно:"
echo "   • Tailscale включён (VPN active), тот же аккаунт что на сервере"
echo "   • Адрес именно http://100.x.x.x:${PORT}  (не https, не публичный IP роутера)"
echo "   • Не cellular без Tailscale — иначе 100.x недоступен"
echo

echo "5) Альтернатива — Tailscale Serve (HTTPS, без открытия порта):"
echo "   sudo tailscale serve --bg --https=443 http://127.0.0.1:${PORT}"
echo "   затем откройте https://<имя-машины> из вывода tailscale serve status"
echo
