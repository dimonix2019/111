# MOEX Bar Replay — Web

**TradingView lightweight-charts** в браузере (график работает надёжно, в отличие от JavaFX WebView).

## Запуск (Windows)

Из корня `MoexMvp`:

```bat
run-replay-web.bat
```

Откроется **http://127.0.0.1:8765**

Требования: **Python 3.10+**, `pip install -r requirements.txt` (один раз).

## Вручную

```bash
cd strategy-web
pip install -r requirements.txt
python replay/replay_app.py
```

## UI

- Z-свечи TradingView, маркеры 1A/1R, линия replay
- **Старт replay** — date picker, сохраняется в `localStorage` (`moexReplay.startDate`)
- Период окна: 30д / 3М / 6М / Всё
- Пороги Вх/Вых, выбор CSV (255д / 365д)
- ▶ Play, шаги, скорость 0.5×…10×, scrub
- Таблица сделок — **все столбцы Android «Тест страт.»**, переключатель столбцов (☰), горизонтальный скролл

## localStorage

| Ключ | Описание |
|------|----------|
| `moexReplay.startDate` | Дата старта replay |
| `moexReplay.entry` / `exit` | Пороги Z |
| `moexReplay.period` | Окно графика (дни) |
| `moexReplay.csv` | Выбранный CSV |
| `moexReplay.tradesScrollLeft` | Горизонтальный скролл таблицы |
| `moexReplay.tradeColumns` | Видимые столбцы (имена enum Android) |

## API

- `GET /api/bars?csv=m15_tatn_255d.csv&start=2025-06-01` — JSON 15м ряд
- `GET /api/health` — health check
- `GET /static/*` — JS/CSS/chart

Ответ `/api/bars`: `source` (`sqlite`/`csv`), `online`, `refreshed` (MOEX tail).

## Данные / offline

- Кэш SQLite: `strategy-web/data/replay_m15.db`
- При старте: seed из CSV, при online — догрузка хвоста через `m15_iss_loader.ensure_m15_data`
- Offline: бары из SQLite без MOEX

CSV: `strategy-web/data/m15_tatn_255d.csv` (whitelist в `replay_app.py`).
