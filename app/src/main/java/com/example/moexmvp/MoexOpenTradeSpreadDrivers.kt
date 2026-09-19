package com.example.moexmvp

import java.util.Locale

/** Спрэд и номинал — драйверы PnL открытой сделки (MOEX 15м + факт исполнения). */
internal fun enrichOpenTradeGroupSpreadDrivers(
    group: PortfolioTradeGroupRow,
    exec: SandboxSpreadExecUi,
    points: List<DataPoint>,
): PortfolioTradeGroupRow {
    val entrySpread = resolveEntrySpreadPercent(
        exec.entrySpreadPercent,
        exec.barTimestampMillis,
        points,
    )
    val currentSpread = points.lastOrNull()?.spreadPercent ?: Double.NaN
    val spreadMtmPoints = if (entrySpread.isNaN() || currentSpread.isNaN()) {
        Double.NaN
    } else {
        openSpreadMtmPoints(exec.signalType, entrySpread, currentSpread)
    }
    val notionalRub = resolveTradeNotionalRubForPnl(exec, points)
    return group.copy(
        entrySpreadPercent = entrySpread,
        currentSpreadPercent = currentSpread,
        spreadMtmPoints = spreadMtmPoints,
        executionNotionalRub = notionalRub,
    )
}

internal fun formatPortfolioSpreadPercent(spread: Double): String =
    if (spread.isNaN()) "—" else String.format(Locale.US, "%.2f%%", spread)

internal fun formatPortfolioOpenTradeNotional(
    volumeText: String,
    notionalRub: Double,
): String = when {
    notionalRub.isNaN() || notionalRub <= 0.0 -> volumeText
    volumeText.isBlank() || volumeText == "—" ->
        "≈${String.format(Locale.US, "%.0f", notionalRub)} ₽"
    else ->
        "$volumeText · ≈${String.format(Locale.US, "%.0f", notionalRub)} ₽"
}
