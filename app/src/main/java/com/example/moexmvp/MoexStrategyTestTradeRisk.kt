package com.example.moexmvp

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

internal const val STRATEGY_TEST_RISK_STRONG_ENTRY_Z = 1.0
internal const val STRATEGY_TEST_RISK_OVERNIGHT_RUB = 50.0
internal const val STRATEGY_TEST_RISK_OVERNIGHT_HIGH_RUB = 100.0
internal const val STRATEGY_TEST_RISK_MIDDAY_HOUR_START = 12
internal const val STRATEGY_TEST_RISK_MIDDAY_HOUR_END = 14
internal const val STRATEGY_TEST_RISK_PEAK_HOUR = 13
private const val MS_PER_HOUR = 3_600_000L
private const val MS_PER_SIX_HOURS = 6 * MS_PER_HOUR
private const val MS_PER_DAY = 24 * MS_PER_HOUR

internal enum class StrategyTestTradeRiskLevel {
    None,
    Elevated,
    High,
    Critical,
}

internal enum class StrategyTestTradeRiskFlag(
    val label: String,
    val shortLabel: String,
) {
    VeryLongHold("> 5 сут", ">5д"),
    LongHold("> 2 сут", ">2д"),
    VeryHighOvernight("Overnight > 100 ₽", "Ovn100"),
    HighOvernight("Overnight > 50 ₽", "Ovn50"),
    WeakEntryZ("|Z| < 1.0 на входе", "Z<1"),
    PeakHourEntry("Вход 13:00 MSK", "13ч"),
    MiddayEntry("Вход 12–14 MSK", "12–14"),
    FridayLongHold("Пятница + >2 сут", "Пт>2д"),
}

internal data class TradeRiskScoreBreakdown(
    /** >2 сут (+3) или >5 сут (+4). */
    val holdPoints: Int = 0,
    /** Overnight >100 ₽ или >50 ₽ при удержании >1 сут (+2). */
    val overnightPoints: Int = 0,
    /** |Z| < 1.0 на входе при удержании >6 ч (+1). */
    val weakEntryZPoints: Int = 0,
    /** Вход 13:00 или 12–14 МСК при удержании >6 ч (+1). */
    val entryHourPoints: Int = 0,
    /** Пятница + удержание >2 сут (+1). */
    val fridayLongHoldPoints: Int = 0,
    /** |Z| у порога входа при удержании >1 сут (+1). */
    val nearThresholdPoints: Int = 0,
) {
    val totalScore: Int
        get() = holdPoints + overnightPoints + weakEntryZPoints +
            entryHourPoints + fridayLongHoldPoints + nearThresholdPoints
}

internal data class StrategyTestTradeRiskAssessment(
    val flags: List<StrategyTestTradeRiskFlag>,
    val level: StrategyTestTradeRiskLevel,
    val score: Int,
    val entryZ: Double?,
    val breakdown: TradeRiskScoreBreakdown = TradeRiskScoreBreakdown(),
)

internal data class StrategyTestTradeRiskSummary(
    val assessedCount: Int,
    val flaggedCount: Int,
    val elevatedCount: Int,
    val highCount: Int,
    val criticalCount: Int,
    val flaggedLossCount: Int,
    val flaggedWinCount: Int,
    val flaggedLossRate: Double,
    val baselineLossRate: Double,
)

internal data class TradeRiskInputs(
    val entryDateLabel: String,
    val exitDateLabel: String,
    val entryZ: Double?,
    val overnightRubApprox: Double,
    val entryThreshold: Double,
)

internal fun buildTradeRiskAssessmentFromInputs(
    inputs: TradeRiskInputs,
    zoneId: ZoneId = moexZoneId,
): StrategyTestTradeRiskAssessment {
    val durationMs = simTradeDurationMillis(inputs.entryDateLabel, inputs.exitDateLabel)
    val entryZ = inputs.entryZ
    val entryHour = entryHourMsk(inputs.entryDateLabel, zoneId)
    val flags = linkedSetOf<StrategyTestTradeRiskFlag>()
    var holdPoints = 0
    var overnightPoints = 0
    var weakEntryZPoints = 0
    var entryHourPoints = 0
    var fridayLongHoldPoints = 0
    var nearThresholdPoints = 0

    when {
        durationMs != null && durationMs > 5 * MS_PER_DAY -> {
            flags += StrategyTestTradeRiskFlag.VeryLongHold
            holdPoints = 4
        }
        durationMs != null && durationMs > 2 * MS_PER_DAY -> {
            flags += StrategyTestTradeRiskFlag.LongHold
            holdPoints = 3
        }
    }

    when {
        inputs.overnightRubApprox > STRATEGY_TEST_RISK_OVERNIGHT_HIGH_RUB -> {
            flags += StrategyTestTradeRiskFlag.VeryHighOvernight
            overnightPoints = 2
        }
        inputs.overnightRubApprox > STRATEGY_TEST_RISK_OVERNIGHT_RUB &&
            durationMs != null &&
            durationMs > MS_PER_DAY -> {
            flags += StrategyTestTradeRiskFlag.HighOvernight
            overnightPoints = 2
        }
    }

    if (
        entryZ != null &&
        abs(entryZ) < STRATEGY_TEST_RISK_STRONG_ENTRY_Z &&
        durationMs != null &&
        durationMs > MS_PER_SIX_HOURS
    ) {
        flags += StrategyTestTradeRiskFlag.WeakEntryZ
        weakEntryZPoints = 1
    }

    if (durationMs != null && durationMs > MS_PER_SIX_HOURS) {
        when (entryHour) {
            STRATEGY_TEST_RISK_PEAK_HOUR -> {
                flags += StrategyTestTradeRiskFlag.PeakHourEntry
                entryHourPoints = 1
            }
            in STRATEGY_TEST_RISK_MIDDAY_HOUR_START..STRATEGY_TEST_RISK_MIDDAY_HOUR_END -> {
                flags += StrategyTestTradeRiskFlag.MiddayEntry
                entryHourPoints = 1
            }
        }
    }

    if (
        durationMs != null &&
        durationMs > 2 * MS_PER_DAY &&
        isFridayEntry(inputs.entryDateLabel, zoneId)
    ) {
        flags += StrategyTestTradeRiskFlag.FridayLongHold
        fridayLongHoldPoints = 1
    }

    if (
        entryZ != null &&
        abs(entryZ) < inputs.entryThreshold + 0.05 &&
        durationMs != null &&
        durationMs > MS_PER_DAY
    ) {
        nearThresholdPoints = 1
    }

    val breakdown = TradeRiskScoreBreakdown(
        holdPoints = holdPoints,
        overnightPoints = overnightPoints,
        weakEntryZPoints = weakEntryZPoints,
        entryHourPoints = entryHourPoints,
        fridayLongHoldPoints = fridayLongHoldPoints,
        nearThresholdPoints = nearThresholdPoints,
    )
    val score = breakdown.totalScore

    return StrategyTestTradeRiskAssessment(
        flags = flags.toList(),
        level = strategyTestTradeRiskLevelFromScore(score),
        score = score,
        entryZ = entryZ,
        breakdown = breakdown,
    )
}

internal fun buildStrategyTestTradeRiskAssessment(
    trade: PortfolioClosedTrade,
    m15Points: List<DataPoint>,
    entryThreshold: Double = 0.8,
    zoneId: ZoneId = moexZoneId,
    barIndexByLabel: Map<String, Int> = buildM15BarIndexByLabel(m15Points),
): StrategyTestTradeRiskAssessment {
    val entryZ = lookupEntryZ(trade, m15Points, barIndexByLabel)
    return buildTradeRiskAssessmentFromInputs(
        TradeRiskInputs(
            entryDateLabel = trade.entryDate,
            exitDateLabel = trade.exitDate,
            entryZ = entryZ,
            overnightRubApprox = trade.overnightRubApprox,
            entryThreshold = entryThreshold,
        ),
        zoneId = zoneId,
    )
}

internal fun buildPortfolioTradeGroupRiskAssessment(
    group: PortfolioTradeGroupRow,
    entryThreshold: Double,
    zoneId: ZoneId = moexZoneId,
    nowMillis: Long = System.currentTimeMillis(),
): StrategyTestTradeRiskAssessment {
    val exitLabel = if (group.isOpen) {
        formatPortfolioTradeRiskNowLabel(nowMillis, zoneId)
    } else {
        group.exitTimeMsk
    }
    val entryZ = group.entryZ.takeUnless { it.isNaN() }
    return buildTradeRiskAssessmentFromInputs(
        TradeRiskInputs(
            entryDateLabel = group.entryTimeMsk,
            exitDateLabel = exitLabel,
            entryZ = entryZ,
            overnightRubApprox = group.overnightRubApprox,
            entryThreshold = entryThreshold,
        ),
        zoneId = zoneId,
    )
}

internal fun buildPortfolioTradeGroupRiskAssessments(
    groups: List<PortfolioTradeGroupRow>,
    entryThreshold: Double,
    zoneId: ZoneId = moexZoneId,
    nowMillis: Long = System.currentTimeMillis(),
): List<StrategyTestTradeRiskAssessment> =
    groups.map { group ->
        buildPortfolioTradeGroupRiskAssessment(
            group = group,
            entryThreshold = entryThreshold,
            zoneId = zoneId,
            nowMillis = nowMillis,
        )
    }

private fun formatPortfolioTradeRiskNowLabel(nowMillis: Long, zoneId: ZoneId): String =
    Instant.ofEpochMilli(nowMillis)
        .atZone(zoneId)
        .toLocalDateTime()
        .format(portfolio15mLabelFormatter)

internal fun buildStrategyTestTradeRiskAssessments(
    trades: List<PortfolioClosedTrade>,
    m15Points: List<DataPoint>,
    entryThreshold: Double = 0.8,
    zoneId: ZoneId = moexZoneId,
    barIndexByLabel: Map<String, Int> = buildM15BarIndexByLabel(m15Points),
): List<StrategyTestTradeRiskAssessment> =
    trades.map { trade ->
        buildStrategyTestTradeRiskAssessment(
            trade = trade,
            m15Points = m15Points,
            entryThreshold = entryThreshold,
            zoneId = zoneId,
            barIndexByLabel = barIndexByLabel,
        )
    }

/** Оценки в том же порядке, что [buildStrategyTestTradeListFromSimulation] (новые сверху). */
internal fun buildStrategyTestTradeRiskAssessmentsForItems(
    tradeItems: List<StrategyTestTradeItem>,
    m15Points: List<DataPoint>,
    entryThreshold: Double = 0.8,
    zoneId: ZoneId = moexZoneId,
): List<StrategyTestTradeRiskAssessment> {
    if (tradeItems.isEmpty()) return emptyList()
    val barIndex = buildM15BarIndexByLabel(m15Points)
    return tradeItems.map { item ->
        buildStrategyTestTradeRiskAssessment(
            trade = item.trade,
            m15Points = m15Points,
            entryThreshold = entryThreshold,
            zoneId = zoneId,
            barIndexByLabel = barIndex,
        )
    }
}

internal fun strategyTestTradeRiskIsFlagged(assessment: StrategyTestTradeRiskAssessment): Boolean =
    assessment.level >= StrategyTestTradeRiskLevel.Elevated

/** Значок слева от ID в таблице открытых сделок. */
internal fun shouldShowOpenTradeRiskIcon(
    group: PortfolioTradeGroupRow,
    assessment: StrategyTestTradeRiskAssessment?,
): Boolean =
    group.isOpen &&
        assessment != null &&
        strategyTestTradeRiskIsFlagged(assessment)

internal const val OPEN_TRADE_RISK_ICON_COL_WIDTH_DP = 20

internal fun buildStrategyTestTradeRiskSummary(
    trades: List<PortfolioClosedTrade>,
    assessments: List<StrategyTestTradeRiskAssessment>,
): StrategyTestTradeRiskSummary? {
    if (trades.isEmpty() || assessments.size != trades.size) return null
    val flagged = assessments.mapIndexedNotNull { index, assessment ->
        if (!strategyTestTradeRiskIsFlagged(assessment)) null else index
    }
    val baselineLoss = trades.count { it.pnlRubApprox < 0 }
    val flaggedLoss = flagged.count { trades[it].pnlRubApprox < 0 }
    val flaggedWin = flagged.size - flaggedLoss
    return StrategyTestTradeRiskSummary(
        assessedCount = trades.size,
        flaggedCount = flagged.size,
        elevatedCount = assessments.count { it.level == StrategyTestTradeRiskLevel.Elevated },
        highCount = assessments.count { it.level == StrategyTestTradeRiskLevel.High },
        criticalCount = assessments.count { it.level == StrategyTestTradeRiskLevel.Critical },
        flaggedLossCount = flaggedLoss,
        flaggedWinCount = flaggedWin,
        flaggedLossRate = if (flagged.isEmpty()) 0.0 else flaggedLoss * 100.0 / flagged.size,
        baselineLossRate = baselineLoss * 100.0 / trades.size,
    )
}

internal fun formatStrategyTestTradeRiskFlags(assessment: StrategyTestTradeRiskAssessment): String =
    if (!strategyTestTradeRiskIsFlagged(assessment) || assessment.flags.isEmpty()) {
        "—"
    } else {
        assessment.flags.joinToString(" · ") { it.shortLabel }
    }

internal fun formatTradeRiskScorePoints(points: Int): String = points.toString()

internal fun formatPortfolioTradeRiskTotalScore(assessment: StrategyTestTradeRiskAssessment): String =
    assessment.breakdown.totalScore.toString()

internal fun portfolioTradeRiskBreakdownColumnTitles(): List<String> = listOf(
    ">2д",
    "Ovn",
    "Z<1",
    "Час",
    "Пт",
    "Z~п",
)

internal fun portfolioTradeRiskBreakdownPointValues(
    assessment: StrategyTestTradeRiskAssessment,
): List<Int> {
    val b = assessment.breakdown
    return listOf(
        b.holdPoints,
        b.overnightPoints,
        b.weakEntryZPoints,
        b.entryHourPoints,
        b.fridayLongHoldPoints,
        b.nearThresholdPoints,
    )
}

internal fun strategyTestTradeRiskLevelLabel(level: StrategyTestTradeRiskLevel): String = when (level) {
    StrategyTestTradeRiskLevel.None -> "нет"
    StrategyTestTradeRiskLevel.Elevated -> "умеренный"
    StrategyTestTradeRiskLevel.High -> "высокий"
    StrategyTestTradeRiskLevel.Critical -> "критический"
}

private fun strategyTestTradeRiskLevelFromScore(score: Int): StrategyTestTradeRiskLevel = when {
    score >= 6 -> StrategyTestTradeRiskLevel.Critical
    score >= 4 -> StrategyTestTradeRiskLevel.High
    score >= 3 -> StrategyTestTradeRiskLevel.Elevated
    else -> StrategyTestTradeRiskLevel.None
}

private fun lookupEntryZ(
    trade: PortfolioClosedTrade,
    m15Points: List<DataPoint>,
    barIndexByLabel: Map<String, Int>,
): Double? {
    if (m15Points.isEmpty()) return null
    val idx = barIndexByLabel[trade.entryDate]
        ?: indexForTradeDateLabel(m15Points, trade.entryDate)
        ?: return null
    return m15Points[idx].zScore
}

private fun entryHourMsk(entryDate: String, zoneId: ZoneId): Int? {
    parseBarLocalTimeMsk(entryDate)?.hour?.let { return it }
    val ms = parseSimTradeExitMillis(entryDate) ?: return null
    return Instant.ofEpochMilli(ms).atZone(zoneId).hour
}

private fun isFridayEntry(entryDate: String, zoneId: ZoneId): Boolean {
    parseBarLocalDateMsk(entryDate)?.dayOfWeek?.let { return it.value == 5 }
    val ms = parseSimTradeExitMillis(entryDate) ?: return false
    return Instant.ofEpochMilli(ms).atZone(zoneId).dayOfWeek.value == 5
}

internal fun formatStrategyTestTradeRiskSummarySubtitle(summary: StrategyTestTradeRiskSummary): String =
    String.format(
        Locale.US,
        "риск ≥3 балла: %d · крит. %d · убытков %.0f%% (база %.0f%%)",
        summary.flaggedCount,
        summary.criticalCount,
        summary.flaggedLossRate,
        summary.baselineLossRate,
    )
