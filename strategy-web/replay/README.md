# MOEX Bar Replay — Web

**TradingView lightweight-charts** в браузере.

## Запуск (Windows)

Из корня `MoexMvp`:

```bat
run-replay-web.bat
```

Один bat как раньше: поднимает сервер, открывает браузер, плюс внешний watchdog.

- Слушает **`0.0.0.0:8765`** (`MOEX_REPLAY_HOST`) — доступ с телефона по Tailscale: `http://<100.x.x.x>:8765` (IP ПК в Tailscale).
- `GET /api/health/live` каждые ~60 с
- **soft:** `POST /api/live/monitor/restart` если поток мёртв / `last_tick` старше 180 с
- **hard:** kill `:8765` + рестарт процесса (браузер повторно не открывает)
- лог: `strategy-web/data/watchdog.log` (+ toast на hard restart)

Закрытие окна / Ctrl+C останавливает watchdog и сервер.

**APK:** вкладка «Стол web» — WebView на этот URL + push по опросу `/api/live/status` (ордера только на web).

## Вкладки

| Вкладка | Назначение |
|---------|------------|
| **Торговля** | Главный стол: Z/спред, пороги, Авто, открытая сделка (PnL≈, риск), Закрыть |
| **История** | Закрытые сделки, глубина 1/3/7/30д |
| **Счёт** | Токен T‑Invest, Sandbox/Prod, монитор, редкий ручной Long/Short |
| **Replay** | Бэктест-симулятор (как раньше) |

Пороги Z / плечо / Авто задаются **только на «Торговля»** (без дублей).

**Монитор:** пока запущен — Windows keep-awake (ПК не уходит в сон). Торгует только следующее consecutive-ребро; пропуски **не** догоняет AUTO-реплеем (якорь вперёд + warn). Sync ISS ≤12с, heartbeat ~5 мин. Сигналы только в сессии TQBR **пн–пт 07:00–23:50 МСК** (бары 06:30/06:45 до открытия игнорируются).

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
