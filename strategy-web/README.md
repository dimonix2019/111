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

### Мобильный веб (Tailscale)

Урезанный режим: только вкладка «Рынок» и свечной график Z-score 15м.

1. Запустите **`run_tester.bat`** (Vite слушает `0.0.0.0:5174`, не только localhost).
2. В конце скрипта будет строка **`[OK] Tailscale: http://100.x.x.x:5174/m`** — откройте её на телефоне (Tailscale включён).
3. Если «не удаётся получить доступ» — разрешите входящий TCP **5174** в брандмауэре Windows (PowerShell от администратора):

```powershell
New-NetFirewallRule -DisplayName "Z-Strategy UI 5174" -Direction Inbound -Protocol TCP -LocalPort 5174 -Action Allow
```

URL:

- `http://<tailscale-ip>:5174/m` — закладка для телефона
- `http://<tailscale-ip>:5174/?mobile=1` — альтернатива
- `http://127.0.0.1:5174/` — полный десктоп на этом ПК

**Режим «как приложение» (без адресной строки браузера):**

1. Откройте `http://<tailscale-ip>:5174/m` в браузере телефона.
2. **iPhone (Safari):** «Поделиться» → **«На экран Домой»** → «Добавить».
3. **Android (Chrome):** меню ⋮ → **«Установить приложение»** или **«Добавить на главный экран»**.

Иконка запускает сразу мобильный режим (`standalone`, старт `/m`). Для iOS важен `viewport-fit=cover` — учтены «чёлки» экрана.

Полный десктопный UI — по корню `/` без параметров.

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
