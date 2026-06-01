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

1. Tailscale на сервере и на устройстве (одна tailnet).
2. `tailscale ip -4` на сервере → `http://100.x.y.z:8080`

HTTPS (опционально):

```bash
sudo tailscale serve --bg --https=443 http://127.0.0.1:8080
```

## Первый запуск

При старте подтянется `strategy-web/data/m15_tatn_255d.csv` или загрузка с MOEX ISS (~минута).

Кнопка **«Обновить MOEX 15м»** на вкладке «Рынок» — принудительное обновление.

## Безопасность

Только Tailscale / VPN. Не пробрасывайте 8080 в интернет без auth.
