# MOEX MVP Web + Tailscale

Это **web-версия Android-приложения MOEX MVP**, не `strategy-web`.

## Запуск на сервере

```bash
cd moex-web
chmod +x deploy/run.sh
./deploy/run.sh
```

Порт по умолчанию: **8080**.

## С телефона / ПК

1. На сервере запущен `./deploy/run.sh` (не закрывать терминал или использовать systemd).
2. На телефоне Tailscale **включён** (VPN active), тот же аккаунт.
3. `tailscale ip -4` на сервере → `http://100.x.y.z:8080` (**http**, не https).

### «Не удаётся получить доступ к сайту»

На сервере:

```bash
cd moex-web && ./deploy/check-access.sh
curl -s http://127.0.0.1:8080/api/health
```

| Причина | Решение |
|--------|---------|
| `run.sh` не запущен | `cd moex-web && ./deploy/run.sh` |
| Tailscale выкл на телефоне | Включить VPN в приложении |
| Неверный URL | Только `100.x.x.x` из `tailscale ip -4`, не IP роутера |
| ufw | `sudo ufw allow 8080/tcp` |
| Путаница с strategy-web | MOEX MVP = порт **8080**, не 8501 |

HTTPS (опционально, удобно с телефона):

```bash
sudo tailscale serve --bg --https=443 http://127.0.0.1:8080
# затем: tailscale serve status — откройте https-URL из вывода
```

## Первый запуск

При старте подтянется `strategy-web/data/m15_tatn_255d.csv` или загрузка с MOEX ISS (~минута).

Кнопка **«Обновить MOEX 15м»** на вкладке «Рынок» — принудительное обновление.

## Безопасность

Только Tailscale / VPN. Не пробрасывайте 8080 в интернет без auth.
