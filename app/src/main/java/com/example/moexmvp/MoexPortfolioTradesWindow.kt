package com.example.moexmvp

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Глубина списка сделок на вкладке «Портфель» (календарные дни, МСК). */
internal const val PORTFOLIO_TRADES_WINDOW_DAYS = 3L

private val portfolioTableMskParseFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

internal fun portfolioTradesWindowStartMillis(
    zone: ZoneId = ZoneId.of("Europe/Moscow"),
    nowMillis: Long = System.currentTimeMillis()
): Long =
    Instant.ofEpochMilli(nowMillis)
        .atZone(zone)
        .toLocalDate()
        .minusDays(PORTFOLIO_TRADES_WINDOW_DAYS)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

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
    windowStartMillis: Long = portfolioTradesWindowStartMillis()
): List<SandboxSpreadExecUi> =
    executions.filter { it.executedAtMillis >= windowStartMillis }

internal fun filterClosedTradeRowsInWindow(
    rows: List<PortfolioConfirmedTradeTableRow>,
    windowStartMillis: Long = portfolioTradesWindowStartMillis()
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

internal fun filterSimClosedTradesInWindow(
    trades: List<PortfolioClosedTrade>,
    windowStartMillis: Long = portfolioTradesWindowStartMillis()
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
    val isOpenTrades: Boolean
)

internal fun buildPortfolioTradesBuckets(
    openExecutions: List<SandboxSpreadExecUi>,
    closedRows: List<PortfolioConfirmedTradeTableRow>,
    windowStartMillis: Long = portfolioTradesWindowStartMillis()
): Pair<PortfolioTradesBucketUi, PortfolioTradesBucketUi> {
    val openFiltered = filterSandboxExecutionsInWindow(openExecutions, windowStartMillis)
    val closedFiltered = filterClosedTradeRowsInWindow(closedRows, windowStartMillis)
    val openGroups = openFiltered.asReversed().map { it.toTradeGroup() }
    val closedGroups = closedFiltered.map { it.toTradeGroup() }
    return PortfolioTradesBucketUi(
        title = "Открытые",
        tradeCount = openGroups.size,
        totalPnlRub = sumTradeGroupsNetPnl(openGroups),
        groups = openGroups,
        isOpenTrades = true
    ) to PortfolioTradesBucketUi(
        title = "Закрытые",
        tradeCount = closedGroups.size,
        totalPnlRub = sumTradeGroupsNetPnl(closedGroups),
        groups = closedGroups,
        isOpenTrades = false
    )
}
