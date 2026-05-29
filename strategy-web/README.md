# Z-Strategy Backtester (strategy-web)

Инструмент для тестирования Z-score стратегии на спреде TATN/TATNP.

## Для пользователя (только git pull)

После `git checkout cursor/strategy-web-moex-parity-4bf6` и `git pull` в коде уже:

- **Rolling Z 30д** — основной режим теста (без look-ahead, как Android PR #26)
- **Global Z 255д** — legacy для сравнения со старым APK
- Автозагрузка CSV 255 дней при первом открытии Streamlit
- Таблица **«Сравнение с Global»** на экране rolling

Запуск Streamlit **опционален** (для разработчика):

```bash
cd strategy-web
pip install -r requirements.txt
streamlit run app_streamlit.py
```

Основной UI также доступен через `run_tester.bat` (Vite + FastAPI) — там Z пересчитывается с **rolling 30д** по умолчанию при загрузке CSV.

## Два режима Z

| Режим | Описание |
|--------|-----------|
| **Rolling 30д** | μ и σ только за последние 30 календарных дней до бара (Europe/Moscow), min 48 баров прогрева. Честный бэктест. |
| **Global 255д** | μ/σ на весь CSV — look-ahead; Z на хвосте часто ≈0 при живом спреде; ~140 сделок только в этом режиме. |

## Технические детали

- `zsim.py`: `apply_z_scores_rolling`, `z_diagnostics`, `compare_z_modes`, `load_bars_from_csv(..., z_mode=)`
- `tests/test_z_rolling.py` — pytest (causal, rolling≠global, warmup)
- Данные: `m15_iss_loader.py`, `data/m15_tatn_255d.csv`

```bash
pytest tests/test_z_rolling.py -q
```
