package com.example.moexmvp

/** Зона запаса до маржин-колла по доле free/corrected. */
internal enum class MarginCallHeadroomZone {
    Green,
    Yellow,
    Red,
}

/** free = liquid − corrected; pct = free / corrected × 100. */
internal data class MarginCallHeadroom(
    val freeRub: Double,
    val pct: Double,
    val zone: MarginCallHeadroomZone,
)

internal const val MARGIN_CALL_HEADROOM_GREEN_PCT = 30.0
internal const val MARGIN_CALL_HEADROOM_YELLOW_PCT = 10.0

internal fun computeMarginCallHeadroom(margin: MarginAttributesSnapshot): MarginCallHeadroom? {
    val corrected = margin.correctedMarginRub
    if (corrected <= 0.0) return null
    val free = margin.liquidPortfolioRub - corrected
    val pct = free / corrected * 100.0
    val zone = when {
        pct > MARGIN_CALL_HEADROOM_GREEN_PCT -> MarginCallHeadroomZone.Green
        pct >= MARGIN_CALL_HEADROOM_YELLOW_PCT -> MarginCallHeadroomZone.Yellow
        else -> MarginCallHeadroomZone.Red
    }
    return MarginCallHeadroom(freeRub = free, pct = pct, zone = zone)
}
