package com.example.moexmvp

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Глубина списка сделок на вкладке «Портфель» (календарные дни, МСК). */
internal fun portfolioTradesWindowStartMillis(
    lookbackDays: Long,
    zone: ZoneId = ZoneId.of("Europe/Moscow"),
    nowMillis: Long = System.currentTimeMillis(),
): Long =
    Instant.ofEpochMilli(nowMillis)
        .atZone(zone)
        .toLocalDate()
        .minusDays((normalizePortfolioLookbackDays(lookbackDays) - 1).coerceAtLeast(0))
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

private val portfolioTableMskParseFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

internal fun parsePortfolioExecutionTableMsk(label: String): Long? {
    if (label.isBlank() || label == "—") return null
    return runCatching {
        LocalDateTime.parse(label.trim(), portfolioTableMskParseFormatter)
            .atZone(ZoneId.of("Europe/Moscow"))
            .toInstant()
            .toEpochMilli()
    }.getOrNull()
}

internal fun filterSandboxExecutionsInWindow(
    executions: List<SandboxSpreadExecUi>,
    lookbackDays: Long,
    windowStartMillis: Long = portfolioTradesWindowStartMillis(lookbackDays),
): List<SandboxSpreadExecUi> =
    executions.filter { it.executedAtMillis >= windowStartMillis }

internal fun filterClosedTradeRowsInWindow(
    rows: List<PortfolioConfirmedTradeTableRow>,
    lookbackDays: Long,
    windowStartMillis: Long = portfolioTradesWindowStartMillis(lookbackDays),
): List<PortfolioConfirmedTradeTableRow> =
    rows.filter { row ->
        val exitMs = parsePortfolioExecutionTableMsk(row.exitTimeMsk) ?: return@filter false
        exitMs >= windowStartMillis
    }

/** Парсинг даты/времени выхода в симуляции (15м метка или только день). */
internal fun parseSimTradeExitMillis(label: String): Long? {
    if (label.isBlank() || label == "—") return null
    parsePortfolioExecutionTableMsk(label)?.let { return it }
    if (label.length >= 10) {
        return runCatching {
            java.time.LocalDate.parse(label.trim().take(10))
                .atStartOfDay(ZoneId.of("Europe/Moscow"))
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }
    return null
}

/** Длительность сделки в миллисекундах (вход → выход); null если даты не разобрать. */
internal fun simTradeDurationMillis(entryDate: String, exitDate: String): Long? {
    val entryMs = parseSimTradeExitMillis(entryDate) ?: return null
    val exitMs = parseSimTradeExitMillis(exitDate) ?: return null
    val diffMs = exitMs - entryMs
    if (diffMs < 0) return null
    return diffMs
}

/** true, если длительность строго меньше суток (24 ч). */
internal fun isSimTradeDurationUnderDay(entryDate: String, exitDate: String): Boolean {
    val diffMs = simTradeDurationMillis(entryDate, exitDate) ?: return false
    return diffMs < 24 * 60 * 60_000L
}

/** true, если длительность не меньше суток (24 ч), включая ровно 1 день. */
internal fun isSimTradeDurationOverDay(entryDate: String, exitDate: String): Boolean {
    val diffMs = simTradeDurationMillis(entryDate, exitDate) ?: return false
    return diffMs >= 24 * 60 * 60_000L
}

internal enum class SimTradeDurationTone { Neutral, Short, Long }

internal fun simTradeDurationTone(entryDate: String, exitDate: String): SimTradeDurationTone = when {
    isSimTradeDurationUnderDay(entryDate, exitDate) -> SimTradeDurationTone.Short
    isSimTradeDurationOverDay(entryDate, exitDate) -> SimTradeDurationTone.Long
    else -> SimTradeDurationTone.Neutral
}

/** true, если выход на следующий календарный день после входа (закрытие «во 2-й день», МСК). */
internal fun isSimTradeClosedOnSecondCalendarDay(entryDate: String, exitDate: String): Boolean {
    val entry = parseChartDateLabel(entryDate) ?: return false
    val exit = parseChartDateLabel(exitDate) ?: return false
    return java.time.temporal.ChronoUnit.DAYS.between(entry, exit) == 1L
}

/** Человекочитаемая длительность сделки (вход → выход) для списка «Тест страт.». */
internal fun formatSimTradeDurationLabel(entryDate: String, exitDate: String): String {
    val diffMs = simTradeDurationMillis(entryDate, exitDate) ?: return "—"
    if (diffMs == 0L) return "0 мин"
    val totalMinutes = diffMs / 60_000L
    if (totalMinutes == 0L) return "< 1 мин"
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> buildString {
            append("$days дн.")
            if (hours > 0) append(" $hours ч")
        }
        hours > 0 -> if (minutes > 0) "$hours ч $minutes мин" else "$hours ч"
        else -> "$minutes мин"
    }
}

internal fun filterSimClosedTradesInWindow(
    trades: List<PortfolioClosedTrade>,
    lookbackDays: Long,
    windowStartMillis: Long = portfolioTradesWindowStartMillis(lookbackDays),
): List<PortfolioClosedTrade> =
    trades.filter { t ->
        val exitMs = parseSimTradeExitMillis(t.exitDate) ?: return@filter false
        exitMs >= windowStartMillis
    }

internal fun sumTradeGroupsNetPnl(groups: List<PortfolioTradeGroupRow>): Double =
    groups.sumOf { g -> if (g.netPnlRubApprox.isNaN()) 0.0 else g.netPnlRubApprox }

internal data class PortfolioTradesBucketUi(
    val title: String,
    val tradeCount: Int,
    val totalPnlRub: Double,
    val groups: List<PortfolioTradeGroupRow>,
    val isOpenTrades: Boolean,
    /** Подпись источника PnL, напр. «Tinkoff». */
    val pnlSourceLabel: String? = null,
)

/** Фильтр закрытых сделок в блоке «Закрытые». */
internal fun filterClosedRowsForTradesTable(
    rows: List<PortfolioConfirmedTradeTableRow>,
    filter: PortfolioClosedTradesSourceFilter,
): List<PortfolioConfirmedTradeTableRow> =
    filterClosedTradesBySourceFilter(rows, filter)

internal fun buildPortfolioTradesBuckets(
    openExecutions: List<SandboxSpreadExecUi>,
    closedRows: List<PortfolioConfirmedTradeTableRow>,
    lookbackDays: Long,
    windowStartMillis: Long = portfolioTradesWindowStartMillis(lookbackDays),
    closedSourceFilter: PortfolioClosedTradesSourceFilter = PortfolioClosedTradesSourceFilter.All,
    closedPnlOverrideRub: Double? = null,
    closedPnlSourceLabel: String? = null,
    closedTradeCountOverride: Int? = null,
    openPnlSourceLabel: String? = null,
): Pair<PortfolioTradesBucketUi, PortfolioTradesBucketUi> {
    val openSingle = resolveSingleOpenExecutionForDisplay(openExecutions)?.let { listOf(it) } ?: emptyList()
    val closedInWindow = filterClosedTradeRowsInWindow(closedRows, lookbackDays, windowStartMillis)
    val closedFiltered = filterClosedRowsForTradesTable(closedInWindow, closedSourceFilter)
    val openGroups = openSingle.map { it.toTradeGroup() }
    val closedGroups = closedFiltered.map { it.toTradeGroup() }
    return PortfolioTradesBucketUi(
        title = "Открытая",
        tradeCount = openGroups.size,
        totalPnlRub = sumTradeGroupsNetPnl(openGroups),
        groups = openGroups,
        isOpenTrades = true,
        pnlSourceLabel = openPnlSourceLabel,
    ) to PortfolioTradesBucketUi(
        title = "Закрытые",
        tradeCount = when {
            closedSourceFilter != PortfolioClosedTradesSourceFilter.All -> closedGroups.size
            closedTradeCountOverride != null -> closedTradeCountOverride
            else -> closedGroups.size
        },
        totalPnlRub = when {
            closedSourceFilter == PortfolioClosedTradesSourceFilter.All &&
                closedPnlOverrideRub != null -> closedPnlOverrideRub
            else -> sumTradeGroupsNetPnl(closedGroups)
        },
        groups = closedGroups,
        isOpenTrades = false,
        pnlSourceLabel = closedPnlSourceLabel,
    )
}

/** Стратегия — не более одной открытой сделки; для UI берём последнюю по времени входа. */
internal fun resolveSingleOpenExecutionForDisplay(
    executions: List<SandboxSpreadExecUi>,
): SandboxSpreadExecUi? = executions.maxByOrNull { it.executedAtMillis }
