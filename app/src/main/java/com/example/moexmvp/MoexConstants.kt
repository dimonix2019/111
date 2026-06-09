package com.example.moexmvp

import java.time.format.DateTimeFormatter
import okhttp3.OkHttpClient

internal val httpClient = OkHttpClient()
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
internal const val STRATEGY_TEST_RESIM_DEBOUNCE_MS = 750L
internal const val DEFAULT_PORTFOLIO_NOTIONAL_RUB = 100_000.0

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

/** Если последний 15м бар старше этого интервала — принудительно догружаем хвост с MOEX. */
internal const val PORTFOLIO_M15_TAIL_MAX_AGE_MS = 40L * 60L * 60L * 1000L
internal const val TINKOFF_OVERNIGHT_FEE_PERCENT_PER_DAY = 0.033

/** Прямая загрузка debug APK (если репозиторий private — нужна авторизация GitHub в браузере, иначе будет 404). */
internal const val APK_DOWNLOAD_DIRECT_URL =
    "https://github.com/dimonix2019/111/releases/download/moexmvp-debug-latest/moexmvp-debug.apk"

internal const val APK_GITHUB_RELEASES_PAGE_URL = "https://github.com/dimonix2019/111/releases"

/** Shown on the About tab (последние 5 версий; старые записи не храним). */
internal const val APP_CHANGELOG = """
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
