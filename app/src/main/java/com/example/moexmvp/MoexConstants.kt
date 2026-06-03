package com.example.moexmvp

import java.time.format.DateTimeFormatter
import okhttp3.OkHttpClient

internal val httpClient = OkHttpClient()
internal val tradeDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
internal val updatedAtFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
internal val candleTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
internal val intradayLabelFormatter = DateTimeFormatter.ofPattern("HH:mm")
internal val portfolio15mLabelFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
/** Локальное время (часы устройства): до этого момента пороги Z не пересчитываются «за сегодня». */
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
internal const val PREF_STRATEGY_TEST_EXIT_MODE = "strategy_test_exit_mode"
internal const val PREF_STRATEGY_TEST_Z_PEAK_TRAIL = "strategy_test_z_peak_trail"
/** @deprecated миграция в [PREF_REAL_TRADE_Z_ENTRY] при первом запуске. */
internal const val PREF_PORTFOLIO_Z_ENTRY_THRESHOLD = "portfolio_z_entry_threshold"
/** @deprecated */
internal const val PREF_PORTFOLIO_Z_EXIT_THRESHOLD = "portfolio_z_exit_threshold"
/** Один сигнал на пересечение порога на конкретном 15м баре (barTs|EnterLong и т.д.). */
internal const val PREF_LAST_CONSUMED_15M_SIGNAL_EDGE = "last_consumed_15m_signal_edge"
internal const val PREF_Z_DAILY_SIGNAL_DATE = "z_daily_signal_date"
internal const val PREF_Z_DAILY_SIGNAL_COUNT = "z_daily_signal_count"
internal const val PREF_Z_DAILY_SIGNAL_ENTRY = "z_daily_signal_entry_legacy"
internal const val PREF_Z_DAILY_SIGNAL_EXIT = "z_daily_signal_exit_legacy"
internal const val DAILY_SIGNAL_MAX_PER_DAY = 20
/** Skip duplicate journal rows when UI and background service see the same edge within this window. */
internal const val STRATEGY_SIGNAL_JOURNAL_DEDUP_WALL_MS = 25_000L
internal const val FIXED_REALTIME_INTERVAL_MS = 5_000L
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
/** Вкладка «Портфель»: метрики и сделки — достаточно ~3 мес. (~3k баров вместо ~13k). */
internal const val PORTFOLIO_TAB_M15_LOOKBACK_DAYS = 90L
/** Сколько дней 15м хранить в SQLite (макс. из вкладок; «Тест страт.» 255, «Рынок» до 3M). */
internal val PORTFOLIO_M15_CACHE_RETENTION_DAYS: Long
    get() = maxOf(
        PORTFOLIO_M15_LOOKBACK_DAYS,
        PORTFOLIO_TAB_M15_LOOKBACK_DAYS,
        MARKETS_M15_MAX_LOOKBACK_DAYS,
    )
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
1.7.57 — Обновление: скачивание APK с зеркала gh-pages, если Release отдаёт 404.
1.7.56 — Диалог обновления: убран длинный текст про установку с GitHub / Play Store.
1.7.55 — Рынок: убраны подсказки Interval 5s и «1 палец — сдвиг»; Z и Spread 15м — горизонтальные даты dd.MM.yy HH:mm.
1.7.54 — Рынок: MOEX и время в одной строке с Z/спред (без секунд); полноэкранный Z — горизонтальные подписи dd.MM.yy HH:mm, уже по ширине.
1.7.53 — «О приложении»: только 5 версий в истории; без плашки версии на вкладках; журнал диагностики отключён; убраны debug APK и «Демо-счёт» на «Рынке».
"""
