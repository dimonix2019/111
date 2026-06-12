package com.example.moexmvp

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val dailySimZone: ZoneId = ZoneId.of("Europe/Moscow")

/** Индекс первого 15м бара календарного [day] (МСК); null если нет. */
internal fun firstBarIndexOnDay(points: List<DataPoint>, day: LocalDate): Int? {
    val idx = points.indexOfFirst { barLocalDate(it) == day }
    return idx.takeIf { it >= 0 }
}

internal fun barLocalDate(point: DataPoint): LocalDate =
    Instant.ofEpochMilli(point.timestampMillis).atZone(dailySimZone).toLocalDate()

/** Позиция по журналу до начала [day] (события строго до полуночи дня). */
internal fun inferJournalPositionBeforeDay(
    events: List<StrategySignalEvent>,
    day: LocalDate,
): ZStrategyPosition {
    var position = ZStrategyPosition.Flat
    for (event in events.sortedBy { it.timestampMillis }) {
        val eventDay = Instant.ofEpochMilli(event.timestampMillis).atZone(dailySimZone).toLocalDate()
        if (!eventDay.isBefore(day)) break
        position = when (event.signalType) {
            StrategySignalType.EnterLong -> ZStrategyPosition.Long
            StrategySignalType.EnterShort -> ZStrategyPosition.Short
            StrategySignalType.ExitLong, StrategySignalType.ExitShort -> ZStrategyPosition.Flat
        }
    }
    return position
}

/** Позиция sim после replay 255d ряда до первого бара [day]. */
internal fun inferSimPositionAtDayOpen(
    points: List<DataPoint>,
    day: LocalDate,
    thresholds: DynamicThresholds,
    notionalRub: Double,
    leverage: Double,
    commissionPercentPerSide: Double,
): ZStrategyPosition {
    val dayStartIdx = firstBarIndexOnDay(points, day) ?: return ZStrategyPosition.Flat
    if (dayStartIdx < 1) return ZStrategyPosition.Flat
    val prefix = buildZStrategyPortfolioMetrics(
        points = points.subList(0, dayStartIdx),
        thresholds = thresholds,
        notionalRub = notionalRub,
        leverage = leverage,
        commissionPercentPerSide = commissionPercentPerSide,
        periodDescription = "prefix before $day",
    )
    return prefix?.openPosition?.direction ?: ZStrategyPosition.Flat
}

/**
 * Sim только с открытия [day]: позиция на первом баре дня = replay до этого момента;
 * закрытые сделки и PnL — только за активность внутри дня.
 */
internal fun buildTodaySimPortfolioMetricsFromDayOpen(
    points: List<DataPoint>,
    day: LocalDate,
    thresholds: DynamicThresholds,
    notionalRub: Double,
    leverage: Double,
    commissionPercentPerSide: Double,
    compoundReturns: Boolean = false,
): PortfolioMetrics? {
    val dayStartIdx = firstBarIndexOnDay(points, day) ?: return null
    if (dayStartIdx < 1 || points.size <= dayStartIdx) return null

    val carryOpen = buildZStrategyPortfolioMetrics(
        points = points.subList(0, dayStartIdx),
        thresholds = thresholds,
        notionalRub = notionalRub,
        leverage = leverage,
        commissionPercentPerSide = commissionPercentPerSide,
        periodDescription = "carry before $day",
        compoundReturns = compoundReturns,
    )?.openPosition

    return buildZStrategyPortfolioMetrics(
        points = points,
        thresholds = thresholds,
        notionalRub = notionalRub,
        leverage = leverage,
        commissionPercentPerSide = commissionPercentPerSide,
        periodDescription = "today from day open $day",
        compoundReturns = compoundReturns,
        simLoopStartIndex = dayStartIdx,
        initialCarryOpen = carryOpen,
        todaySliceOnly = true,
    )
}

/** Пересечения порога только на барах [day] с [initialPosition] на открытие дня. */
internal fun collectZStrategySignalEdgesOnCalendarDay(
    points: List<DataPoint>,
    day: LocalDate,
    initialPosition: ZStrategyPosition,
    thresholds: DynamicThresholds,
): List<ZStrategy15mSignalEdge> {
    if (points.size < 2) return emptyList()
    var position = initialPosition
    val edges = mutableListOf<ZStrategy15mSignalEdge>()
    for (index in 1 until points.size) {
        val current = points[index]
        val currentDay = barLocalDate(current)
        if (currentDay.isBefore(day)) {
            position = advanceZStrategyPosition(points, index, position, thresholds)
            continue
        }
        if (currentDay.isAfter(day)) break
        val prev = points[index - 1]
        val signal = determineZStrategySignalBetweenBars(prev, current, position, thresholds)
        if (signal != ZStrategySignal.None) {
            edges += ZStrategy15mSignalEdge(
                signal = signal,
                bar = current,
                positionBefore = position,
                positionAfter = positionAfterZStrategySignal(signal),
            )
            position = positionAfterZStrategySignal(signal)
        }
    }
    return edges
}

internal fun advanceZStrategyPosition(
    points: List<DataPoint>,
    index: Int,
    position: ZStrategyPosition,
    thresholds: DynamicThresholds,
): ZStrategyPosition {
    val signal = determineZStrategySignalBetweenBars(
        points[index - 1],
        points[index],
        position,
        thresholds,
    )
    return when (signal) {
        ZStrategySignal.EnterLong -> ZStrategyPosition.Long
        ZStrategySignal.EnterShort -> ZStrategyPosition.Short
        ZStrategySignal.ExitLong, ZStrategySignal.ExitShort -> ZStrategyPosition.Flat
        ZStrategySignal.None -> position
    }
}
