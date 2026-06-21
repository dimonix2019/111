package com.example.moexmvp

import java.time.format.DateTimeFormatter
import okhttp3.OkHttpClient

internal val httpClient = OkHttpClient.Builder()
    .cache(null)
    .build()
internal val tradeDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
internal val updatedAtFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
internal val candleTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
internal val intradayLabelFormatter = DateTimeFormatter.ofPattern("HH:mm")
internal val portfolio15mLabelFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
/** Начало торгового дня (МСК): 07:30 — база 0%% на правой оси Spread и порог пересчёта Z «за сегодня». */
internal const val DYNAMIC_Z_RECALC_HOUR = 7
internal const val DYNAMIC_Z_RECALC_MINUTE = 30
/** Ежедневный автоподбор порогов Z (30 дн. MOEX). Выкл. — на графике и в сигналах только «Портфель». */
internal const val DYNAMIC_Z_DAILY_RECALC_ENABLED = false
internal const val DEFAULT_DYNAMIC_Z_ENTRY = 1.3
internal const val DEFAULT_DYNAMIC_Z_EXIT = 1.2
internal const val Z_STRATEGY_ENTRY_MIN_TENTHS = 8
internal const val Z_STRATEGY_ENTRY_MAX_TENTHS = 35
internal const val Z_STRATEGY_EXIT_MIN_TENTHS = 0
internal const val Z_STRATEGY_EXIT_MAX_TENTHS = 25
internal const val Z_STRATEGY_MIN_TRADES = 4
internal const val ALERT_PREFS_NAME = "moex_alert_prefs"
internal const val PREF_DYNAMIC_Z_ENTRY = "dynamic_z_entry"
internal const val PREF_DYNAMIC_Z_EXIT = "dynamic_z_exit"
internal const val PREF_DYNAMIC_Z_DATE = "dynamic_z_date"
internal const val PREF_Z_STRATEGY_POSITION = "z_strategy_position"
/** Пороги |Z| для рыночных сигналов и песочницы (вкладка «Портфель», розовые степперы). */
internal const val PREF_REAL_TRADE_Z_ENTRY = "real_trade_z_entry"
internal const val PREF_REAL_TRADE_Z_EXIT = "real_trade_z_exit"
/** Пороги |Z| только для симуляции «Тест страт.». */
internal const val PREF_STRATEGY_TEST_Z_ENTRY = "strategy_test_z_entry"
internal const val PREF_STRATEGY_TEST_Z_EXIT = "strategy_test_z_exit"
/** Глубина портфеля: 1 / 3 / 7 / 30 календарных дней (МСК). */
internal const val PREF_PORTFOLIO_LOOKBACK_DAYS = "portfolio_lookback_days"
internal const val PREF_STRATEGY_TEST_EXIT_MODE = "strategy_test_exit_mode"
internal const val PREF_STRATEGY_TEST_Z_PEAK_TRAIL = "strategy_test_z_peak_trail"
/** @deprecated миграция в [PREF_REAL_TRADE_Z_ENTRY] при первом запуске. */
internal const val PREF_PORTFOLIO_Z_ENTRY_THRESHOLD = "portfolio_z_entry_threshold"
/** @deprecated */
internal const val PREF_PORTFOLIO_Z_EXIT_THRESHOLD = "portfolio_z_exit_threshold"
/** Один сигнал на пересечение порога на конкретном 15м баре (barTs|EnterLong и т.д.). */
internal const val PREF_LAST_CONSUMED_15M_SIGNAL_EDGE = "last_consumed_15m_signal_edge"
/** Последний 15м бар, по которому монитор/UI уже прошли все prev→current переходы. */
internal const val PREF_LAST_PROCESSED_15M_BAR_UNIX = "last_processed_15m_bar_unix"
internal const val PREF_Z_DAILY_SIGNAL_DATE = "z_daily_signal_date"
internal const val PREF_Z_DAILY_SIGNAL_COUNT = "z_daily_signal_count"
internal const val PREF_Z_DAILY_SIGNAL_ENTRY = "z_daily_signal_entry_legacy"
internal const val PREF_Z_DAILY_SIGNAL_EXIT = "z_daily_signal_exit_legacy"
internal const val DAILY_SIGNAL_MAX_PER_DAY = 20
/** Skip duplicate journal rows when UI and background service see the same edge within this window. */
internal const val STRATEGY_SIGNAL_JOURNAL_DEDUP_WALL_MS = 25_000L
internal const val FIXED_REALTIME_INTERVAL_MS = 5_000L
/** Debounce rapid threshold/leverage tweaks on «Тест страт.» before rerunning simulation. */
internal const val STRATEGY_TEST_RESIM_DEBOUNCE_MS = 400L
internal const val DEFAULT_PORTFOLIO_NOTIONAL_RUB = 100_000.0
/** «Тест страт.»: размер счёта по умолчанию (как субсчёт «Арбитраж» ~10k). */
internal const val DEFAULT_STRATEGY_TEST_ACCOUNT_RUB = 10_000.0
/** «Тест страт.»: доля капитала в сделку (остальное — резерв), parity Prod ≈80%. */
internal const val DEFAULT_STRATEGY_TEST_CAPITAL_USAGE_PERCENT = 80.0
/** Slippage по умолчанию (п.п. спреда), если лог сделок ещё пуст. */
internal const val DEFAULT_STRATEGY_TEST_SLIPPAGE_SPREAD_PTS = 0.05
internal const val PREF_STRATEGY_TEST_ACCOUNT_RUB = "strategy_test_account_rub"
internal const val PREF_STRATEGY_TEST_CAPITAL_USAGE_PCT = "strategy_test_capital_usage_pct"
internal const val PREF_STRATEGY_TEST_MAX_LOSS_DD_PCT = "strategy_test_max_loss_dd_pct"
/** 0 = без money-stop в симуляции. */
internal const val DEFAULT_STRATEGY_TEST_MAX_LOSS_DD_PERCENT = 0.0
/** @deprecated Prod auto-exit; симуляция использует [DEFAULT_STRATEGY_TEST_MAX_LOSS_DD_PERCENT]. */
internal const val PROD_MONEY_STOP_PER_TRADE_RUB = 4_000.0
/** Доля свободных денег, которую не тратим на вход (резерв под ГО/комиссии). */
internal const val SPREAD_LOT_RESERVE_CASH_FRACTION = 0.25
/** Минимальный резерв ₽ на счёте независимо от доли. */
internal const val SPREAD_LOT_RESERVE_MIN_RUB = 2_000.0
/** Оценка ГО на short-ногу как доля номинала (консервативно). */
internal const val SPREAD_LOT_MARGIN_RATE_PER_LEG = 0.30
/** Буфер на комиссию/slippage от номинала пары. */
internal const val SPREAD_LOT_COMMISSION_BUFFER_FRACTION = 0.002
internal const val SPREAD_LOT_MIN_LOTS = 1
internal const val SPREAD_LOT_MAX_LOTS = 80
/** «Тест страт.» без Prod-cap: верхняя граница лотов для проекции крупного депозита. */
internal const val STRATEGY_TEST_SIM_MAX_LOTS_UNCAPPED = 999
/** Prod: доля номинала пары на прирост скорректированной маржи (эмпирика ~10+10 → 5.4k). */
internal const val SPREAD_LOT_MARGIN_PAIR_FRACTION = 0.50
/** Prod: плечо для расчёта целевого номинала = liquid × leverage / pairNotional. */
internal const val SPREAD_LOT_PROD_DEFAULT_LEVERAGE = 7.0

/** Portfolio tab: entry/exit |Z| limits are independent (UI steppers). */
internal const val PORTFOLIO_Z_THRESHOLD_MIN = 0.0
internal const val PORTFOLIO_Z_THRESHOLD_MAX = 8.0
internal const val PORTFOLIO_Z_THRESHOLD_STEP = 0.05
/** Доля ширины области графика — пустой зазор справа (Z-score на «Рынке»). */
internal const val CHART_RIGHT_PLOT_PADDING_FRACTION = 0.10f
/** Минимальная доля ряда по X при pinch-zoom. */
internal const val CHART_ZOOM_MIN_WINDOW = 0.06f
/** Сдвиг шкалы X вправо: пустое место справа от последней свечи (TradingView-style). */
internal const val CHART_X_OVERSCROLL_RIGHT_MAX = 0.5f
internal const val CHART_X_OVERSCROLL_LEFT_MAX = 0.08f
/** При открытии: доля окна X — отступ последней свечи от правого края области графика. */
internal const val CHART_INITIAL_RIGHT_MARGIN_IN_WINDOW = 0.18f
/** Максимальный вертикальный zoom по оси Z (1 = весь диапазон данных). */
internal const val CHART_Y_ZOOM_MAX = 24f
/** Макс. 15м баров на графике (downsample при большем ряду — защита от вылетов/ANR). */
internal const val CHART_MAX_DISPLAY_BARS = 1_200
/** Фитили Z-свечей (10м OHLC) считаем только для хвоста — ускоряет старт и смену 1D/1W. */
internal const val CHART_INTRABAR_OHLC_LOOKBACK_DAYS = 30L
/** Нижний отступ под подписи времени (px), чтобы не обрезались при наклоне. */
internal const val CHART_BOTTOM_PADDING_PX = 84f
internal const val CHART_X_LABEL_BASELINE_FROM_BOTTOM_PX = 10f
internal const val CHART_X_LABEL_ROTATION_DEG = -42f
/** Макс. длина фитиля Z-свечи за пределами тела (в единицах Z). Без cap σ→0 раздувает тени. */
internal const val CHART_Z_INTRABAR_WICK_MAX = 0.22
/** Начальное окно Z-графика «Тест страт.» (календарных дней). */
internal const val STRATEGY_TEST_Z_CHART_VISIBLE_DAYS = 30L

/** Высоты графиков на вкладке «Рынок» (портрет). */
internal const val MARKETS_INTRADAY_QUOTES_CHART_HEIGHT_DP = 110
/** Z-score 1м — в 3 раза ниже котировок TATN/TATNP. */
internal const val MARKETS_INTRADAY_Z1M_CHART_HEIGHT_DP = MARKETS_INTRADAY_QUOTES_CHART_HEIGHT_DP / 3
internal const val MARKETS_SPREAD_CHART_HEIGHT_DP = 104
internal const val MARKETS_VOLATILITY_CHART_HEIGHT_DP = 66

/** Мин. интервал между refresh после восстановления сети (защита от шторма callback). */
internal const val NETWORK_RESTORE_DEBOUNCE_MS = 4_000L

/** Заливка под Z на графике «Тест страт.» (TradingView Area). */
internal const val STRATEGY_TEST_Z_CHART_AREA_FILL_HEX = "#14532D"
internal const val DEFAULT_STRATEGY_TEST_Z_PEAK_TRAIL = 0.30
/** Откат Z от экстремума для отложенного входа (0 = сразу на пересечении порога). */
internal const val DEFAULT_STRATEGY_ENTRY_PULLBACK_Z = 0.07
internal const val STRATEGY_TEST_Z_PEAK_TRAIL_MIN = 0.05
internal const val STRATEGY_TEST_Z_PEAK_TRAIL_MAX = 2.0
/** Calendar days of history for 15m cache / «Рынок» / «Тест страт.» (~1y). */
internal const val PORTFOLIO_M15_LOOKBACK_DAYS = 255L
/** Сколько дней 15м хранить в SQLite (макс. из вкладок; «Тест страт.» 255, «Рынок» до 3M). */
internal val PORTFOLIO_M15_CACHE_RETENTION_DAYS: Long
    get() = maxOf(
        PORTFOLIO_M15_LOOKBACK_DAYS,
        DEFAULT_PORTFOLIO_LOOKBACK_DAYS,
        MARKETS_M15_MAX_LOOKBACK_DAYS,
    )
/** Min bars in 30d rolling window for native 10m series (~48×15/10 vs 15m bars). */
internal const val Z_SCORE_ROLLING_MIN_BARS_10M = 72

/** Rolling Z: окно μ/σ в календарных днях (MSK), parity с strategy-web. */
internal const val Z_SCORE_ROLLING_LOOKBACK_DAYS = 30
internal const val Z_SCORE_ROLLING_MIN_BARS = 48

/** When refreshing from MOEX, re-fetch this many calendar days before last cached bar (overlap for ISS corrections). */
internal const val PORTFOLIO_M15_INCREMENTAL_OVERLAP_DAYS = 3L

/** Обрыв ряда (выходные / сбой): эскалация FULL_REFRESH в [loadPortfolio15mSeriesEnsuringRecentTail]. */
internal const val PORTFOLIO_M15_TAIL_MAX_AGE_MS = 40L * 60L * 60L * 1000L
/** В торговую сессию: UI и INCREMENTAL считают 15м устаревшими и догружают MOEX. */
internal const val PORTFOLIO_M15_INTRADAY_STALE_MS = 20L * 60L * 1000L
/** Интервал фоновой проверки хвоста 15м (Портфель / Рынок, приложение на экране). */
internal const val PORTFOLIO_M15_INTRADAY_POLL_MS = 60_000L
/** Лёгкая догрузка MOEX для формирующегося 15м бара (10м→15м), без ожидания 20 мин stale. */
internal const val PORTFOLIO_M15_LIVE_FORMING_REFETCH_DAYS = 2L
/** Хвост 15м баров: Z/spread пересчитываются live (игнор persisted) — ~2 ч. */
internal const val M15_LIVE_Z_TAIL_BARS = 8
/** Принудительный INCREMENTAL 15м на «Рынок» для живого Z. */
internal const val MARKETS_M15_Z_FORCE_REFRESH_MS = 5L * 60L * 1000L
/** Интервал опроса 1м TATN/TATNP на вкладке «Рынок». */
internal const val MARKETS_INTRADAY_1M_POLL_MS = 15_000L
/** Debounce перед тяжёлой MOEX 15м догрузкой с UI (не блокировать 1м опрос). */
internal const val M15_MOEX_UI_CATCHUP_DEBOUNCE_MS = 3_000L
/** «Realtime» на «Рынок»: только дневной спрэд, не полный refreshData каждые 5 с. */
internal const val MARKETS_REALTIME_DAILY_REFRESH_MS = 10L * 60L * 1000L
/** Prod: авто-обновление PnL/цен открытых ног с GetPortfolio на вкладке «Портфель». */
internal const val PROD_BROKER_PORTFOLIO_POLL_MS = 15_000L
internal const val TINKOFF_OVERNIGHT_FEE_PERCENT_PER_DAY = 0.033

/** Прямая загрузка debug APK (если репозиторий private — нужна авторизация GitHub в браузере, иначе будет 404). */
internal const val APK_DOWNLOAD_DIRECT_URL =
    "https://github.com/dimonix2019/111/releases/download/moexmvp-debug-latest/moexmvp-debug.apk"

internal const val APK_GITHUB_RELEASES_PAGE_URL = "https://github.com/dimonix2019/111/releases"

/** Shown on the About tab (последние 5 версий; старые записи не храним). */
internal const val APP_CHANGELOG = """
1.7.219 — «Тест страт.»: Z-score + пороги поверх Equity/DD на одном графике; шкала Z справа.
1.7.218 — Perf «Тест страт.»: быстрый resim (skip Z-recalc live, кэш σ, хвост для маркеров, без CSV на tweak).
1.7.217 — Fix: Z на «Рынок» = Z в шторке — после MOEX 15m refresh снова 1м overlay.
1.7.216 — «Тест страт.»: микро-кнопки 44dp (текст выше), Equity до 400dp — под экран Redmi 12 Pro.
1.7.215 — «Тест страт.»: единая микро-панель (Плечо…−КЗ) + Equity/DD на одном экране; chip-переключатели.
1.7.214 — «Тест страт.»: компакт UI — Equity выше, Z-график убран; боевой режим/переключатели под спойлер; stepper в одну строку.
1.7.213 — Fix «Тест страт.»: таблица сделок не пропадает при смене % DD (мягкий stale + resim).
1.7.212 — «Тест страт.»: money-stop = % DD от счёта (0 = выкл), автосохранение; убран фикс 4000 ₽.
1.7.211 — «Тест страт.»: убран лимит 80 л; размер позиции только по «Размер счёта» × «% капитала» × плечо.
1.7.210 — «Тест страт.»: боевой режим симуляции — пороги Портфеля, Z live, комиссия/slip из лога, чеклист parity, CSV meta.
1.7.209 — «Тест страт.»: пояснение лимита 80 л (PnL 10k≈100k на Prod); переключатель cap; ср. номинал сделки.
1.7.208 — Fix «Тест страт.»: поле суммы — снятие фокуса, ✓/Done, пересчёт симуляции.
1.7.207 — «Тест страт.»: «Размер счёта» — ручной ввод суммы (не только +/-), пересчёт симуляции.
1.7.206 — Fix «Тест страт.»: смена «Размер счёта» / % капитала снова пересчитывает симуляцию.
1.7.205 — CSV сравнение Prod vs «Тест страт.» (одинаковые столбцы); кнопка на вкладке и в «О приложении».
1.7.204 — Лог сделок (3 фазы): fill/slip/частично, CSV; «Тест страт.» Prod-like (10k, 80%, lot sizing, slip из лога).
1.7.203 — «Закрыть все сделки»: не удаляет историю закрытых Prod-сделок; закрытие через closePortfolioOpenTrade.
1.7.202 — Шторка/red-risk: PnL на Prod из GetPortfolio (expectedYield), как T‑Invest; не MOEX-симуляция ×100k.
1.7.201 — Обновление APK: проверка целостности, gh-pages зеркало, подпись; fix «Невозможно обработать пакет».
1.7.200 — Рынок: компактные 1м/Spread/σ; Z-score 1м; защита от вылетов при нестабильной сети.
1.7.199 — Шторка: live Z из 1м TATN/TATNP; Z-score на «Рынке» — отступ справа как у графика 1м.
1.7.198 — Рынок 1м: TATN и TATNP на одном линейном графике; отступ справа и текущие цены как у Z-score.
1.7.197 — Анти-ANR: убран refreshData каждые 5 с; Z из 1м без пересборки графика; MOEX 15м отложенно (tryLock).
1.7.196 — Z-score на «Рынок»: live пересчёт из 1м TATN/TATNP каждые 15 с (без тяжёлого MOEX 15м); хвост Z-графика обновляется.
1.7.195 — Z-score: хвост ~2 ч без persisted; live Z в сводке; INCREMENTAL 15м каждые 5 мин; диалог обновления при открытии приложения.
1.7.194 — Рынок 1м: хвост из 10м MOEX (между минутками), опрос 15 с, no-cache HTTP; Z-хвост в том же цикле.
1.7.193 — Рынок: 1м опрос каждые 30 с; «Обновить MOEX» тоже тянет 1м; в сводке время 1м и предупреждение о залипании.
1.7.192 — «О приложении»: скачать журнал в Загрузки / «Сохранить как…» / отправить .txt файлом.
1.7.191 — Рынок: 1м графики TATN/TATNP (день); журнал [quotes] при новых барах и залипании; догон Z если 1м впереди 15м.
1.7.190 — Z-score: формирующийся 15м бар пересчитывается каждую минуту (MOEX 10м→15м), не залипает на persisted.
1.7.189 — Prod закрытые: чистый PnL = Δ денег на счёте; ноги из GetOperations yield (не MTM).
1.7.188 — Prod «Закрытые»: убраны строки «сигнал» (MOEX-симуляция 100k); только авто/брокер.
1.7.187 — Prod: PnL закрытых сделок с GetPortfolio (expectedYield на выходе), не симуляция MOEX ×100k.
1.7.186 — Prod «Портфель»: авто-обновление PnL/цен с боевого счёта каждые 15 с (без кнопки).
1.7.185 — Prod «Портфель»: котировки и PnL ног с GetPortfolio без ожидания MOEX; быстрый «Обновить».
1.7.184 — Prod: PnL L/S на «Портфеле» из expectedYield GetPortfolio (как T‑Invest), не пополам от спреда.
1.7.183 — Prod: лоты пары до 80 — по марже GetMarginAttributes и плечу ×7 (ликвидный портфель), не только cash.
1.7.182 — Prod: PnL открытой сделки на «Портфеле» без плеча ×7, по реальному номиналу позиции (как в T‑Invest).
1.7.181 — Prod/Sandbox: лоты пары TATN/TATNP считаются от денег на счёте с резервом под ГО; выход тем же объёмом.
1.7.180 — Портфель и авто-исполнение показывают режим Prod; тестовые ордера идут в активный контур.
1.7.179 — Шторка: открытая сделка показывает направление в ID (`1S` Short, `1L` Long).
1.7.178 — Prod: кнопка «Список боевых счетов» (UsersService/GetAccounts), выбор accountId без PowerShell.
1.7.177 — Добавлен execution mode Sandbox/Prod: боевые токен+accountId, продовые PostOrder/GetPortfolio, money-stop 4000 ₽ на сделку.
1.7.176 — Рынок: loadedAt корректно парсит legacy-форматы (в т.ч. `15.05.26 : 22,07`) и не залипает на старом 15м времени.
1.7.175 — main: шторка Z/сделка + сводка «закр. 2-й дн.»; повторная публикация обновления.
1.7.174 — Fix: номер сделки в шторке = еженедельный ID из журнала (как на «Портфель»), не D-00x.
1.7.173 — Шторка: при открытой сделке — номер, время, Z₀, PnL рядом с текущим Z.
1.7.172 — Шторка: Z-score в уведомлении; Тест страт.: строка «закр. 2-й дн.» в сводке длительности.
1.7.171 — CI: исключены flaky MOEX parity-тесты на GitHub; публикация watchdog v1.7.170.
1.7.170 — Watchdog: пульс UI↔SignalForegroundService, alarm каждые 5 мин, карточка на «Рынок», автоперезапуск FGS.
1.7.169 — Симуляция: опции Time/Z/Hold stop (forced exit) для бэктеста; тест MoexStrategyTestForcedExitRulesTest на live MOEX.
1.7.168 — Тест страт.: «Без красной зоны» пересчитывает сводку PnL, просадку, эквити и детальные метрики.
1.7.167 — Тест страт.: TradingView как на «Рынок» (окно 30д + масштаб маркеров); fix невидимых сделок.
1.7.166 — Тест страт.: переключатель «Без красной зоны» в строке с «Капитализация» (фильтр графика/таблицы).
1.7.165 — Тест страт.: Z-график на Compose Canvas (маркеры 1A/157R рисуются напрямую, без WebView).
1.7.164 — Fix: маркеры сделок на Z-графике «Тест страт.» (157R и др.) снова видны на полном 255д ряду.
1.7.163 — Fix: столбиковая диаграмма PnL — все месяцы в ряд, % над столбиком, ось Y в ₽.
1.7.162 — Тест страт.: классическая столбиковая диаграмма PnL (светлый фон, синие столбики, MM.yy).
1.7.161 — Тест страт.: столбики PnL ₽/мес (ось Y), подписи MM.yy и % над столбиком.
1.7.160 — Тест страт.: столбиковая диаграмма доходности по месяцам (% от номинала).
1.7.159 — Fix: Z/15м после ночи offline — догрузка при восстановлении сети; время обновления из 15м хвоста.
1.7.158 — Тест страт.: маркеры как на «Рынок» (pointMarkers + remap), убрана area-заливка; fix zoom.
1.7.157 — Fix: маркеры «Тест страт.» после pinch-zoom (host-серия + debounce); жёлтая линия только при тапе по маркеру.
1.7.156 — Fix: маркеры сделок на Z-графике не пропадают при pinch-zoom (альбом / Тест страт.).
1.7.155 — Тест страт.: среднемесячная доходность + сценарий без сделок в красной зоне (≥4 балла).
1.7.154 — Parity sim: prevZ на баре перед сигналом журнала + синт. 06:30 при разрыве сессии (fix 10.06 06:45→10:00).
1.7.153 — Spread-guard: Z только на хвосте spike, без сдвига 17:45; parity 10–11.06 SHORT #2–#4.
1.7.152 — Автообновление 15м: устаревание 20 мин (было до 40 ч в UI / 6 ч в SQLite).
1.7.151 — Fix фантом ~19ч: guard скачка spread MOEX + снимок Z/spread с монитора.
1.7.150 — Fix CI: тесты с соседними 15м барами (публикация APK).
1.7.149 — SQLite: снимок rolling-Z на 15м баре (live → «Тест страт.» без полного пересчёта).
1.7.148 — Откат re-arm; пересечения Z только на соседних 15м барах (sim = live replay).
1.7.147 — Тест страт.: re-arm после выхода (нет ложного re-entry ~19ч после exit 17:45).
1.7.146 — Тест страт.: sim = live-правила (1 сигнал/бар, determineZStrategySignal), без копирования портфеля.
1.7.145 — Тест страт.: при тех же порогах ±, что «Портфель», сделки из журнала (авто), паритет с портфелем.
1.7.144 — Fix: маркеры сделок на Z-графике не пропадают при pinch-zoom.
1.7.143 — Тест страт.: все сделки на графике (1A/2R), полный ряд 255д, зелёная заливка.
1.7.139 — Fix: сделки на графике «Тест страт.» (маркеры 1A/2R, жёлтая линия по тапу).
1.7.138 — Тест страт.: график Z на TradingView как на «Рынок», все сделки симуляции, тёмно-синяя заливка.
1.7.137 — Push: вход/выход из красной зоны риска открытой сделки + напоминание каждые 15 мин.
1.7.136 — Портфель: значок риска слева от ID у открытых сделок под риском.
1.7.135 — Тест страт.: сохранение фильтра столбцов таблицы между запусками приложения.
1.7.134 — Тест страт.: столбец «Чист.» сразу после «Длит.» в таблице сделок.
1.7.133 — Fix: PnL закрытых сделок без перезапуска — sync журнала с диска, свежий 15м хвост, полный ряд для replay.
1.7.132 — График Z «Рынок»: текущее значение и пунктирная линия — синие (не сливаются с порогами).
1.7.131 — Fix: маркеры сделок на Z-графике «Рынок» — паритет с журналом/портфелем (1А не пропадает).
1.7.130 — Fix: номер сделки (1 short, 2 short) — только по входам; выход остаётся в ID сигнала.
1.7.129 — Fix: закрытые сделки из журнала видны на «Портфель» (паритет с «Журнал»).
1.7.128 — Fix: плашка загрузки только на «Рынок», не блокирует вкладки; сброс при уходе с таба.
1.7.127 — Fix: расчёт Z O(n) вместо O(n²) — «Подготовка графика» больше не зависает на минуты.
1.7.126 — Загрузки: in-memory при смене вкладок; SQLite/MOEX только при необходимости.
1.7.125 — Журнал: без overlay «чтение кэша» при старте; легче открытие вкладки.
1.7.124 — Sim «сегодня» с carry-in позиции на открытие дня; «Сверка за день» на вкладке Портфель.
1.7.123 — Live-сигналы: единый 15м+Z ряд 255д (parity с «Тест страт.»), монитор и UI.
1.7.122 — Монитор: replay пропущенных 15м баров после пакетной загрузки; fix consume/journal.
1.7.97 — Fix: MOEX poll только на «Рынок» и при активном экране (не каждые 5с в фоне).
1.7.96 — Диагностика вылетов: WebView crash, lifecycle, старт на «Журнал», largeHeap.
1.7.95 — Журнал событий: лог вылетов/мониторинга, экспорт из «О приложении».
1.7.94 — Parity: экспорт журнала, journal_vs_sim_parity.py, baseline-тест 0.7/0.5.
1.7.93 — Бэктест стратегии на 3 годах MOEX (run_3y_strategy_test, m15_tatn_1095d).
1.7.92 — Walk-forward сетки порогов по кварталам (threshold_walkforward_quarters).
1.7.91 — Бэктест сетки порогов на 365д MOEX (threshold_sweep_365d CSV).
1.7.90 — Скрипт и тест сетки порогов вход/выход 0…2.5 (threshold_gap_sweep).
1.7.89 — Тест страт.: убрана подсказка «1 палец…» под графиком Z-score.
1.7.88 — Тест страт.: убраны легенда и Min/Max под Z-score; Equity выше.
1.7.87 — Тест страт.: график Equity и просадка сразу под Z-score.
1.7.86 — График Z: номера сделок вместо Enter/Exit; линия вход→выход при выборе (как desktop).
1.7.85 — График Z: fix подписи маркеров (UTF-8, 1A вместо «Ð»).
1.7.84 — Рынок: fix TradingView WebView (inline JS, base64 payload, resize).
1.7.83 — Рынок: график Z-score на TradingView (lightweight-charts, как strategy-web /m).
1.7.82 — Таблицы сделок: дата и время на двух строках, столбцы уже.
1.7.81 — Рынок: убран дублирующий график Z-свечей (остался desktop-вариант как в landscape).
1.7.80 — Spread (Рынок): правая ось 0%% = спред на 07:30 МСК (открытие торгового дня).
1.7.79 — Тест страт.: переключатель «Капитализация» без длинного пояснения.
1.7.78 — Портфель: убраны поясняющие тексты (Z, номинал, подписи таблиц).
1.7.77 — Портфель: убраны «Все показатели» и «Сверка за день».
1.7.76 — График Z: все сделки портфеля (А/Р), привязка к барам после downsample.
1.7.75 — График Z (Рынок): у сделок портфеля подпись номер+тип (1А, 2Р).
1.7.74 — Портфель: у сделок номер и тип одной буквой (Р/А).
1.7.73 — Портфель: фильтр «Всё / только Авто» в блоке сделок; убран поясняющий текст.
1.7.72 — Тестовая пара: перед входом догружается хвост MOEX, бар = текущий 15м слот.
1.7.71 — Push «Сделка закрыта»: сигнал выхода, PnL сделки и общий PnL счёта.
1.7.70 — Push «Сделка открыта»: номер сигнала и все параметры сделки (вместо push по ногам).
1.7.69 — Таблица портфеля: компактные даты (26-…), убраны Объём/Push/Заявка, уже колонки.
1.7.68 — Сигналы и сделки: еженедельная нумерация (с 1), формат «N long/short дата время».
"""
