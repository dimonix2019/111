package com.example.moexmvp

import java.time.format.DateTimeFormatter
import okhttp3.OkHttpClient

internal val httpClient = OkHttpClient()
internal val tradeDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
internal val updatedAtFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
internal val candleTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
internal val intradayLabelFormatter = DateTimeFormatter.ofPattern("HH:mm")
internal val portfolio15mLabelFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
internal const val DYNAMIC_Z_RECALC_HOUR = 9
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
internal const val PREF_Z_DAILY_SIGNAL_DATE = "z_daily_signal_date"
internal const val PREF_Z_DAILY_SIGNAL_COUNT = "z_daily_signal_count"
internal const val PREF_Z_DAILY_SIGNAL_ENTRY = "z_daily_signal_entry_legacy"
internal const val PREF_Z_DAILY_SIGNAL_EXIT = "z_daily_signal_exit_legacy"
internal const val DAILY_SIGNAL_MAX_PER_DAY = 20
internal const val FIXED_REALTIME_INTERVAL_MS = 5_000L
internal const val DEFAULT_PORTFOLIO_NOTIONAL_RUB = 100_000.0

/** Portfolio tab: entry/exit |Z| limits are independent (UI steppers). */
internal const val PORTFOLIO_Z_THRESHOLD_MIN = 0.0
internal const val PORTFOLIO_Z_THRESHOLD_MAX = 8.0
internal const val PORTFOLIO_Z_THRESHOLD_STEP = 0.05
/** Calendar days of history for 15m-style portfolio (10m ISS → 15m bars). ~1y; smaller than 365*1m traffic. */
internal const val PORTFOLIO_M15_LOOKBACK_DAYS = 252L

/** When refreshing from MOEX, re-fetch this many calendar days before last cached bar (overlap for ISS corrections). */
internal const val PORTFOLIO_M15_INCREMENTAL_OVERLAP_DAYS = 3L
internal const val TINKOFF_OVERNIGHT_FEE_PERCENT_PER_DAY = 0.033

/** Shown on the About tab (keep short; dates are illustrative). */
internal const val APP_CHANGELOG = """
1.5.3 — Рынок: убраны режимы «Реальные/Модель», маркеры только из журнала; без легенды под Z-графиком; убрана кнопка Test; один переключатель фонового монитора.
1.5.2 — Портфель: без «MOEX заново»; время свечей графика = Москва — подтверждённые сделки из журнала совпадают с 15м; фоновый монитор (BG) по умолчанию вкл. и стартует с приложением.
1.5.1 — Вкладка «Тест страт.»: пороги Z, пресеты, walk-forward, сделки симуляции. «Портфель» — только подтверждённые сделки (журнал вход/выход).
1.5.0 — Иконки на кнопках; карточка «виртуальная сделка» Принять/Отклонить после сигнала входа (push/фон).
1.4.2 — Портфель только на 15-мин ряду (ISS→кэш); дневной расчёт убран. Walk-forward на тех же 15 мин.
1.4.1 — Портфель: компактная вёрстка; «Итого» и «Макс. просадка» рядом под порогами.
1.4.0 — Сводка на «Рынке», pull-to-refresh, статус данных, журнал сигналов, пресеты портфеля, walk-forward пороги, офлайн-снимок, экран «О приложении».
1.3.0 — Сплит файлов, .cursorignore, кэш 15м SQLite.
1.2.x — Портфель: плечо, комиссия, овернайт Тинькофф, режимы сигналов, пороги вручную.
"""
