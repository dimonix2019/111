package com.example.moexmvp

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

internal fun spreadPnlToRubApprox(pnlSpreadPoints: Double, notionalRub: Double): Double {
    return notionalRub * (pnlSpreadPoints / 100.0)
}

private fun overnightDays(entryDate: String, endDate: String): Long {
    val entry = parseChartDateLabel(entryDate) ?: return 0L
    val end = parseChartDateLabel(endDate) ?: return 0L
    return kotlin.math.max(0L, java.time.temporal.ChronoUnit.DAYS.between(entry, end))
}

/** Daily labels `yyyy-MM-dd` or 15m labels `yyyy-MM-dd HH:mm` from [portfolio15mLabelFormatter]. */
private fun parseChartDateLabel(label: String): LocalDate? {
    val trimmed = label.trim()
    if (trimmed.length >= 10) {
        runCatching { return LocalDate.parse(trimmed.take(10)) }.getOrNull()
    }
    return runCatching { LocalDateTime.parse(trimmed, portfolio15mLabelFormatter).toLocalDate() }
        .getOrNull()
}

/**
 * Mirrors [backtestZStrategy] but records round-trips, open leg, equity path (realized + MTM each bar),
 * and portfolio statistics. PnL is in spread %-points; rub figures scale by effective notional (own capital × leverage).
 *
 * @param compoundReturns если true, перед каждой новой сделкой пересчитываем собственный капитал как
 * `max(1 ₽, стартовый notional + накопленный realizedRub)` и от него снова берём плечо, комиссию и овернайт —
 * прибыль увеличивает размер следующей позиции (упрощённая капитализация в симуляции).
 */
internal fun buildZStrategyPortfolioMetrics(
    points: List<DataPoint>,
    thresholds: DynamicThresholds,
    notionalRub: Double,
    leverage: Double,
    commissionPercentPerSide: Double,
    periodDescription: String,
    compoundReturns: Boolean = false
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

    var tradeEffNotionalRub = notionalRub * leverage
    var tradeCommissionPerSideRub = tradeEffNotionalRub * (commissionPercentPerSide / 100.0)
    var tradeOvernightFeePerDayRub =
        notionalRub * (leverage - 1.0).coerceAtLeast(0.0) * (TINKOFF_OVERNIGHT_FEE_PERCENT_PER_DAY / 100.0)

    fun applyTradeSizingFromRealized() {
        if (!compoundReturns) return
        val base = kotlin.math.max(1.0, notionalRub + realizedRub)
        tradeEffNotionalRub = base * leverage
        tradeCommissionPerSideRub = tradeEffNotionalRub * (commissionPercentPerSide / 100.0)
        val borrowedRub = base * (leverage - 1.0).coerceAtLeast(0.0)
        tradeOvernightFeePerDayRub = borrowedRub * (TINKOFF_OVERNIGHT_FEE_PERCENT_PER_DAY / 100.0)
    }

    var entryCommissionRub = 0.0

    fun pushEquitySnapshot(atIndex: Int) {
        val bar = points[atIndex]
        val mtm = when (position) {
            ZStrategyPosition.Flat -> 0.0
            ZStrategyPosition.Long -> bar.spreadPercent - entrySpread
            ZStrategyPosition.Short -> entrySpread - bar.spreadPercent
        }
        val mtmRub = spreadPnlToRubApprox(mtm, tradeEffNotionalRub)
        equityRubSeries += realizedRub + mtmRub
    }

    for (index in 1 until points.size) {
        val prev = points[index - 1]
        val current = points[index]
        when (position) {
            ZStrategyPosition.Long -> {
                if (prev.zScore < -exit && current.zScore >= -exit) {
                    val pnl = current.spreadPercent - entrySpread
                    val grossPnlRub = spreadPnlToRubApprox(pnl, tradeEffNotionalRub)
                    val overnightRub = tradeOvernightFeePerDayRub * overnightDays(entryDate, current.tradeDate)
                    val netTradeRub = grossPnlRub - entryCommissionRub - tradeCommissionPerSideRub - overnightRub
                    realizedSpread += pnl
                    realizedRub += grossPnlRub - tradeCommissionPerSideRub
                    realizedRub -= overnightRub
                    totalCommissionRub += tradeCommissionPerSideRub
                    totalOvernightRub += overnightRub
                    closed += PortfolioClosedTrade(
                        direction = ZStrategyPosition.Long,
                        entryDate = entryDate,
                        exitDate = current.tradeDate,
                        entrySpreadPercent = entrySpread,
                        exitSpreadPercent = current.spreadPercent,
                        pnlSpreadPoints = pnl,
                        grossPnlRubApprox = grossPnlRub,
                        pnlRubApprox = netTradeRub
                    )
                    position = ZStrategyPosition.Flat
                    entryCommissionRub = 0.0
                }
            }

            ZStrategyPosition.Short -> {
                if (prev.zScore > exit && current.zScore <= exit) {
                    val pnl = entrySpread - current.spreadPercent
                    val grossPnlRub = spreadPnlToRubApprox(pnl, tradeEffNotionalRub)
                    val overnightRub = tradeOvernightFeePerDayRub * overnightDays(entryDate, current.tradeDate)
                    val netTradeRub = grossPnlRub - entryCommissionRub - tradeCommissionPerSideRub - overnightRub
                    realizedSpread += pnl
                    realizedRub += grossPnlRub - tradeCommissionPerSideRub
                    realizedRub -= overnightRub
                    totalCommissionRub += tradeCommissionPerSideRub
                    totalOvernightRub += overnightRub
                    closed += PortfolioClosedTrade(
                        direction = ZStrategyPosition.Short,
                        entryDate = entryDate,
                        exitDate = current.tradeDate,
                        entrySpreadPercent = entrySpread,
                        exitSpreadPercent = current.spreadPercent,
                        pnlSpreadPoints = pnl,
                        grossPnlRubApprox = grossPnlRub,
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
                applyTradeSizingFromRealized()
                position = ZStrategyPosition.Long
                entrySpread = current.spreadPercent
                entryDate = current.tradeDate
                entryCommissionRub = tradeCommissionPerSideRub
                totalCommissionRub += tradeCommissionPerSideRub
                realizedRub -= tradeCommissionPerSideRub
            } else if (prev.zScore < entry && current.zScore >= entry) {
                applyTradeSizingFromRealized()
                position = ZStrategyPosition.Short
                entrySpread = current.spreadPercent
                entryDate = current.tradeDate
                entryCommissionRub = tradeCommissionPerSideRub
                totalCommissionRub += tradeCommissionPerSideRub
                realizedRub -= tradeCommissionPerSideRub
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
        val grossUnrealizedRub = spreadPnlToRubApprox(grossUnrealizedSpread, tradeEffNotionalRub)
        val openOvernightRub = tradeOvernightFeePerDayRub * overnightDays(entryDate, last.tradeDate)
        val netUnrealizedRub = grossUnrealizedRub - tradeCommissionPerSideRub - openOvernightRub
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

    val capNote = if (compoundReturns) " · капитализация PnL" else ""
    val fullDescription =
        "$periodDescription · Z-вход ±${String.format(Locale.US, "%.1f", entry)}, выход ±${String.format(Locale.US, "%.1f", exit)}$capNote"
    return PortfolioMetrics(
        periodDescription = fullDescription,
        notionalRub = notionalRub,
        leverage = leverage,
        commissionPercentPerSide = commissionPercentPerSide,
        totalCommissionRub = totalCommissionRub,
        totalOvernightRub = totalOvernightRub +
            if (position != ZStrategyPosition.Flat) {
                tradeOvernightFeePerDayRub * overnightDays(entryDate, last.tradeDate)
            } else {
                0.0
            },
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
        .sortedWith(compareBy({ it.third }, { it.first.timestampMillis }))

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
                            grossPnlRubApprox = grossPnlRub,
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
                            grossPnlRubApprox = grossPnlRub,
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
        periodDescription = "$periodDescription · подтверждённые вход/выход (журнал)",
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
