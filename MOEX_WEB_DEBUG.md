# MOEX MVP — web для отладки (не strategy-web)

| | |
|---|---|
| **Нужно** | Текущее APK-приложение в браузере |
| **Не то** | `strategy-web/` — отдельный Z-Strategy Backtester |

## Запуск

```bash
cd moex-web
./deploy/run.sh
```

`http://<tailscale-ip>:8080`

Документация: [moex-web/README.md](moex-web/README.md) · [moex-web/deploy/TAILSCALE.md](moex-web/deploy/TAILSCALE.md)

## Сейчас в web

- **Рынок**: Z-свечи 1D / 1W / 1M / 3M (как в APK)
- **Тест страт.**: симуляция 255д, пороги, плечо
- **Портфель / Журнал / Песочница**: заглушки → позже или APK

## APK

https://github.com/dimonix2019/111/releases/download/moexmvp-debug-latest/moexmvp-debug.apk
