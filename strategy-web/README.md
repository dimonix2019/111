# strategy-web — локальный тест Z-стратегии

## Запуск (Windows)

```powershell
cd "C:\Users\Lenovo\Documents\Тестер стратегий\strategy-web"
..\.venv\Scripts\python.exe -m streamlit run app.py
```

Или из родительской папки с активированным `.venv`:

```powershell
streamlit run app.py
```

Браузер: http://localhost:8501

## CSV

Минимум для `zsim.py`: `timestamp`, `z_score`, `spread_percent`.

| Файл | Откуда |
|------|--------|
| `data/sample.csv` | синтетика, в репозитории |
| `data/m15_tatn_255d.csv` | **нет на диске по умолчанию** — выгрузка с MOEX |

### История 15м TATN/TATNP с MOEX ISS (как в Android)

**Важно:** в CSV должно быть **~13 000+** строк. Если **~1000** — загрузка оборвалась (часто кнопка Streamlit), не «биржа закрыта»: ISS отдаёт историю и ночью/в воскресенье.

**Надёжный способ (PowerShell, 2–5 мин):**

```powershell
cd "C:\Users\Lenovo\Documents\Тестер стратегий\strategy-web"
..\.venv\Scripts\python.exe scripts\export_m15_iss.py --days 255 --out data\m15_tatn_255d.csv
```

Или: `.\scripts\download_m15.ps1`

В Streamlit: **«Скачать готовый CSV с GitHub»** (если репозиторий открыт) или обновлённая **«Дозагрузить с MOEX»** (окна по 35 дней + проверка ≥8000 баров).

**Другие источники:**

| Источник | Как |
|----------|-----|
| GitHub | ветка `cursor/strategy-web-moex-parity-4bf6`, файл `strategy-web/data/m15_tatn_255d.csv` |
| Android | после «Тест страт.» на телефоне — кэш `portfolio_m15_cache.db` (Room), экспорт сложнее; проще ISS или GitHub |
| Локальный git | `git pull` и скопировать `strategy-web/data/m15_tatn_255d.csv` |

В Streamlit: sidebar **«Дозагрузить с MOEX (255 дн.)»** — без ручного запуска скрипта.

Логика как в `moexmvp`: ISS 10м свечи TQBR → 15м → `spread = (TATN/TATNP - 1)*100` → Z по всему ряду.

### Сравнение с Android «Тест страт.»

| Параметр | Android | Web (ожидание) |
|----------|---------|----------------|
| История | ~255д, ~13 800 баров 15м | то же в `m15_tatn_255d.csv` |
| Пороги | 0.8 / 0.7 | 0.8 / 0.7 |
| 100k, x7, комиссия 0.04% | ~140 сделок, **~+250k ₽** PnL | то же |

**Если на вебе 1 сделка и минус** — почти всегда короткий/устаревший CSV (несколько дней). Z на графике уходит к ±8, потому что пересчитывается по короткому окну, а не по полному ряду как в Android. Дозагрузите 255д с MOEX и перезапустите симуляцию.

### Кэш в Android (если уже гоняли APK)

На телефоне/эмуляторе: `portfolio_m15_cache.db` (Room), **не** в папке `strategy-web` на ПК.  
Скопировать с устройства сложнее, проще `export_m15_iss.py`.

## Continue

Diff в чате **не сохраняется сам** — нажмите **Apply** или скопируйте файлы из репозитория `strategy-web/`.
