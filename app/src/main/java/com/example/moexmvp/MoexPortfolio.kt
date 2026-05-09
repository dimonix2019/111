package com.example.moexmvp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

internal fun spreadPnlToRubApprox(pnlSpreadPoints: Double, notionalRub: Double): Double {
    return notionalRub * (pnlSpreadPoints / 100.0)
}

private fun overnightDays(entryDate: String, endDate: String): Long {
    val entry = runCatching { LocalDate.parse(entryDate) }.getOrNull() ?: return 0L
    val end = runCatching { LocalDate.parse(endDate) }.getOrNull() ?: return 0L
    return kotlin.math.max(0L, java.time.temporal.ChronoUnit.DAYS.between(entry, end))
}

/**
 * Mirrors [backtestZStrategy] but records round-trips, open leg, equity path (realized + MTM each bar),
 * and portfolio statistics. PnL is in spread %-points; rub figures are a rough scaling by notional.
 */
internal fun buildZStrategyPortfolioMetrics(
    points: List<DataPoint>,
    thresholds: DynamicThresholds,
    notionalRub: Double,
    leverage: Double,
    commissionPercentPerSide: Double,
    periodDescription: String
): PortfolioMetrics? {
    if (points.size < 2) return null

    val entry = thresholds.entry
    val exit = thresholds.exit

    var position = ZStrategyPosition.Flat
    var entrySpread = 0.0
    var entryDate = ""
    var realizedSpread = 0.0
    var realizedRub = 0.0
    var totalCommissionRub = 0.0
    var totalOvernightRub = 0.0
    val closed = mutableListOf<PortfolioClosedTrade>()
    val equityRubSeries = mutableListOf<Double>()
    val effectiveNotionalRub = notionalRub * leverage
    val commissionPerSideRub = effectiveNotionalRub * (commissionPercentPerSide / 100.0)
    val borrowedRub = notionalRub * (leverage - 1.0).coerceAtLeast(0.0)
    val overnightFeePerDayRub = borrowedRub * (TINKOFF_OVERNIGHT_FEE_PERCENT_PER_DAY / 100.0)
    var entryCommissionRub = 0.0

    fun pushEquitySnapshot(atIndex: Int) {
        val bar = points[atIndex]
        val mtm = when (position) {
            ZStrategyPosition.Flat -> 0.0
            ZStrategyPosition.Long -> bar.spreadPercent - entrySpread
            ZStrategyPosition.Short -> entrySpread - bar.spreadPercent
        }
        val mtmRub = spreadPnlToRubApprox(mtm, effectiveNotionalRub)
        equityRubSeries += realizedRub + mtmRub
    }

    for (index in 1 until points.size) {
        val prev = points[index - 1]
        val current = points[index]
        when (position) {
            ZStrategyPosition.Long -> {
                if (prev.zScore < -exit && current.zScore >= -exit) {
                    val pnl = current.spreadPercent - entrySpread
                    val grossPnlRub = spreadPnlToRubApprox(pnl, effectiveNotionalRub)
                    val overnightRub = overnightFeePerDayRub * overnightDays(entryDate, current.tradeDate)
                    val netTradeRub = grossPnlRub - entryCommissionRub - commissionPerSideRub - overnightRub
                    realizedSpread += pnl
                    realizedRub += grossPnlRub - commissionPerSideRub
                    realizedRub -= overnightRub
                    totalCommissionRub += commissionPerSideRub
                    totalOvernightRub += overnightRub
                    closed += PortfolioClosedTrade(
                        direction = ZStrategyPosition.Long,
                        entryDate = entryDate,
                        exitDate = current.tradeDate,
                        entrySpreadPercent = entrySpread,
                        exitSpreadPercent = current.spreadPercent,
                        pnlSpreadPoints = pnl,
                        pnlRubApprox = netTradeRub
                    )
                    position = ZStrategyPosition.Flat
                    entryCommissionRub = 0.0
                }
            }

            ZStrategyPosition.Short -> {
                if (prev.zScore > exit && current.zScore <= exit) {
                    val pnl = entrySpread - current.spreadPercent
                    val grossPnlRub = spreadPnlToRubApprox(pnl, effectiveNotionalRub)
                    val overnightRub = overnightFeePerDayRub * overnightDays(entryDate, current.tradeDate)
                    val netTradeRub = grossPnlRub - entryCommissionRub - commissionPerSideRub - overnightRub
                    realizedSpread += pnl
                    realizedRub += grossPnlRub - commissionPerSideRub
                    realizedRub -= overnightRub
                    totalCommissionRub += commissionPerSideRub
                    totalOvernightRub += overnightRub
                    closed += PortfolioClosedTrade(
                        direction = ZStrategyPosition.Short,
                        entryDate = entryDate,
                        exitDate = current.tradeDate,
                        entrySpreadPercent = entrySpread,
                        exitSpreadPercent = current.spreadPercent,
                        pnlSpreadPoints = pnl,
                        pnlRubApprox = netTradeRub
                    )
                    position = ZStrategyPosition.Flat
                    entryCommissionRub = 0.0
                }
            }

            ZStrategyPosition.Flat -> Unit
        }
        if (position == ZStrategyPosition.Flat) {
            if (prev.zScore > -entry && current.zScore <= -entry) {
                position = ZStrategyPosition.Long
                entrySpread = current.spreadPercent
                entryDate = current.tradeDate
                entryCommissionRub = commissionPerSideRub
                totalCommissionRub += commissionPerSideRub
                realizedRub -= commissionPerSideRub
            } else if (prev.zScore < entry && current.zScore >= entry) {
                position = ZStrategyPosition.Short
                entrySpread = current.spreadPercent
                entryDate = current.tradeDate
                entryCommissionRub = commissionPerSideRub
                totalCommissionRub += commissionPerSideRub
                realizedRub -= commissionPerSideRub
            }
        }
        pushEquitySnapshot(index)
    }

    val last = points.last()
    val open: PortfolioOpenPosition?
    val unrealizedSpread: Double
    if (position != ZStrategyPosition.Flat) {
        val grossUnrealizedSpread = when (position) {
            ZStrategyPosition.Long -> last.spreadPercent - entrySpread
            ZStrategyPosition.Short -> entrySpread - last.spreadPercent
            ZStrategyPosition.Flat -> 0.0
        }
        unrealizedSpread = grossUnrealizedSpread
        val grossUnrealizedRub = spreadPnlToRubApprox(grossUnrealizedSpread, effectiveNotionalRub)
        val openOvernightRub = overnightFeePerDayRub * overnightDays(entryDate, last.tradeDate)
        val netUnrealizedRub = grossUnrealizedRub - commissionPerSideRub - openOvernightRub
        open = PortfolioOpenPosition(
            direction = position,
            entryDate = entryDate,
            entrySpreadPercent = entrySpread,
            lastSpreadPercent = last.spreadPercent,
            unrealizedPnlSpread = unrealizedSpread,
            unrealizedRubApprox = netUnrealizedRub
        )
    } else {
        unrealizedSpread = 0.0
        open = null
    }

    val totalSpread = realizedSpread + unrealizedSpread
    val totalRub = realizedRub + (open?.unrealizedRubApprox ?: 0.0)

    var peak = doubleArrayOf(0.0)
    var maxDdRub = 0.0
    var maxDdPct = 0.0
    for (eq in equityRubSeries) {
        peak[0] = max(peak[0], eq)
        val dd = peak[0] - eq
        if (dd > maxDdRub) maxDdRub = dd
        if (peak[0] != 0.0) {
            val pct = dd / abs(peak[0]) * 100.0
            if (pct > maxDdPct) maxDdPct = pct
        }
    }

    val wins = closed.count { it.pnlRubApprox > 0 }
    val losses = closed.count { it.pnlRubApprox < 0 }
    val winRate = if (closed.isEmpty()) 0.0 else wins * 100.0 / closed.size

    val grossWin = closed.filter { it.pnlRubApprox > 0 }.sumOf { it.pnlRubApprox }
    val grossLoss = closed.filter { it.pnlRubApprox < 0 }.sumOf { -it.pnlRubApprox }
    val profitFactor = if (grossLoss > 0) grossWin / grossLoss else null

    val winRubList = closed.mapNotNull { if (it.pnlRubApprox > 0) it.pnlRubApprox else null }
    val lossRubList = closed.mapNotNull { if (it.pnlRubApprox < 0) it.pnlRubApprox else null }
    val avgWin = if (winRubList.isEmpty()) 0.0 else winRubList.average()
    val avgLoss = if (lossRubList.isEmpty()) 0.0 else lossRubList.average()
    val largestWin = winRubList.maxOrNull() ?: 0.0
    val largestLoss = lossRubList.minOrNull() ?: 0.0

    val fullDescription = "$periodDescription · Z-вход ±${String.format(Locale.US, "%.1f", entry)}, выход ±${String.format(Locale.US, "%.1f", exit)}"
    return PortfolioMetrics(
        periodDescription = fullDescription,
        notionalRub = notionalRub,
        leverage = leverage,
        commissionPercentPerSide = commissionPercentPerSide,
        totalCommissionRub = totalCommissionRub,
        totalOvernightRub = totalOvernightRub +
            if (position != ZStrategyPosition.Flat) overnightFeePerDayRub * overnightDays(entryDate, last.tradeDate) else 0.0,
        closedTrades = closed,
        openPosition = open,
        cumulativeRealizedSpread = realizedSpread,
        cumulativeRealizedRubApprox = realizedRub,
        unrealizedRubApprox = open?.unrealizedRubApprox ?: 0.0,
        totalPnlSpread = totalSpread,
        totalPnlRubApprox = totalRub,
        totalReturnPercent = if (notionalRub > 0) totalRub / notionalRub * 100.0 else 0.0,
        maxDrawdownRubApprox = maxDdRub,
        maxDrawdownPercent = maxDdPct,
        winCount = wins,
        lossCount = losses,
        winRate = winRate,
        profitFactor = profitFactor,
        avgWinRub = avgWin,
        avgLossRub = avgLoss,
        largestWinRub = largestWin,
        largestLossRub = largestLoss
    )
}

internal fun buildExecutedPortfolioMetrics(
    points: List<DataPoint>,
    events: List<StrategySignalEvent>,
    notionalRub: Double,
    leverage: Double,
    commissionPercentPerSide: Double,
    periodDescription: String
): PortfolioMetrics? {
    if (points.size < 2) return null
    val markers = buildZScoreSignalMarkersFromEvents(points, events)
    if (markers.isEmpty()) return null

    val effectiveNotionalRub = notionalRub * leverage
    val commissionPerSideRub = effectiveNotionalRub * (commissionPercentPerSide / 100.0)
    val borrowedRub = notionalRub * (leverage - 1.0).coerceAtLeast(0.0)
    val overnightFeePerDayRub = borrowedRub * (TINKOFF_OVERNIGHT_FEE_PERCENT_PER_DAY / 100.0)

    var realizedSpread = 0.0
    var realizedRub = 0.0
    var totalCommissionRub = 0.0
    var totalOvernightRub = 0.0
    var currentPosition = ZStrategyPosition.Flat
    var entrySpread = 0.0
    var entryDate = ""
    val closed = mutableListOf<PortfolioClosedTrade>()
    val equityRubSeries = mutableListOf<Double>()

    val eventsWithBar = markers
        .mapNotNull { marker ->
            val point = points.getOrNull(marker.index) ?: return@mapNotNull null
            Triple(point, marker.label, marker.index)
        }
        .sortedBy { it.third }

    var eventCursor = 0
    for (index in points.indices) {
        while (eventCursor < eventsWithBar.size && eventsWithBar[eventCursor].third == index) {
            val (point, label) = eventsWithBar[eventCursor].let { it.first to it.second }
            when (label) {
                "Enter LONG" -> {
                    if (currentPosition == ZStrategyPosition.Flat) {
                        currentPosition = ZStrategyPosition.Long
                        entrySpread = point.spreadPercent
                        entryDate = point.tradeDate
                        totalCommissionRub += commissionPerSideRub
                        realizedRub -= commissionPerSideRub
                    }
                }

                "Enter SHORT" -> {
                    if (currentPosition == ZStrategyPosition.Flat) {
                        currentPosition = ZStrategyPosition.Short
                        entrySpread = point.spreadPercent
                        entryDate = point.tradeDate
                        totalCommissionRub += commissionPerSideRub
                        realizedRub -= commissionPerSideRub
                    }
                }

                "Exit LONG" -> {
                    if (currentPosition == ZStrategyPosition.Long) {
                        val pnlSpread = point.spreadPercent - entrySpread
                        val grossPnlRub = spreadPnlToRubApprox(pnlSpread, effectiveNotionalRub)
                        val overnightRub = overnightFeePerDayRub * overnightDays(entryDate, point.tradeDate)
                        val netPnlRub = grossPnlRub - commissionPerSideRub - overnightRub
                        realizedSpread += pnlSpread
                        realizedRub += netPnlRub
                        totalOvernightRub += overnightRub
                        totalCommissionRub += commissionPerSideRub
                        closed += PortfolioClosedTrade(
                            direction = ZStrategyPosition.Long,
                            entryDate = entryDate,
                            exitDate = point.tradeDate,
                            entrySpreadPercent = entrySpread,
                            exitSpreadPercent = point.spreadPercent,
                            pnlSpreadPoints = pnlSpread,
                            pnlRubApprox = grossPnlRub - (2 * commissionPerSideRub) - overnightRub
                        )
                        currentPosition = ZStrategyPosition.Flat
                    }
                }

                "Exit SHORT" -> {
                    if (currentPosition == ZStrategyPosition.Short) {
                        val pnlSpread = entrySpread - point.spreadPercent
                        val grossPnlRub = spreadPnlToRubApprox(pnlSpread, effectiveNotionalRub)
                        val overnightRub = overnightFeePerDayRub * overnightDays(entryDate, point.tradeDate)
                        val netPnlRub = grossPnlRub - commissionPerSideRub - overnightRub
                        realizedSpread += pnlSpread
                        realizedRub += netPnlRub
                        totalOvernightRub += overnightRub
                        totalCommissionRub += commissionPerSideRub
                        closed += PortfolioClosedTrade(
                            direction = ZStrategyPosition.Short,
                            entryDate = entryDate,
                            exitDate = point.tradeDate,
                            entrySpreadPercent = entrySpread,
                            exitSpreadPercent = point.spreadPercent,
                            pnlSpreadPoints = pnlSpread,
                            pnlRubApprox = grossPnlRub - (2 * commissionPerSideRub) - overnightRub
                        )
                        currentPosition = ZStrategyPosition.Flat
                    }
                }
            }
            eventCursor += 1
        }

        val point = points[index]
        val mtmSpread = when (currentPosition) {
            ZStrategyPosition.Flat -> 0.0
            ZStrategyPosition.Long -> point.spreadPercent - entrySpread
            ZStrategyPosition.Short -> entrySpread - point.spreadPercent
        }
        equityRubSeries += realizedRub + spreadPnlToRubApprox(mtmSpread, effectiveNotionalRub)
    }

    val last = points.last()
    val openPosition = when (currentPosition) {
        ZStrategyPosition.Flat -> null
        ZStrategyPosition.Long, ZStrategyPosition.Short -> {
            val grossSpread = if (currentPosition == ZStrategyPosition.Long) {
                last.spreadPercent - entrySpread
            } else {
                entrySpread - last.spreadPercent
            }
            PortfolioOpenPosition(
                direction = currentPosition,
                entryDate = entryDate,
                entrySpreadPercent = entrySpread,
                lastSpreadPercent = last.spreadPercent,
                unrealizedPnlSpread = grossSpread,
                unrealizedRubApprox = spreadPnlToRubApprox(grossSpread, effectiveNotionalRub) -
                    commissionPerSideRub -
                    (overnightFeePerDayRub * overnightDays(entryDate, last.tradeDate))
            )
        }
    }

    var peak = 0.0
    var maxDdRub = 0.0
    var maxDdPct = 0.0
    for (eq in equityRubSeries) {
        peak = max(peak, eq)
        val dd = peak - eq
        if (dd > maxDdRub) maxDdRub = dd
        if (peak != 0.0) {
            val pct = dd / abs(peak) * 100.0
            if (pct > maxDdPct) maxDdPct = pct
        }
    }

    val totalSpread = realizedSpread + (openPosition?.unrealizedPnlSpread ?: 0.0)
    val totalRub = realizedRub + (openPosition?.unrealizedRubApprox ?: 0.0)
    val wins = closed.count { it.pnlRubApprox > 0.0 }
    val losses = closed.count { it.pnlRubApprox < 0.0 }
    val winRate = if (closed.isEmpty()) 0.0 else wins * 100.0 / closed.size
    val grossWin = closed.filter { it.pnlRubApprox > 0 }.sumOf { it.pnlRubApprox }
    val grossLoss = closed.filter { it.pnlRubApprox < 0 }.sumOf { -it.pnlRubApprox }
    val profitFactor = if (grossLoss > 0) grossWin / grossLoss else null
    val winRubList = closed.mapNotNull { if (it.pnlRubApprox > 0) it.pnlRubApprox else null }
    val lossRubList = closed.mapNotNull { if (it.pnlRubApprox < 0) it.pnlRubApprox else null }
    val avgWin = if (winRubList.isEmpty()) 0.0 else winRubList.average()
    val avgLoss = if (lossRubList.isEmpty()) 0.0 else lossRubList.average()
    val largestWin = winRubList.maxOrNull() ?: 0.0
    val largestLoss = lossRubList.minOrNull() ?: 0.0

    return PortfolioMetrics(
        periodDescription = "$periodDescription · режим: реальные сигналы",
        notionalRub = notionalRub,
        leverage = leverage,
        commissionPercentPerSide = commissionPercentPerSide,
        totalCommissionRub = totalCommissionRub,
        totalOvernightRub = totalOvernightRub +
            if (currentPosition != ZStrategyPosition.Flat) overnightFeePerDayRub * overnightDays(entryDate, last.tradeDate) else 0.0,
        closedTrades = closed,
        openPosition = openPosition,
        cumulativeRealizedSpread = realizedSpread,
        cumulativeRealizedRubApprox = realizedRub,
        unrealizedRubApprox = openPosition?.unrealizedRubApprox ?: 0.0,
        totalPnlSpread = totalSpread,
        totalPnlRubApprox = totalRub,
        totalReturnPercent = if (notionalRub > 0) totalRub / notionalRub * 100.0 else 0.0,
        maxDrawdownRubApprox = maxDdRub,
        maxDrawdownPercent = maxDdPct,
        winCount = wins,
        lossCount = losses,
        winRate = winRate,
        profitFactor = profitFactor,
        avgWinRub = avgWin,
        avgLossRub = avgLoss,
        largestWinRub = largestWin,
        largestLossRub = largestLoss
    )
}

@Composable
internal fun MainTabSelector(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MainTab.entries.forEach { tab ->
            val isSel = tab == selected
            Button(
                onClick = { onSelect(tab) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSel) Color(0xFF1565C0) else Color(0xFF424242),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(tab.label, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
internal fun StrategyViewModeSelector(
    mode: StrategyViewMode,
    onModeChange: (StrategyViewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StrategyViewMode.entries.forEach { candidate ->
            val selected = candidate == mode
            Button(
                onClick = { onModeChange(candidate) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) Color(0xFF00897B) else Color(0xFF424242),
                    contentColor = Color.White
                )
            ) {
                Text(candidate.label, fontSize = 12.sp)
            }
        }
    }
}

@Composable
internal fun PortfolioTabContent(
    metrics: PortfolioMetrics?,
    portfolioLoading: Boolean,
    portfolioError: String?,
    onRefresh: () -> Unit,
    leverage: Double,
    commissionPercentPerSide: Double,
    onLeverageChange: (Double) -> Unit,
    onCommissionChange: (Double) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Портфель Z-стратегии (TATN/TATNP)",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Обновить", fontSize = 12.sp)
            }
        }
        Text(
            text = "Оценка в ₽: капитал ${"%.0f".format(Locale.US, metrics?.notionalRub ?: DEFAULT_PORTFOLIO_NOTIONAL_RUB)} ₽, плечо x${String.format(Locale.US, "%.1f", leverage)}, комиссия ${String.format(Locale.US, "%.3f", commissionPercentPerSide)}% за сторону.",
            color = Color(0xFFBDBDBD),
            fontSize = 11.sp
        )
        PortfolioParamsControls(
            leverage = leverage,
            commissionPercentPerSide = commissionPercentPerSide,
            onLeverageChange = onLeverageChange,
            onCommissionChange = onCommissionChange
        )

        if (portfolioLoading) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(color = Color(0xFF64B5F6))
            }
            return@Column
        }

        if (portfolioError != null) {
            Text(portfolioError, color = Color(0xFFEF9A9A), fontSize = 12.sp)
            Button(onClick = onRefresh) { Text("Повторить") }
            return@Column
        }

        if (metrics == null) {
            Text("Недостаточно данных для расчёта.", color = Color(0xFFBDBDBD), fontSize = 12.sp)
            return@Column
        }

        Text(
            text = metrics.periodDescription,
            color = Color(0xFF9E9E9E),
            fontSize = 11.sp
        )

        PortfolioMetricGrid(metrics)

        Text(
            text = "Сделки (${metrics.closedTrades.size} закрытых)",
            color = Color(0xFFE0E0E0),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 8.dp)
        )

        val recent = metrics.closedTrades.takeLast(25).asReversed()
        if (recent.isEmpty()) {
            Text("Закрытых сделок за период нет.", color = Color(0xFF9E9E9E), fontSize = 12.sp)
        } else {
            recent.forEachIndexed { index, t ->
                PortfolioTradeRow(index = index + 1, t = t)
            }
        }
    }
}

@Composable
private fun PortfolioMetricGrid(m: PortfolioMetrics) {
    val pf = m.profitFactor?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PortfolioStatCard(
            "Итого PnL (оценка)",
            "${formatRubSigned(m.totalPnlRubApprox)} (${formatPercentSigned(m.totalReturnPercent)})"
        )
        PortfolioStatCard(
            "Реализовано / нереализ.",
            "${formatRubSigned(m.cumulativeRealizedRubApprox)} / ${formatRubSigned(m.unrealizedRubApprox)}"
        )
        PortfolioStatCard(
            "Комиссии (сумма)",
            "${formatRubSigned(-m.totalCommissionRub)} · ${String.format(Locale.US, "%.3f", m.commissionPercentPerSide)}% за сторону"
        )
        PortfolioStatCard(
            "Овернайт (Тинькофф)",
            "${formatRubSigned(-m.totalOvernightRub)} · ${String.format(Locale.US, "%.3f", TINKOFF_OVERNIGHT_FEE_PERCENT_PER_DAY)}%/день на заёмную часть"
        )
        PortfolioStatCard(
            "Макс. просадка",
            "${formatRubSigned(-m.maxDrawdownRubApprox)} (~ ${String.format(Locale.US, "%.1f", m.maxDrawdownRubApprox / m.notionalRub * 100.0)}% от капитала, по эквити-пику ${String.format(Locale.US, "%.1f", m.maxDrawdownPercent)}%)"
        )
        PortfolioStatCard(
            "Сделки / винрейт",
            "${m.winCount}W / ${m.lossCount}L · ${String.format(Locale.US, "%.0f", m.winRate)}%"
        )
        PortfolioStatCard("Profit factor", pf)
        PortfolioStatCard(
            "Средний W / L",
            "${formatRubSigned(m.avgWinRub)} / ${formatRubSigned(m.avgLossRub)}"
        )
        PortfolioStatCard(
            "Лучшая / худшая",
            "${formatRubSigned(m.largestWinRub)} / ${formatRubSigned(m.largestLossRub)}"
        )
        m.openPosition?.let { o ->
            val dir = when (o.direction) {
                ZStrategyPosition.Long -> "LONG спрэд"
                ZStrategyPosition.Short -> "SHORT спрэд"
                ZStrategyPosition.Flat -> ""
            }
            PortfolioStatCard(
                "Открытая позиция",
                "$dir с ${o.entryDate}, спрэд ${String.format(Locale.US, "%.2f", o.entrySpreadPercent)}% → ${String.format(Locale.US, "%.2f", o.lastSpreadPercent)}%"
            )
            PortfolioStatCard("Нереализ. PnL", formatRubSigned(o.unrealizedRubApprox))
        } ?: PortfolioStatCard("Открытая позиция", "Нет")
    }
}

@Composable
private fun PortfolioParamsControls(
    leverage: Double,
    commissionPercentPerSide: Double,
    onLeverageChange: (Double) -> Unit,
    onCommissionChange: (Double) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ParamStepper(
            title = "Плечо",
            valueLabel = "x${String.format(Locale.US, "%.1f", leverage)}",
            onMinus = { onLeverageChange((leverage - 0.5).coerceAtLeast(1.0)) },
            onPlus = { onLeverageChange((leverage + 0.5).coerceAtMost(30.0)) },
            modifier = Modifier.weight(1f)
        )
        ParamStepper(
            title = "Комиссия / сторона",
            valueLabel = "${String.format(Locale.US, "%.3f", commissionPercentPerSide)}%",
            onMinus = { onCommissionChange((commissionPercentPerSide - 0.005).coerceAtLeast(0.0)) },
            onPlus = { onCommissionChange((commissionPercentPerSide + 0.005).coerceAtMost(1.0)) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ParamStepper(
    title: String,
    valueLabel: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = Color(0xFF9E9E9E), fontSize = 11.sp)
        Text(valueLabel, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onMinus, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) {
                Text("−")
            }
            Button(onClick = onPlus, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) {
                Text("+")
            }
        }
    }
}

@Composable
private fun PortfolioStatCard(title: String, value: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(title, color = Color(0xFF9E9E9E), fontSize = 11.sp)
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PortfolioTradeRow(index: Int, t: PortfolioClosedTrade) {
    val dir = when (t.direction) {
        ZStrategyPosition.Long -> "LONG"
        ZStrategyPosition.Short -> "SHORT"
        ZStrategyPosition.Flat -> "—"
    }
    val pnlColor = when {
        t.pnlRubApprox > 0 -> Color(0xFF81C784)
        t.pnlRubApprox < 0 -> Color(0xFFE57373)
        else -> Color(0xFFBDBDBD)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF252525), RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("#$index  $dir ${t.entryDate} → ${t.exitDate}", color = Color(0xFFE0E0E0), fontSize = 12.sp)
            Text(formatRubSigned(t.pnlRubApprox), color = pnlColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "спрэд ${String.format(Locale.US, "%.2f", t.entrySpreadPercent)}% → ${String.format(Locale.US, "%.2f", t.exitSpreadPercent)}% (${String.format(Locale.US, "%+.2f", t.pnlSpreadPoints)} п.п.)",
            color = Color(0xFF9E9E9E),
            fontSize = 10.sp
        )
    }
}

private fun formatRubSigned(v: Double): String {
    val s = String.format(Locale.US, "%+.0f ₽", v)
    return s
}

private fun formatPercentSigned(v: Double): String {
    return String.format(Locale.US, "%+.2f%%", v)
}
