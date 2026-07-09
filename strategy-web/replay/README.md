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
- Период окна: 30д / 3М / 6М / Всё
- Пороги Вх/Вых, выбор CSV (255д / 365д)
- ▶ Play, шаги, скорость 0.5×…10×, scrub
- Таблица сделок справа

## API

- `GET /api/bars?csv=m15_tatn_255d.csv` — JSON 15м ряд
- `GET /static/*` — JS/CSS/chart

## Данные

`strategy-web/data/m15_tatn_255d.csv` (и др. из whitelist в `replay_app.py`).
