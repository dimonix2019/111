# MOEX Bar Replay — Web

**TradingView lightweight-charts** в браузере.

## Запуск (Windows)

Из корня `MoexMvp`:

```bat
run-replay-web.bat
```

Откроется **http://127.0.0.1:8765**

## Вкладки

| Вкладка | Назначение |
|---------|------------|
| **Торговля** | Главный стол: Z/спред, пороги, Авто, открытая сделка (PnL≈, риск), Закрыть |
| **История** | Закрытые сделки, глубина 1/3/7/30д |
| **Счёт** | Токен T‑Invest, Sandbox/Prod, монитор, редкий ручной Long/Short |
| **Replay** | Бэктест-симулятор (как раньше) |

Пороги Z / плечо / Авто задаются **только на «Торговля»** (без дублей).

## API

- `GET /api/trade/desk?days=` — стол: бары + open MTM/risk + settings/monitor
- `GET /api/markets?days=`, `POST /api/markets/refresh`
- `GET /api/portfolio?days=`, `POST /api/portfolio/params`, `POST /api/portfolio/close`
- `GET/POST /api/live/*` — счёт и монитор

Хранение: `strategy-web/data/live_trading.db`

## Replay UI

- Старт date picker, период 30д/3М/6М/Всё, пороги симуляции, CSV
- Play / scrub, таблица сделок симуляции
