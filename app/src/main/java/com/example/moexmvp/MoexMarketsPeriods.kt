package com.example.moexmvp

/** Периоды Z-графика и дневных свечей на вкладке «Рынок» (без 6M/1Y — меньше данных и стабильнее). */
internal val MARKETS_UI_PERIODS: List<Period> = listOf(
    Period.OneDay,
    Period.OneWeek,
    Period.OneMonth,
    Period.ThreeMonths,
)

internal fun Period.coerceToMarketsUiPeriod(): Period =
    if (this in MARKETS_UI_PERIODS) this else Period.ThreeMonths

internal val MARKETS_M15_MAX_LOOKBACK_DAYS: Long
    get() = marketsM15LookbackDays(Period.ThreeMonths)
