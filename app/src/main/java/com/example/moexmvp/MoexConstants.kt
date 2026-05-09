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
internal const val PORTFOLIO_M15_LOOKBACK_DAYS = 365L
internal const val TINKOFF_OVERNIGHT_FEE_PERCENT_PER_DAY = 0.033
