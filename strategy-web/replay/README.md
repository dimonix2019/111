# MOEX Bar Replay — Web

**TradingView lightweight-charts** в браузере.

## Запуск (Windows)

### Вариант A — окно (как раньше)

Из корня `MoexMvp`:

```bat
run-replay-web.bat
```

Закрытие окна / Ctrl+C останавливает watchdog и сервер.

### Вариант B — Windows-сервис (рекомендуется для Prod)

Админ PowerShell / bat (один раз):

```bat
scripts\install-moex-live-service.bat
```

Сервис **`MoexLiveWatchdog`**: автозапуск при старте Windows, поднимает `live_watchdog.py` + uvicorn `:8765`.

- Probe **каждые 60 с** (`MOEX_WATCHDOG_INTERVAL_SEC=60`)
- soft restart монитора при stale (~90 с без тика)
- hard restart процесса при мёртвом HTTP
- лог: `strategy-web/data/watchdog.log` (+ `watchdog-service.*.log`)
- снятие: `scripts\uninstall-moex-live-service.bat`
- после обновления кода агент/вы: `scripts\restart-moex-live-service.bat` (или `Restart-Service MoexLiveWatchdog`)

**Не** запускайте `run-replay-web.bat` параллельно с сервисом (один порт 8765).  
В параметрах питания ПК: сон **Никогда** на время торгов (keep-awake в Session 0 ограничен).

Общее:

- Слушает **`0.0.0.0:8765`** — Tailscale: `http://<100.x.x.x>:8765`
- **APK:** вкладка «Стол web» + push по `/api/live/status` (ордера только на web)

## Вкладки

| Вкладка | Назначение |
|---------|------------|
| **Торговля** | Главный стол: Z/спред, пороги, Авто, открытая сделка (PnL≈, риск), Закрыть |
| **История** | Закрытые сделки, глубина 1/3/7/30д |
| **Счёт** | Токен T‑Invest, Sandbox/Prod, монитор, редкий ручной Long/Short |
| **Replay** | Бэктест-симулятор (как раньше) |

Пороги Z / плечо / Авто задаются **только на «Торговля»** (без дублей).

**Монитор:** keep-awake пока поток жив. AUTO догоняет до 8 consecutive 15м-рёбер после лага/рестарта (parity APK); настоящие дыры в барах — `skip_gap` без сделок. Sync ISS ≤12с, heartbeat ~5 мин. Сигналы только TQBR **пн–пт 07:00–23:50 МСК**.

**Testing ≈ Prod:** Z на барах — **rolling 30д** (как Android). Пороги теста **не** связаны с Prod автоматически: кнопка **«из Торговли»** на вкладке Тестирование копирует пороги Live → только в UI теста. Опции **TP**, **Капитализация** и **Slip п.п.** (adverse спред на вход/выход, дефолт **0.12** по сделке Prod 20.07.2026) — в группе «Стратегия». Комиссия сима **0.04%/сторона** (тариф Премиум). После AUTO-сделки через ~45 мин сверяется edge с симом → лог `Parity OK/MISSING`.

## API

- `GET /api/health` — процесс жив
- `GET /api/health/live` — probe монитора (`monitor_alive`, `last_tick_age_sec`, `stale`)
- `POST /api/live/monitor/restart` — stop+start потока (watchdog soft recover)
- `GET /api/trade/desk?days=` — стол: бары + open MTM/risk + settings/monitor + parity
- `GET /api/markets?days=`, `POST /api/markets/refresh`
- `GET /api/portfolio?days=`, `POST /api/portfolio/params`, `POST /api/portfolio/close`
- `GET/POST /api/live/*` — счёт и монитор
- `GET /api/live/parity`, `POST /api/live/parity/check` — сверка AUTO vs Testing

Хранение: `strategy-web/data/live_trading.db`

## Replay UI

- Старт date picker, период 30д/3М/6М/Всё, пороги симуляции, CSV
- Play / scrub, таблица сделок симуляции
