package com.example.moexmvp

/** Зона запаса до маржин-колла по доле (ликвид − мин.маржа) / ликвид. */
internal enum class MarginCallHeadroomZone {
    Green,
    Yellow,
    Red,
}

/**
 * Запас до маржин-колла как в Т‑Инвест: liquid − minimal.
 * Маржин-колл, когда ликвидный портфель падает ниже минимальной маржи.
 */
internal data class MarginCallHeadroom(
    val freeRub: Double,
    val pct: Double,
    val zone: MarginCallHeadroomZone,
    val liquidRub: Double,
    val minimalMarginRub: Double,
    val startingMarginRub: Double,
)

internal const val MARGIN_CALL_HEADROOM_GREEN_PCT = 30.0
internal const val MARGIN_CALL_HEADROOM_YELLOW_PCT = 10.0

/** Мин. маржа для порога МК: поле API или половина начальной (как в справке Т‑Банка). */
internal fun resolveMinimalMarginRub(margin: MarginAttributesSnapshot): Double? {
    margin.minimalMarginRub?.takeIf { it > 0.0 && it.isFinite() }?.let { return it }
    margin.startingMarginRub.takeIf { it > 0.0 && it.isFinite() }?.let { return it / 2.0 }
    margin.correctedMarginRub.takeIf { it > 0.0 && it.isFinite() }?.let { return it / 2.0 }
    return null
}

internal fun computeMarginCallHeadroom(margin: MarginAttributesSnapshot): MarginCallHeadroom? {
    val liquid = margin.liquidPortfolioRub
    if (!liquid.isFinite()) return null
    val minimal = resolveMinimalMarginRub(margin) ?: return null
    val free = liquid - minimal
    val pct = if (kotlin.math.abs(liquid) > 1e-6) free / liquid * 100.0 else return null
    val zone = when {
        pct > MARGIN_CALL_HEADROOM_GREEN_PCT -> MarginCallHeadroomZone.Green
        pct >= MARGIN_CALL_HEADROOM_YELLOW_PCT -> MarginCallHeadroomZone.Yellow
        else -> MarginCallHeadroomZone.Red
    }
    return MarginCallHeadroom(
        freeRub = free,
        pct = pct,
        zone = zone,
        liquidRub = liquid,
        minimalMarginRub = minimal,
        startingMarginRub = margin.startingMarginRub,
    )
}
