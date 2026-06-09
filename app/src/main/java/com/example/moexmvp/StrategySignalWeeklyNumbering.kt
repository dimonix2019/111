package com.example.moexmvp

import java.time.Instant
import java.time.LocalDate
import java.time.temporal.IsoFields

/** С этой даты (МСК) сигналы получают еженедельную нумерацию с 1. */
internal val STRATEGY_SIGNAL_WEEKLY_NUMBER_EPOCH: LocalDate = LocalDate.of(2026, 6, 1)

internal data class StrategySignalKey(
    val timestampMillis: Long,
    val signalType: StrategySignalType,
)

internal fun strategySignalDirectionLabel(signalType: StrategySignalType): String = when (signalType) {
    StrategySignalType.EnterLong, StrategySignalType.ExitLong -> "long"
    StrategySignalType.EnterShort, StrategySignalType.ExitShort -> "short"
}

private fun strategySignalWeekKey(epochMillis: Long): String {
    val zdt = Instant.ofEpochMilli(epochMillis).atZone(moexZoneId)
    val week = zdt.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
    val year = zdt.get(IsoFields.WEEK_BASED_YEAR)
    return "$year-W$week"
}

/**
 * Порядковый номер сигнала внутри ISO-недели (МСК), с 1.
 * Учитываются только события не раньше [STRATEGY_SIGNAL_WEEKLY_NUMBER_EPOCH].
 */
internal fun buildWeeklySignalNumberIndex(
    events: List<StrategySignalEvent>,
): Map<StrategySignalKey, Int> {
    val epochStart = STRATEGY_SIGNAL_WEEKLY_NUMBER_EPOCH
        .atStartOfDay(moexZoneId)
        .toInstant()
        .toEpochMilli()
    val eligible = events.filter { it.receivedAtMillis >= epochStart }
    if (eligible.isEmpty()) return emptyMap()
    return eligible
        .groupBy { strategySignalWeekKey(it.receivedAtMillis) }
        .flatMap { (_, weekEvents) ->
            weekEvents
                .sortedWith(
                    compareBy<StrategySignalEvent>(
                        { it.receivedAtMillis },
                        { it.timestampMillis },
                        { it.signalType.name },
                    )
                )
                .mapIndexed { index, ev ->
                    StrategySignalKey(ev.timestampMillis, ev.signalType) to (index + 1)
                }
        }
        .toMap()
}

/** Полный ID сигнала: «3 long 2026-06-01 14:30». */
internal fun weeklyStrategySignalLabel(
    weeklyNumber: Int,
    signalType: StrategySignalType,
    barTimestampMillis: Long,
): String =
    "$weeklyNumber ${strategySignalDirectionLabel(signalType)} " +
        formatPortfolioExecutionTableMsk(barTimestampMillis)

/** Короткий ID сделки (пара): «3 long». */
internal fun weeklyTradeDisplayId(
    weeklyNumber: Int,
    entrySignalType: StrategySignalType,
): String =
    "$weeklyNumber ${strategySignalDirectionLabel(entrySignalType)}"

internal fun weeklyStrategySignalLabel(
    index: Map<StrategySignalKey, Int>,
    event: StrategySignalEvent,
): String? {
    val n = index[StrategySignalKey(event.timestampMillis, event.signalType)] ?: return null
    return weeklyStrategySignalLabel(n, event.signalType, event.timestampMillis)
}

internal fun weeklyTradeDisplayId(
    index: Map<StrategySignalKey, Int>,
    barTimestampMillis: Long,
    entrySignalType: StrategySignalType,
): String? {
    val n = index[StrategySignalKey(barTimestampMillis, entrySignalType)] ?: return null
    return weeklyTradeDisplayId(n, entrySignalType)
}
