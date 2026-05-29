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
..\.venv\Scripts\python.exe download_m15.py
```

Или двойной клик **`download_m15.bat`**.

Нужны файлы в папке `strategy-web`: **`download_m15.py`** и **`m15_iss_loader.py`** (из git / PR #24). Папка `scripts\` не обязательна.

В Streamlit: **«Скачать готовый CSV с GitHub»** (если репозиторий открыт) или обновлённая **«Дозагрузить с MOEX»** (окна по 35 дней + проверка ≥8000 баров).

**Другие источники:**

| Источник | Как |
|----------|-----|
| GitHub | ветка `cursor/strategy-web-moex-parity-4bf6`, файл `strategy-web/data/m15_tatn_255d.csv` |
| Android | после «Тест страт.» на телефоне — кэш `portfolio_m15_cache.db` (Room), экспорт сложнее; проще ISS или GitHub |
| Локальный git | `git pull` и скопировать `strategy-web/data/m15_tatn_255d.csv` |

В Streamlit: sidebar **«Дозагрузить с MOEX (255 дн.)»** — без ручного запуска скрипта.

Логика как в `moexmvp`: ISS 10м свечи TQBR → 15м → `spread = (TATN/TATNP - 1)*100` → Z-score.

### Режимы Z-score (sidebar)

| Режим | Описание |
|-------|----------|
| **Rolling 30д** (по умолчанию) | μ и σ только за последние **30 календарных дней** до бара — **без look-ahead**, как `applyZScoresRolling` в Android (PR #26). Z двигается со спрэдом, сделок обычно **гораздо больше**. |
| **Global 255д** | μ и σ по **всему** CSV — legacy (старый APK / `apply_z_scores`). На хвосте ряда Z часто **прижат к 0** → мало сделок при живом спрэде. |

Кнопка **«Сравнить Global vs Rolling 30д»** — две симуляции на одном CSV (сделки, PnL, max DD, \|Z\|≥0.8).

### Сравнение с Android «Тест страт.»

| Параметр | Android (legacy) | Web Rolling 30д |
|----------|------------------|-----------------|
| История | ~255д, ~13 800 баров 15м | то же в `m15_tatn_255d.csv` |
| Z | global 255д в APK | rolling 30д в тестере (честнее) |
| Пороги | 0.8 / 0.7 | 0.8 / 0.7 |
| 100k, x7, 0.04% | ~140 сделок при **global** Z | с rolling — **сотни** сделок на полном ряде (проверьте кнопкой сравнения) |

**Если сделок 0–1 при Global** — это ожидаемо на полном CSV: переключите **Rolling 30д** или нажмите «Сравнить режимы».

**Если сделок мало при Rolling** — проверьте CSV: нужно **≥8000** баров (`download_m15.py`).

### Тесты (pytest)

```powershell
cd strategy-web
..\.venv\Scripts\python.exe -m pytest tests/ -q
```

### Кэш в Android (если уже гоняли APK)

На телефоне/эмуляторе: `portfolio_m15_cache.db` (Room), **не** в папке `strategy-web` на ПК.  
Скопировать с устройства сложнее, проще `export_m15_iss.py`.

## Continue

Diff в чате **не сохраняется сам** — нажмите **Apply** или скопируйте файлы из репозитория `strategy-web/`.
