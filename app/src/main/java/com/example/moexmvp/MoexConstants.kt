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
/** Не потолок сделок: размер по плечу (пустой счёт) / запасу маржи (уже в позиции). */
internal const val SPREAD_LOT_MAX_LOTS = 10_000_000
/** «Тест страт.»: тот же верх, что и Торговля (потолка 80 нет). */
internal const val STRATEGY_TEST_SIM_MAX_LOTS_UNCAPPED = SPREAD_LOT_MAX_LOTS
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
/**
 * Спред TATN/TATNP % на графике «Рынок» 1м: вне диапазона — битая нога / дыра в ценах.
 * Типичный преф ~2–8%; запас под шоки, без 0%/100% от нулевых close.
 */
internal const val SPREAD_1M_CHART_MIN_PERCENT = -2.0
internal const val SPREAD_1M_CHART_MAX_PERCENT = 20.0
/** Скачок close относительно медианы соседних баров (п.п.) → бар пропускаем. */
internal const val SPREAD_1M_OUTLIER_JUMP_PP = 2.5
/** Окно соседей (±N) для медианы при отбраковке outlier. */
internal const val SPREAD_1M_OUTLIER_NEIGHBOR_RADIUS = 5
/** Z-свеча «формируется» на «Рынок» (live Z из 1м). */
internal const val CHART_FORMING_BAR_BORDER_HEX = "#FBBF24"
internal const val CHART_FORMING_BAR_BODY_UP_HEX = "#B45309"
internal const val CHART_FORMING_BAR_BODY_DOWN_HEX = "#92400E"
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
2.0.28 — Push + алерт «Закрытие неполное» при частичном исполнении выхода; остаток сделки остаётся открытым. (сборка 457: повторная публикация после ошибочного APK 1.7.232)
2.0.27 — «Рынок»: один last price на оси спреда (без дубля 3,13); линия «ТП 2%» при открытой позиции.
2.0.26 — «Рынок»: pan/pinch графика не сбрасывается к last bar; follow-live только у правого края.
2.0.25 — «Рынок»: OHLC текущей/наведённой свечи спреда % рядом с подписью TATN/TATNP.
2.0.24 — «Рынок»: локальный TradingView-график спреда 1м с зонами, уровнями и сделками; экстренное закрытие перенесено в «Сделка».
"""
