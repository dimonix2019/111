package com.example.moexmvp

/**
 * Z для UI «Рынок»: rolling по spread из полного 255д ряда, без persistedZ из SQLite
 * (снимки могут «застыть» на −3.5+ при пересмотре spread MOEX).
 */
internal fun recalcM15ZForChartDisplayWindow(
    window: List<DataPoint>,
    rollingBase: List<DataPoint>,
): List<DataPoint> {
    if (window.isEmpty()) return window
    if (rollingBase.size < Z_SCORE_ROLLING_MIN_BARS) return window
    val labels = window.map { it.tradeDate }.toSet()
    val byLabel = linkedMapOf<String, DataPoint>()
    rollingBase.forEach { byLabel[it.tradeDate] = it }
    window.forEach { pt -> byLabel[pt.tradeDate] = pt }
    val combined = ensureAscendingM15Points(byLabel.values.toList()).toMutableList()
    applyZScoresDefaultInPlace(combined)
    return combined.filter { it.tradeDate in labels }
}

/** Rolling-Z на последнем баре полного 15м ряда (fallback если live 1м недоступен). */
internal fun rollingZForLastM15Bar(points: List<DataPoint>): Double? {
    if (points.size < Z_SCORE_ROLLING_MIN_BARS) return null
    return rollingZAtBarIndex(points, points.lastIndex)
}
