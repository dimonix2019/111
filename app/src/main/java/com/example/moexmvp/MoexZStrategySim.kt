package com.example.moexmvp

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal fun buildZStrategyPortfolioMetrics(
    points: List<DataPoint>,
    thresholds: DynamicThresholds,
    notionalRub: Double,
    leverage: Double,
    commissionPercentPerSide: Double,
    periodDescription: String,
    compoundReturns: Boolean = false,
    exitMode: ZStrategyExitMode = ZStrategyExitMode.FixedThreshold,
    zPeakTrailZ: Double = 0.2,
    entryPullbackZ: Double = 0.0,
    simOptions: ZStrategySimOptions = ZStrategySimOptions()
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

    var positionNotionalRub = notionalRub
    var tradeEffNotionalRub = notionalRub * leverage
    var tradeCommissionPerSideRub = tradeEffNotionalRub * (commissionPercentPerSide / 100.0)
    var tradeOvernightFeePerDayRub =
        notionalRub * (leverage - 1.0).coerceAtLeast(0.0) * (TINKOFF_OVERNIGHT_FEE_PERCENT_PER_DAY / 100.0)

    fun refreshTradeFeesFromPositionNotional() {
        tradeEffNotionalRub = positionNotionalRub * leverage
        tradeCommissionPerSideRub = tradeEffNotionalRub * (commissionPercentPerSide / 100.0)
        tradeOvernightFeePerDayRub =
            positionNotionalRub * (leverage - 1.0).coerceAtLeast(0.0) *
                (TINKOFF_OVERNIGHT_FEE_PERCENT_PER_DAY / 100.0)
    }

    fun applyTradeSizingFromRealized() {
        positionNotionalRub = if (compoundReturns) {
            kotlin.math.max(1.0, notionalRub + realizedRub)
        } else {
            notionalRub
        }
        refreshTradeFeesFromPositionNotional()
    }

    var entryCommissionRub = 0.0
    var zBestSinceEntry = 0.0
    var entryPendingArm = ZEntryPendingArm.None
    var zExtremeWhilePending = 0.0
    var pyramidAdded = false

    fun resetFlatState() {
        entryCommissionRub = 0.0
        zBestSinceEntry = 0.0
        entryPendingArm = ZEntryPendingArm.None
        pyramidAdded = false
    }

    fun addPyramidLeg(bar: DataPoint) {
        val addN = simOptions.pyramidAddNotionalRub
        entrySpread = (entrySpread * positionNotionalRub + bar.spreadPercent * addN) /
            (positionNotionalRub + addN)
        positionNotionalRub += addN
        refreshTradeFeesFromPositionNotional()
        val addComm = addN * leverage * (commissionPercentPerSide / 100.0)
        entryCommissionRub += addComm
        totalCommissionRub += addComm
        realizedRub -= addComm
        pyramidAdded = true
    }

    fun forceSessionExit(bar: DataPoint): Boolean {
        val cutoff = simOptions.closeBeforeClearingMsk ?: return false
        return isAtOrAfterMskCutoff(bar.tradeDate, cutoff)
    }

    fun forceReportExit(bar: DataPoint): Boolean {
        if (!simOptions.skipTatnReportDays || !simOptions.closeOpenOnReportPublicationDay) return false
        val day = parseBarLocalDateMsk(bar.tradeDate) ?: return false
        return isTatnReportPublicationDay(day)
    }

    fun blockNewEntries(bar: DataPoint): Boolean {
        if (!simOptions.skipTatnReportDays) return false
        val day = parseBarLocalDateMsk(bar.tradeDate) ?: return false
        return isTatnReportBlackoutDay(day)
    }

    fun closeLongAt(current: DataPoint) {
        val pnl = current.spreadPercent - entrySpread
        val grossPnlRub = spreadPnlToRubApprox(pnl, tradeEffNotionalRub)
        val overnightRub = tradeOvernightFeePerDayRub * overnightDays(entryDate, current.tradeDate)
        val commissionRub = entryCommissionRub + tradeCommissionPerSideRub
        val netTradeRub = grossPnlRub - commissionRub - overnightRub
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
            commissionRubApprox = commissionRub,
            overnightRubApprox = overnightRub,
            pnlRubApprox = netTradeRub
        )
        position = ZStrategyPosition.Flat
        resetFlatState()
    }

    fun closeShortAt(current: DataPoint) {
        val pnl = entrySpread - current.spreadPercent
        val grossPnlRub = spreadPnlToRubApprox(pnl, tradeEffNotionalRub)
        val overnightRub = tradeOvernightFeePerDayRub * overnightDays(entryDate, current.tradeDate)
        val commissionRub = entryCommissionRub + tradeCommissionPerSideRub
        val netTradeRub = grossPnlRub - commissionRub - overnightRub
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
            commissionRubApprox = commissionRub,
            overnightRubApprox = overnightRub,
            pnlRubApprox = netTradeRub
        )
        position = ZStrategyPosition.Flat
        resetFlatState()
    }

    fun enterLongAtBar(bar: DataPoint) {
        applyTradeSizingFromRealized()
        position = ZStrategyPosition.Long
        entrySpread = bar.spreadPercent
        entryDate = bar.tradeDate
        entryCommissionRub = tradeCommissionPerSideRub
        zBestSinceEntry = bar.zScore
        totalCommissionRub += tradeCommissionPerSideRub
        realizedRub -= tradeCommissionPerSideRub
        entryPendingArm = ZEntryPendingArm.None
        pyramidAdded = false
    }

    fun enterShortAtBar(bar: DataPoint) {
        applyTradeSizingFromRealized()
        position = ZStrategyPosition.Short
        entrySpread = bar.spreadPercent
        entryDate = bar.tradeDate
        entryCommissionRub = tradeCommissionPerSideRub
        zBestSinceEntry = bar.zScore
        totalCommissionRub += tradeCommissionPerSideRub
        realizedRub -= tradeCommissionPerSideRub
        entryPendingArm = ZEntryPendingArm.None
        pyramidAdded = false
    }

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
                zBestSinceEntry = updateZBestForLong(zBestSinceEntry, current.zScore)
                if (simOptions.hasPyramiding && !pyramidAdded &&
                    current.zScore <= -simOptions.pyramidZDepth
                ) {
                    addPyramidLeg(current)
                }
                val ruleExit = when (exitMode) {
                    ZStrategyExitMode.FixedThreshold ->
                        fixedThresholdExitLong(prev.zScore, current.zScore, exit)
                    ZStrategyExitMode.ZPeakTrailing ->
                        zPeakTrailingExitLong(current.zScore, zBestSinceEntry, entry, zPeakTrailZ)
                }
                if (forceSessionExit(current) || forceReportExit(current) || ruleExit) {
                    closeLongAt(current)
                }
            }

            ZStrategyPosition.Short -> {
                zBestSinceEntry = updateZBestForShort(zBestSinceEntry, current.zScore)
                if (simOptions.hasPyramiding && !pyramidAdded &&
                    current.zScore >= simOptions.pyramidZDepth
                ) {
                    addPyramidLeg(current)
                }
                val ruleExit = when (exitMode) {
                    ZStrategyExitMode.FixedThreshold ->
                        fixedThresholdExitShort(prev.zScore, current.zScore, exit)
                    ZStrategyExitMode.ZPeakTrailing ->
                        zPeakTrailingExitShort(current.zScore, zBestSinceEntry, entry, zPeakTrailZ)
                }
                if (forceSessionExit(current) || forceReportExit(current) || ruleExit) {
                    closeShortAt(current)
                }
            }

            ZStrategyPosition.Flat -> Unit
        }
        if (position == ZStrategyPosition.Flat && !blockNewEntries(current)) {
            if (entryPullbackZ <= 0.0) {
                if (prev.zScore > -entry && current.zScore <= -entry) {
                    enterLongAtBar(current)
                } else if (prev.zScore < entry && current.zScore >= entry) {
                    enterShortAtBar(current)
                }
            } else {
                when (entryPendingArm) {
                    ZEntryPendingArm.Long -> {
                        zExtremeWhilePending = min(zExtremeWhilePending, current.zScore)
                        when {
                            zPullbackLongEntryTriggered(current.zScore, zExtremeWhilePending, entryPullbackZ) ->
                                enterLongAtBar(current)
                            zPullbackEntryCancelLong(current.zScore, exit) ->
                                entryPendingArm = ZEntryPendingArm.None
                        }
                    }
                    ZEntryPendingArm.Short -> {
                        zExtremeWhilePending = max(zExtremeWhilePending, current.zScore)
                        when {
                            zPullbackShortEntryTriggered(current.zScore, zExtremeWhilePending, entryPullbackZ) ->
                                enterShortAtBar(current)
                            zPullbackEntryCancelShort(current.zScore, exit) ->
                                entryPendingArm = ZEntryPendingArm.None
                        }
                    }
                    ZEntryPendingArm.None -> {
                        when {
                            zPullbackArmLongCross(prev.zScore, current.zScore, entry) -> {
                                entryPendingArm = ZEntryPendingArm.Long
                                zExtremeWhilePending = current.zScore
                            }
                            zPullbackArmShortCross(prev.zScore, current.zScore, entry) -> {
                                entryPendingArm = ZEntryPendingArm.Short
                                zExtremeWhilePending = current.zScore
                            }
                        }
                    }
                }
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
    val exitNote = when (exitMode) {
        ZStrategyExitMode.FixedThreshold ->
            "выход ±${String.format(Locale.US, "%.1f", exit)}"
        ZStrategyExitMode.ZPeakTrailing ->
            "выход трейл Z ${String.format(Locale.US, "%.2f", zPeakTrailZ)} от пика"
    }
    val entryNote = if (entryPullbackZ > 0.0) {
        ", вход откат Z ${String.format(Locale.US, "%.2f", entryPullbackZ)}"
    } else {
        ""
    }
    val fullDescription =
        "$periodDescription · Z-вход ±${String.format(Locale.US, "%.1f", entry)}$entryNote, $exitNote$capNote" +
            describeSimOptions(simOptions)
    val barLabels = points.drop(1).map { it.tradeDate }
    val (curveLabels, curveEquity, curveDrawdown) = equityCurveDailyForChart(barLabels, equityRubSeries)
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
        largestLossRub = largestLoss,
        equityCurveLabels = curveLabels,
        equityCurveRub = curveEquity,
        drawdownCurveRub = curveDrawdown
    )
}
