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

internal data class PortfolioConfirmedTradeTableRow(
    val tradeId: String,
    val directionLabel: String,
    val entryTimeMsk: String,
    val exitTimeMsk: String,
    val longLegTicker: String,
    val shortLegTicker: String,
    val longLegSideRu: String,
    val shortLegSideRu: String,
    val volumeText: String,
    val confirmLabel: String,
    val entryZ: Double,
    val exitZ: Double,
    val notificationIdsText: String,
    val legLongPnlSplitRubApprox: Double,
    val legShortPnlSplitRubApprox: Double,
    val grossPnlRubApprox: Double,
    val netPnlRubApprox: Double,
    val commissionRubApprox: Double = 0.0,
    val overnightRubApprox: Double = 0.0,
    val entrySignalId: String = "—",
    val entrySignalBarTimeMsk: String = "—",
    val entrySignalReceivedMsk: String = "—",
    val tradeDisplayId: String = tradeId,
)

/** Одна сделка (пара ног) и её ордера для таблицы портфеля. */
internal data class PortfolioTradeGroupRow(
    val tradeId: String,
    /** Показывается в колонке «ID сделки» (еженедельный номер + тип). */
    val tradeDisplayId: String = tradeId,
    val directionLabel: String,
    val entryTimeMsk: String,
    val exitTimeMsk: String,
    val volumeText: String,
    val confirmLabel: String,
    val entryZ: Double,
    val exitZ: Double,
    val notificationIdsText: String,
    val legLongPnlSplitRubApprox: Double,
    val legShortPnlSplitRubApprox: Double,
    val netPnlRubApprox: Double,
    val commissionRubApprox: Double = 0.0,
    val overnightRubApprox: Double = 0.0,
    val entrySignalId: String = "—",
    val entrySignalBarTimeMsk: String = "—",
    val entrySignalReceivedMsk: String = "—",
    val orders: List<PortfolioOrderTableRow>,
    val isOpen: Boolean = false
)

/** Один ордер (нога спрэда) внутри сделки. */
internal data class PortfolioOrderTableRow(
    val orderIndex: Int,
    val legRole: String,
    val ticker: String,
    val sideRu: String,
    val orderBrief: String,
    val volumeText: String
)

internal fun PortfolioConfirmedTradeTableRow.toTradeGroup(): PortfolioTradeGroupRow = PortfolioTradeGroupRow(
    tradeId = tradeId,
    tradeDisplayId = tradeDisplayId,
    directionLabel = directionLabel,
    entryTimeMsk = entryTimeMsk,
    exitTimeMsk = exitTimeMsk,
    volumeText = volumeText,
    confirmLabel = confirmLabel,
    entryZ = entryZ,
    exitZ = exitZ,
    notificationIdsText = notificationIdsText,
    legLongPnlSplitRubApprox = legLongPnlSplitRubApprox,
    legShortPnlSplitRubApprox = legShortPnlSplitRubApprox,
    netPnlRubApprox = netPnlRubApprox,
    commissionRubApprox = commissionRubApprox,
    overnightRubApprox = overnightRubApprox,
    entrySignalId = entrySignalId,
    entrySignalBarTimeMsk = entrySignalBarTimeMsk,
    entrySignalReceivedMsk = entrySignalReceivedMsk,
    orders = listOf(
        PortfolioOrderTableRow(
            orderIndex = 1,
            legRole = "Long",
            ticker = longLegTicker,
            sideRu = longLegSideRu,
            orderBrief = "—",
            volumeText = "1 лот"
        ),
        PortfolioOrderTableRow(
            orderIndex = 2,
            legRole = "Short",
            ticker = shortLegTicker,
            sideRu = shortLegSideRu,
            orderBrief = "—",
            volumeText = "1 лот"
        )
    ),
    isOpen = false
)

internal data class ExecutedPortfolioBuildResult(
    val metrics: PortfolioMetrics?,
    val tableRows: List<PortfolioConfirmedTradeTableRow>
)

private val executionTableMskFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Europe/Moscow"))

internal fun formatPortfolioExecutionTableMsk(epochMillis: Long): String =
    executionTableMskFormatter.format(Instant.ofEpochMilli(epochMillis))

internal data class SnappedSignalForExecution(
    val index: Int,
    val point: DataPoint,
    val event: StrategySignalEvent
)

internal fun snapStrategySignalEventsToExecutionPoints(
    points: List<DataPoint>,
    events: List<StrategySignalEvent>
): List<SnappedSignalForExecution> {
    if (points.isEmpty()) return emptyList()
    val rangeStart = points.first().timestampMillis
    val rangeEnd = points.last().timestampMillis
    val bucketMs = 15 * 60 * 1000L
    val maxSnapMs = bucketMs * 2
    val edgeGraceMs = 48 * 60 * 60 * 1000L
    return events
        .asSequence()
        .filter { it.timestampMillis in (rangeStart - bucketMs - edgeGraceMs)..(rangeEnd + bucketMs + edgeGraceMs) }
        .distinctBy { "${it.signalType}:${it.timestampMillis}" }
        .mapNotNull { event ->
            val idxNearest = points.indices.minByOrNull { index ->
                abs(points[index].timestampMillis - event.timestampMillis)
            } ?: return@mapNotNull null
            val diffNearest = abs(points[idxNearest].timestampMillis - event.timestampMillis)
            val idx = when {
                diffNearest <= maxSnapMs -> idxNearest
                event.timestampMillis > rangeEnd && event.timestampMillis <= rangeEnd + edgeGraceMs ->
                    points.lastIndex
                event.timestampMillis < rangeStart && event.timestampMillis >= rangeStart - edgeGraceMs ->
                    0
                else -> return@mapNotNull null
            }
            SnappedSignalForExecution(index = idx, point = points[idx], event = event)
        }
        .sortedWith(compareBy({ it.index }, { it.event.timestampMillis }))
        .toList()
}

internal data class LegSpreadDisplay(
    val longTicker: String,
    val shortTicker: String,
    val longSideRu: String,
    val shortSideRu: String
)

internal fun legSpreadDisplayForEntry(signalType: StrategySignalType): LegSpreadDisplay = when (signalType) {
    StrategySignalType.EnterLong -> LegSpreadDisplay("TATN", "TATNP", "LONG покупка", "SHORT продажа")
    StrategySignalType.EnterShort -> LegSpreadDisplay("TATNP", "TATN", "LONG покупка", "SHORT продажа")
    else -> LegSpreadDisplay("—", "—", "—", "—")
}

internal fun legSpreadDisplay(direction: ZStrategyPosition): LegSpreadDisplay = when (direction) {
    ZStrategyPosition.Long -> legSpreadDisplayForEntry(StrategySignalType.EnterLong)
    ZStrategyPosition.Short -> legSpreadDisplayForEntry(StrategySignalType.EnterShort)
    ZStrategyPosition.Flat -> LegSpreadDisplay("—", "—", "—", "—")
}

internal fun formatPushIdsForCorrelation(pushLog: List<PushNotificationLogEntry>, tag: String): String {
    val ids = pushLog
        .asSequence()
        .filter { it.posted && it.correlationTag == tag && it.notificationId != null }
        .sortedBy { it.wallTimestampMillis }
        .mapNotNull { it.notificationId }
        .distinct()
        .toList()
    return if (ids.isEmpty()) "—" else ids.joinToString(", ")
}

private fun ledgerConfirmLabel(
    ledger: List<PortfolioExecutionLedgerEntry>,
    entryType: StrategySignalType,
    entryBarTs: Long
): String {
    val src = ledger.firstOrNull { it.signalType == entryType && it.barTimestampMillis == entryBarTs }?.source
        ?: return "—"
    return when (src) {
        PortfolioExecSource.AUTO -> "авто"
        PortfolioExecSource.MANUAL -> "ручное"
    }
}

internal fun buildExecutedPortfolioWithTable(
    points: List<DataPoint>,
    events: List<StrategySignalEvent>,
    ledger: List<PortfolioExecutionLedgerEntry>,
    pushLog: List<PushNotificationLogEntry>,
    notionalRub: Double,
    leverage: Double,
    commissionPercentPerSide: Double,
    periodDescription: String
): ExecutedPortfolioBuildResult {
    if (points.size < 2) return ExecutedPortfolioBuildResult(null, emptyList())
    val orderedSnaps = snapStrategySignalEventsToExecutionPoints(points, events)
    if (orderedSnaps.isEmpty()) return ExecutedPortfolioBuildResult(null, emptyList())

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
    val tableRows = mutableListOf<PortfolioConfirmedTradeTableRow>()
    var entryContext: SnappedSignalForExecution? = null
    var tradeSeq = 0

    var eventCursor = 0
    for (index in points.indices) {
        while (eventCursor < orderedSnaps.size && orderedSnaps[eventCursor].index == index) {
            val snap = orderedSnaps[eventCursor]
            val point = snap.point
            val ev = snap.event
            when (ev.signalType) {
                StrategySignalType.EnterLong -> {
                    if (currentPosition == ZStrategyPosition.Flat) {
                        currentPosition = ZStrategyPosition.Long
                        entrySpread = point.spreadPercent
                        entryDate = point.tradeDate
                        totalCommissionRub += commissionPerSideRub
                        realizedRub -= commissionPerSideRub
                        entryContext = snap
                    }
                }

                StrategySignalType.EnterShort -> {
                    if (currentPosition == ZStrategyPosition.Flat) {
                        currentPosition = ZStrategyPosition.Short
                        entrySpread = point.spreadPercent
                        entryDate = point.tradeDate
                        totalCommissionRub += commissionPerSideRub
                        realizedRub -= commissionPerSideRub
                        entryContext = snap
                    }
                }

                StrategySignalType.ExitLong -> {
                    if (currentPosition == ZStrategyPosition.Long) {
                        val pnlSpread = point.spreadPercent - entrySpread
                        val grossPnlRub = spreadPnlToRubApprox(pnlSpread, effectiveNotionalRub)
                        val overnightRub = overnightFeePerDayRub * overnightDays(entryDate, point.tradeDate)
                        val commissionRub = 2 * commissionPerSideRub
                        val netPnlRub = grossPnlRub - commissionRub - overnightRub
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
                            commissionRubApprox = commissionRub,
                            overnightRubApprox = overnightRub,
                            pnlRubApprox = netPnlRub
                        )
                        val c = closed.last()
                        val ec = entryContext
                        if (ec != null) {
                            tradeSeq += 1
                            val legs = legSpreadDisplay(c.direction)
                            val tag = spreadLegPushCorrelationTag(ec.event.timestampMillis, ec.event.signalType)
                            val halfNet = c.pnlRubApprox / 2.0
                            val (commRub, ovnRub) = portfolioTradeCommissionAndOvernightRub(
                                notionalRub = notionalRub,
                                leverage = leverage,
                                commissionPercentPerSide = commissionPercentPerSide,
                                entryDateLabel = ec.point.tradeDate,
                                exitDateLabel = point.tradeDate,
                                includeExitCommission = true
                            )
                            val entrySig = strategySignalDisplay(ec.event, events)
                            tableRows += PortfolioConfirmedTradeTableRow(
                                tradeId = "T-%03d".format(Locale.US, tradeSeq),
                                tradeDisplayId = entrySig.tradeDisplayId,
                                directionLabel = "long",
                                entryTimeMsk = formatPortfolioExecutionTableMsk(ec.point.timestampMillis),
                                exitTimeMsk = formatPortfolioExecutionTableMsk(point.timestampMillis),
                                longLegTicker = legs.longTicker,
                                shortLegTicker = legs.shortTicker,
                                longLegSideRu = legs.longSideRu,
                                shortLegSideRu = legs.shortSideRu,
                                volumeText = "1+1 лот",
                                confirmLabel = ledgerConfirmLabel(ledger, ec.event.signalType, ec.event.timestampMillis),
                                entryZ = ec.event.zScore,
                                exitZ = ev.zScore,
                                notificationIdsText = formatPushIdsForCorrelation(pushLog, tag),
                                legLongPnlSplitRubApprox = halfNet,
                                legShortPnlSplitRubApprox = halfNet,
                                grossPnlRubApprox = c.grossPnlRubApprox,
                                netPnlRubApprox = c.pnlRubApprox,
                                commissionRubApprox = commRub,
                                overnightRubApprox = ovnRub,
                                entrySignalId = entrySig.signalId,
                                entrySignalBarTimeMsk = entrySig.barTimeMsk,
                                entrySignalReceivedMsk = entrySig.receivedTimeMsk
                            )
                        }
                        currentPosition = ZStrategyPosition.Flat
                        entryContext = null
                    }
                }

                StrategySignalType.ExitShort -> {
                    if (currentPosition == ZStrategyPosition.Short) {
                        val pnlSpread = entrySpread - point.spreadPercent
                        val grossPnlRub = spreadPnlToRubApprox(pnlSpread, effectiveNotionalRub)
                        val overnightRub = overnightFeePerDayRub * overnightDays(entryDate, point.tradeDate)
                        val commissionRub = 2 * commissionPerSideRub
                        val netPnlRub = grossPnlRub - commissionRub - overnightRub
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
                            commissionRubApprox = commissionRub,
                            overnightRubApprox = overnightRub,
                            pnlRubApprox = netPnlRub
                        )
                        val c = closed.last()
                        val ec = entryContext
                        if (ec != null) {
                            tradeSeq += 1
                            val legs = legSpreadDisplay(c.direction)
                            val tag = spreadLegPushCorrelationTag(ec.event.timestampMillis, ec.event.signalType)
                            val halfNet = c.pnlRubApprox / 2.0
                            val (commRub, ovnRub) = portfolioTradeCommissionAndOvernightRub(
                                notionalRub = notionalRub,
                                leverage = leverage,
                                commissionPercentPerSide = commissionPercentPerSide,
                                entryDateLabel = ec.point.tradeDate,
                                exitDateLabel = point.tradeDate,
                                includeExitCommission = true
                            )
                            val entrySig = strategySignalDisplay(ec.event, events)
                            tableRows += PortfolioConfirmedTradeTableRow(
                                tradeId = "T-%03d".format(Locale.US, tradeSeq),
                                tradeDisplayId = entrySig.tradeDisplayId,
                                directionLabel = "short",
                                entryTimeMsk = formatPortfolioExecutionTableMsk(ec.point.timestampMillis),
                                exitTimeMsk = formatPortfolioExecutionTableMsk(point.timestampMillis),
                                longLegTicker = legs.longTicker,
                                shortLegTicker = legs.shortTicker,
                                longLegSideRu = legs.longSideRu,
                                shortLegSideRu = legs.shortSideRu,
                                volumeText = "1+1 лот",
                                confirmLabel = ledgerConfirmLabel(ledger, ec.event.signalType, ec.event.timestampMillis),
                                entryZ = ec.event.zScore,
                                exitZ = ev.zScore,
                                notificationIdsText = formatPushIdsForCorrelation(pushLog, tag),
                                legLongPnlSplitRubApprox = halfNet,
                                legShortPnlSplitRubApprox = halfNet,
                                grossPnlRubApprox = c.grossPnlRubApprox,
                                netPnlRubApprox = c.pnlRubApprox,
                                commissionRubApprox = commRub,
                                overnightRubApprox = ovnRub,
                                entrySignalId = entrySig.signalId,
                                entrySignalBarTimeMsk = entrySig.barTimeMsk,
                                entrySignalReceivedMsk = entrySig.receivedTimeMsk
                            )
                        }
                        currentPosition = ZStrategyPosition.Flat
                        entryContext = null
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

    val metrics = PortfolioMetrics(
        periodDescription = "$periodDescription · портфель: журнал + исполнения демо (ручн./авто)",
        notionalRub = notionalRub,
        leverage = leverage,
        commissionPercentPerSide = commissionPercentPerSide,
        totalCommissionRub = totalCommissionRub,
        totalOvernightRub = totalOvernightRub +
            if (currentPosition != ZStrategyPosition.Flat) {
                overnightFeePerDayRub * overnightDays(entryDate, last.tradeDate)
            } else {
                0.0
            },
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
    return ExecutedPortfolioBuildResult(metrics, tableRows.asReversed())
}

internal fun buildExecutedPortfolioMetrics(
    points: List<DataPoint>,
    events: List<StrategySignalEvent>,
    notionalRub: Double,
    leverage: Double,
    commissionPercentPerSide: Double,
    periodDescription: String
): PortfolioMetrics? =
    buildExecutedPortfolioWithTable(
        points = points,
        events = events,
        ledger = emptyList(),
        pushLog = emptyList(),
        notionalRub = notionalRub,
        leverage = leverage,
        commissionPercentPerSide = commissionPercentPerSide,
        periodDescription = periodDescription
    ).metrics

private fun fmtRubHint(v: Double): String = String.format(Locale.US, "%+.0f ₽", v)

/**
 * Подсказка под тапом по дневному графику Z: закрытая сделка симуляции, если дата бара = дата входа или выхода;
 * на последнем баре — нереализованная оценка при открытой позиции.
 */
internal fun formatZStrategyTradeTapHint(
    selectedIndex: Int,
    points: List<DataPoint>,
    metrics: PortfolioMetrics?
): String? {
    if (metrics == null || selectedIndex !in points.indices) return null
    val dateLabel = points[selectedIndex].tradeDate
    val closed = metrics.closedTrades.firstOrNull { it.entryDate == dateLabel || it.exitDate == dateLabel }
    if (closed != null) {
        val dir = when (closed.direction) {
            ZStrategyPosition.Long -> "LONG"
            ZStrategyPosition.Short -> "SHORT"
            ZStrategyPosition.Flat -> ""
        }
        val leg = when {
            closed.entryDate == dateLabel && closed.exitDate == dateLabel -> "вход/выход"
            closed.entryDate == dateLabel -> "вход"
            else -> "выход"
        }
        return "Симуляция $dir ($leg) ${closed.entryDate}→${closed.exitDate}: чистый ${fmtRubHint(closed.pnlRubApprox)}, валовый ${fmtRubHint(closed.grossPnlRubApprox)}, спрэд ${String.format(Locale.US, "%+.2f", closed.pnlSpreadPoints)} п.п. (${"%.0f".format(Locale.US, metrics.notionalRub)} ₽ ×${String.format(Locale.US, "%.1f", metrics.leverage)}, ${String.format(Locale.US, "%.3f", metrics.commissionPercentPerSide)}% / сторона)"
    }
    val open = metrics.openPosition ?: return null
    if (selectedIndex != points.lastIndex) return null
    if (open.direction == ZStrategyPosition.Flat) return null
    val dir = when (open.direction) {
        ZStrategyPosition.Long -> "LONG"
        ZStrategyPosition.Short -> "SHORT"
        ZStrategyPosition.Flat -> ""
    }
    return "Симуляция открытая $dir с ${open.entryDate}: нереализ. ${fmtRubHint(open.unrealizedRubApprox)}, спрэд ${String.format(Locale.US, "%+.2f", open.unrealizedPnlSpread)} п.п. на $dateLabel (${"%.0f".format(Locale.US, metrics.notionalRub)} ₽ ×${String.format(Locale.US, "%.1f", metrics.leverage)})"
}
