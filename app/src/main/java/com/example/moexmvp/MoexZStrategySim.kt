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
    simOptions: ZStrategySimOptions = ZStrategySimOptions(),
    simLoopStartIndex: Int = 1,
    initialCarryOpen: PortfolioOpenPosition? = null,
    todaySliceOnly: Boolean = false,
    prodLikeSizing: ZStrategyProdLikeSizing? = null,
): PortfolioMetrics? {
    if (points.size < 2) return null
    val loopStart = simLoopStartIndex.coerceIn(1, points.lastIndex)
    if (loopStart > points.lastIndex) return null

    val entry = thresholds.entry
    val exit = thresholds.exit
    val slip = max(0.0, simOptions.slippageSpreadPts)

    var position = ZStrategyPosition.Flat
    var entrySpread = 0.0
    var entryDate = ""
    var realizedSpread = 0.0
    var realizedRub = 0.0
    var totalCommissionRub = 0.0
    var totalOvernightRub = 0.0
    val closed = mutableListOf<PortfolioClosedTrade>()
    val equityRubSeries = mutableListOf<Double>()

    if (todaySliceOnly && loopStart > 1) {
        realizedSpread = 0.0
        realizedRub = 0.0
        totalCommissionRub = 0.0
        totalOvernightRub = 0.0
    }

    var positionNotionalRub = notionalRub
    var tradeEffNotionalRub = if (prodLikeSizing != null) notionalRub else notionalRub * leverage
    var tradeCommissionPerSideRub = tradeEffNotionalRub * (commissionPercentPerSide / 100.0)
    var tradeOvernightFeePerDayRub = if (prodLikeSizing != null) {
        0.0
    } else {
        notionalRub * (leverage - 1.0).coerceAtLeast(0.0) * (TINKOFF_OVERNIGHT_FEE_PERCENT_PER_DAY / 100.0)
    }

    fun applyProdLikeSizingAtBar(bar: DataPoint) {
        val prod = prodLikeSizing ?: return
        val cashBase = if (compoundReturns) {
            kotlin.math.max(prod.accountSizeRub, notionalRub + realizedRub)
        } else {
            prod.accountSizeRub
        }
        positionNotionalRub = strategyTestPairNotionalRub(
            bar = bar,
            accountSizeRub = cashBase,
            capitalUsagePercent = prod.capitalUsagePercent,
            leverageForLots = prod.leverageForLots,
        )
        tradeEffNotionalRub = positionNotionalRub
        tradeCommissionPerSideRub = positionNotionalRub * (commissionPercentPerSide / 100.0)
        tradeOvernightFeePerDayRub = 0.0
    }

    fun refreshTradeFeesFromPositionNotional() {
        if (prodLikeSizing != null) return
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
    var entryZScore = 0.0
    var peakMtmNetRub = 0.0
    var zBestSinceEntry = 0.0
    var entryPendingArm = ZEntryPendingArm.None
    var zExtremeWhilePending = 0.0
    var pyramidAdded = false
    var tradingHalted = false
    var peakEquityRub = 0.0

    initialCarryOpen?.let { carry ->
        position = carry.direction
        entrySpread = carry.entrySpreadPercent
        entryDate = carry.entryDate
        refreshTradeFeesFromPositionNotional()
        entryCommissionRub = tradeCommissionPerSideRub
        val entryIdx = points.indexOfFirst { it.tradeDate == carry.entryDate }
            .takeIf { it >= 0 } ?: (loopStart - 1).coerceAtLeast(0)
        for (scanIdx in entryIdx until loopStart) {
            zBestSinceEntry = when (position) {
                ZStrategyPosition.Long -> updateZBestForLong(zBestSinceEntry, points[scanIdx].zScore)
                ZStrategyPosition.Short -> updateZBestForShort(zBestSinceEntry, points[scanIdx].zScore)
                ZStrategyPosition.Flat -> 0.0
            }
        }
    }

    fun resetFlatState() {
        entryCommissionRub = 0.0
        entryZScore = 0.0
        peakMtmNetRub = 0.0
        zBestSinceEntry = 0.0
        entryPendingArm = ZEntryPendingArm.None
        pyramidAdded = false
    }

    fun netMtmIfClosedNow(bar: DataPoint): Double {
        if (position == ZStrategyPosition.Flat) return 0.0
        val mtmSpread = when (position) {
            ZStrategyPosition.Long -> bar.spreadPercent - entrySpread
            ZStrategyPosition.Short -> entrySpread - bar.spreadPercent
            ZStrategyPosition.Flat -> 0.0
        }
        val grossRub = spreadPnlToRubApprox(mtmSpread, tradeEffNotionalRub)
        val overnightRub = tradeOvernightFeePerDayRub * overnightDays(entryDate, bar.tradeDate)
        return grossRub - entryCommissionRub - tradeCommissionPerSideRub - overnightRub
    }

    fun forcedExitHit(bar: DataPoint): Boolean {
        if (position == ZStrategyPosition.Flat) return false
        val durationMs = simTradeDurationMillis(entryDate, bar.tradeDate) ?: return false
        peakMtmNetRub = max(peakMtmNetRub, netMtmIfClosedNow(bar))
        if (simOptions.forcedTimeStopHours > 0.0 &&
            durationMs >= (simOptions.forcedTimeStopHours * 3_600_000.0).toLong()
        ) {
            return true
        }
        if (simOptions.forcedZStopDeviation > 0.0) {
            when (position) {
                ZStrategyPosition.Long ->
                    if (bar.zScore > entryZScore + simOptions.forcedZStopDeviation) return true
                ZStrategyPosition.Short ->
                    if (bar.zScore < entryZScore - simOptions.forcedZStopDeviation) return true
                ZStrategyPosition.Flat -> Unit
            }
        }
        if (simOptions.forcedHoldHoursIfLosing > 0.0 &&
            durationMs >= (simOptions.forcedHoldHoursIfLosing * 3_600_000.0).toLong()
        ) {
            val netRub = netMtmIfClosedNow(bar)
            if (netRub < 0.0 &&
                (!simOptions.forcedHoldRequireNeverGreen || peakMtmNetRub <= 0.0)
            ) {
                return true
            }
        }
        return false
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

    fun spreadOk(sp: Double): Boolean =
        zSimSpreadOk(sp, simOptions.minSpreadPct, simOptions.maxSpreadPct)

    fun entryZOk(z: Double, direction: ZStrategyPosition): Boolean =
        zSimEntryZOk(z, direction, entry, simOptions.entryZBuffer)

    fun stopLossHit(bar: DataPoint): Boolean =
        zSimStopLossHit(
            position = position,
            entrySpread = entrySpread,
            barSpreadPercent = bar.spreadPercent,
            effectiveNotionalRub = tradeEffNotionalRub,
            maxLossSpreadPts = simOptions.maxLossSpreadPts,
            maxLossRub = simOptions.maxLossRub,
            exitCommissionRub = tradeCommissionPerSideRub,
            overnightPerDayRub = tradeOvernightFeePerDayRub,
            entryDate = entryDate,
            barTradeDate = bar.tradeDate,
        )

    fun checkTradingHalt(equityRub: Double) {
        if (tradingHalted) return
        peakEquityRub = max(peakEquityRub, equityRub)
        val drawdownRub = peakEquityRub - equityRub
        if (simOptions.maxDrawdownHaltRub > 0 && drawdownRub >= simOptions.maxDrawdownHaltRub) {
            tradingHalted = true
            return
        }
        if (simOptions.maxDrawdownHaltPct > 0 && peakEquityRub > 0) {
            val drawdownPct = drawdownRub / abs(peakEquityRub) * 100.0
            if (drawdownPct >= simOptions.maxDrawdownHaltPct) {
                tradingHalted = true
            }
        }
    }

    fun equityRubAt(bar: DataPoint): Double {
        val mtmSpread = when (position) {
            ZStrategyPosition.Flat -> 0.0
            ZStrategyPosition.Long -> bar.spreadPercent - entrySpread
            ZStrategyPosition.Short -> entrySpread - bar.spreadPercent
        }
        return realizedRub + spreadPnlToRubApprox(mtmSpread, tradeEffNotionalRub)
    }

    fun closeLongAt(current: DataPoint) {
        val exitSpread = zSimExitSpread(current.spreadPercent, ZStrategyPosition.Long, slip)
        val pnl = exitSpread - entrySpread
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
            exitSpreadPercent = exitSpread,
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
        val exitSpread = zSimExitSpread(current.spreadPercent, ZStrategyPosition.Short, slip)
        val pnl = entrySpread - exitSpread
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
            exitSpreadPercent = exitSpread,
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
        applyProdLikeSizingAtBar(bar)
        position = ZStrategyPosition.Long
        entrySpread = zSimEntrySpread(bar.spreadPercent, ZStrategyPosition.Long, slip)
        entryDate = bar.tradeDate
        entryZScore = bar.zScore
        entryCommissionRub = tradeCommissionPerSideRub
        peakMtmNetRub = netMtmIfClosedNow(bar)
        zBestSinceEntry = bar.zScore
        totalCommissionRub += tradeCommissionPerSideRub
        realizedRub -= tradeCommissionPerSideRub
        entryPendingArm = ZEntryPendingArm.None
        pyramidAdded = false
    }

    fun enterShortAtBar(bar: DataPoint) {
        applyTradeSizingFromRealized()
        applyProdLikeSizingAtBar(bar)
        position = ZStrategyPosition.Short
        entrySpread = zSimEntrySpread(bar.spreadPercent, ZStrategyPosition.Short, slip)
        entryDate = bar.tradeDate
        entryZScore = bar.zScore
        entryCommissionRub = tradeCommissionPerSideRub
        peakMtmNetRub = netMtmIfClosedNow(bar)
        zBestSinceEntry = bar.zScore
        totalCommissionRub += tradeCommissionPerSideRub
        realizedRub -= tradeCommissionPerSideRub
        entryPendingArm = ZEntryPendingArm.None
        pyramidAdded = false
    }

    for (index in loopStart until points.size) {
        val prev = points[index - 1]
        val current = points[index]
        var closedThisBar = false
        when (position) {
            ZStrategyPosition.Long -> {
                zBestSinceEntry = updateZBestForLong(zBestSinceEntry, current.zScore)
                if (simOptions.hasPyramiding && !pyramidAdded &&
                    current.zScore <= -simOptions.pyramidZDepth
                ) {
                    addPyramidLeg(current)
                }
                when {
                    stopLossHit(current) -> {
                        closeLongAt(current)
                        closedThisBar = true
                    }
                    forceSessionExit(current) || forceReportExit(current) -> {
                        closeLongAt(current)
                        closedThisBar = true
                    }
                    forcedExitHit(current) -> {
                        closeLongAt(current)
                        closedThisBar = true
                    }
                    else -> {
                        val ruleExit = when (exitMode) {
                            ZStrategyExitMode.FixedThreshold ->
                                determineZStrategySignalBetweenBars(
                                    prev,
                                    current,
                                    ZStrategyPosition.Long,
                                    thresholds,
                                ) == ZStrategySignal.ExitLong
                            ZStrategyExitMode.ZPeakTrailing ->
                                zPeakTrailingExitLong(current.zScore, zBestSinceEntry, entry, zPeakTrailZ)
                        }
                        if (ruleExit) {
                            closeLongAt(current)
                            closedThisBar = true
                        }
                    }
                }
            }

            ZStrategyPosition.Short -> {
                zBestSinceEntry = updateZBestForShort(zBestSinceEntry, current.zScore)
                if (simOptions.hasPyramiding && !pyramidAdded &&
                    current.zScore >= simOptions.pyramidZDepth
                ) {
                    addPyramidLeg(current)
                }
                when {
                    stopLossHit(current) -> {
                        closeShortAt(current)
                        closedThisBar = true
                    }
                    forceSessionExit(current) || forceReportExit(current) -> {
                        closeShortAt(current)
                        closedThisBar = true
                    }
                    forcedExitHit(current) -> {
                        closeShortAt(current)
                        closedThisBar = true
                    }
                    else -> {
                        val ruleExit = when (exitMode) {
                            ZStrategyExitMode.FixedThreshold ->
                                determineZStrategySignalBetweenBars(
                                    prev,
                                    current,
                                    ZStrategyPosition.Short,
                                    thresholds,
                                ) == ZStrategySignal.ExitShort
                            ZStrategyExitMode.ZPeakTrailing ->
                                zPeakTrailingExitShort(current.zScore, zBestSinceEntry, entry, zPeakTrailZ)
                        }
                        if (ruleExit) {
                            closeShortAt(current)
                            closedThisBar = true
                        }
                    }
                }
            }

            ZStrategyPosition.Flat -> Unit
        }
        // Live-монитор: максимум одно действие на бар — без входа в тот же бар после выхода.
        if (!closedThisBar && position == ZStrategyPosition.Flat && !tradingHalted && !blockNewEntries(current)) {
            if (entryPullbackZ <= 0.0) {
                when (
                    determineZStrategySignalBetweenBars(
                        prev,
                        current,
                        ZStrategyPosition.Flat,
                        thresholds,
                    )
                ) {
                    ZStrategySignal.EnterLong ->
                        if (spreadOk(current.spreadPercent) && entryZOk(current.zScore, ZStrategyPosition.Long)) {
                            enterLongAtBar(current)
                        }
                    ZStrategySignal.EnterShort ->
                        if (spreadOk(current.spreadPercent) && entryZOk(current.zScore, ZStrategyPosition.Short)) {
                            enterShortAtBar(current)
                        }
                    else -> Unit
                }
            } else {
                when (entryPendingArm) {
                    ZEntryPendingArm.Long -> {
                        zExtremeWhilePending = min(zExtremeWhilePending, current.zScore)
                        when {
                            zPullbackLongEntryTriggered(current.zScore, zExtremeWhilePending, entryPullbackZ) &&
                                spreadOk(current.spreadPercent) &&
                                entryZOk(current.zScore, ZStrategyPosition.Long) ->
                                enterLongAtBar(current)
                            zPullbackEntryCancelLong(current.zScore, exit) ->
                                entryPendingArm = ZEntryPendingArm.None
                        }
                    }
                    ZEntryPendingArm.Short -> {
                        zExtremeWhilePending = max(zExtremeWhilePending, current.zScore)
                        when {
                            zPullbackShortEntryTriggered(current.zScore, zExtremeWhilePending, entryPullbackZ) &&
                                spreadOk(current.spreadPercent) &&
                                entryZOk(current.zScore, ZStrategyPosition.Short) ->
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
        val equityRub = equityRubAt(current)
        checkTradingHalt(equityRub)
        equityRubSeries += equityRub
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
    val barLabels = points.drop(loopStart).map { it.tradeDate }
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
