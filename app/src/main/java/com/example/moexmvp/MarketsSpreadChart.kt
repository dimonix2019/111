package com.example.moexmvp

import androidx.compose.ui.graphics.Color

/** Оранжево-жёлтая линия цели ТП на web-столе (`TP_EXIT_LINE_COLOR` / trade.js). */
internal val SPREAD_TP_EXIT_LINE_COLOR = Color(0xFFFB923C)

internal val MARKETS_PHONE_SPREAD_LEVEL_LINES = listOf(
    ChartReferenceLine(DEFAULT_SPREAD_ENTER_WIDE, Color(0xFF2962FF), "S вх 6,1"),
    ChartReferenceLine(DEFAULT_SPREAD_EXIT_WIDE, Color(0xFF26A69A), "S вых 5,8"),
    ChartReferenceLine(DEFAULT_SPREAD_EXIT_NARROW, Color(0xFF26A69A), "L вых 4"),
    ChartReferenceLine(DEFAULT_SPREAD_ENTER_NARROW, Color(0xFF2962FF), "L вх 3,2"),
)

/**
 * Горизонтали L/S вх/вых + опционально «ТП N%» при открытой позиции.
 * Last price — только через lastValueVisible серии (без дублирующей reference line).
 */
internal fun buildMarketsSpreadChartReferenceLines(
    openSide: ZStrategyPosition?,
    openEntrySpread: Double? = null,
    depositRub: Double? = null,
    notionalRub: Double? = null,
    takeProfitPct: Double = DEFAULT_TAKE_PROFIT_PCT,
): List<ChartReferenceLine> {
    val lines = MARKETS_PHONE_SPREAD_LEVEL_LINES.toMutableList()
    if (openSide == null || openSide == ZStrategyPosition.Flat || takeProfitPct <= 0) {
        return lines
    }
    val tpSpread = openEntrySpread?.takeIf { it.isFinite() }?.let { entry ->
        val dep = depositRub?.takeIf { it > 0 } ?: return@let null
        val eff = notionalRub?.takeIf { it > 0 }
            ?: dep * SPREAD_LOT_PROD_DEFAULT_LEVERAGE
        takeProfitExitSpread(
            side = openSide,
            entrySpreadPercent = entry,
            depositRub = dep,
            effNotionalRub = eff,
            takeProfitPct = takeProfitPct,
        )
    }
    val level = tpSpread ?: when (openSide) {
        ZStrategyPosition.Long -> DEFAULT_SPREAD_EXIT_NARROW
        ZStrategyPosition.Short -> DEFAULT_SPREAD_EXIT_WIDE
        ZStrategyPosition.Flat -> return lines
    }
    val tpLabel = if (takeProfitPct % 1.0 == 0.0) {
        "ТП ${takeProfitPct.toInt()}%"
    } else {
        "ТП $takeProfitPct%"
    }
    lines += ChartReferenceLine(
        value = level,
        color = SPREAD_TP_EXIT_LINE_COLOR,
        label = tpLabel,
        dashOnPx = 6f,
        dashOffPx = 4f,
    )
    return lines
}

internal fun resolveMarketsSpreadChartOpenSide(
    openExecutions: List<SandboxSpreadExecUi>,
    brokerSide: ZStrategyPosition,
): ZStrategyPosition? {
    val fromExec = openExecutions.firstOrNull()?.signalType?.let { signal ->
        when (signal) {
            StrategySignalType.EnterLong -> ZStrategyPosition.Long
            StrategySignalType.EnterShort -> ZStrategyPosition.Short
            else -> null
        }
    }
    return fromExec ?: brokerSide.takeUnless { it == ZStrategyPosition.Flat }
}
