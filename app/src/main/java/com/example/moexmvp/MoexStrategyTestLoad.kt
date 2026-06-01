package com.example.moexmvp

import java.time.Instant
import java.time.temporal.ChronoUnit

/** Минимальный календарный охват 15м для полной симуляции (~255 дн.). */
internal const val STRATEGY_TEST_MIN_SPAN_DAYS = 180L

internal fun List<DataPoint>.m15CalendarSpanDays(): Long {
    if (size < 2) return 0L
    val zone = moexZoneId
    val first = Instant.ofEpochMilli(first().timestampMillis).atZone(zone).toLocalDate()
    val last = Instant.ofEpochMilli(last().timestampMillis).atZone(zone).toLocalDate()
    return ChronoUnit.DAYS.between(first, last) + 1
}

internal fun List<DataPoint>.sufficientForStrategyTestSimulation(): Boolean =
    size >= Z_SCORE_ROLLING_MIN_BARS && m15CalendarSpanDays() >= STRATEGY_TEST_MIN_SPAN_DAYS

/** Хвост ~1M для Z-графика на «Тест страт.» (симуляция — полный [strategyTestM15Points]). */
internal fun strategyTestM15PointsForChart(full: List<DataPoint>): List<DataPoint> {
    if (full.size < 2) return emptyList()
    val tail = filterM15PointsForMarketsPeriod(full, Period.OneMonth)
    return if (tail.size >= 2) tail else full
}

/** 15м ряд для симуляции «Тест страт.» */
internal fun MoexScreenState.m15PointsForStrategyTest(): List<DataPoint> =
    strategyTestM15Points.takeIf { it.sufficientForStrategyTestSimulation() }
        ?: strategyTestM15Points.takeIf { it.size >= 2 }
        ?: emptyList()

/**
 * Подгрузка 15м для симуляции без полного refresh портфеля (не блокирует демо-сделки).
 * @return true если после вызова есть ≥2 точки для симуляции
 */
internal suspend fun MoexScreenState.ensureM15PointsForStrategyTest(
    preferNetwork: Boolean = false,
    networkMode: PortfolioM15LoadMode = PortfolioM15LoadMode.INCREMENTAL,
): Boolean {
    strategyTestM15Loading = true
    strategyTestError = null
    try {
        fun publish(points: List<DataPoint>) {
            if (points.isEmpty()) return
            strategyTestM15Points = points
        }

        if (strategyTestM15Points.sufficientForStrategyTestSimulation()) return true

        if (!preferNetwork) {
            val cached = loadM15ForStrategyTest(PortfolioM15LoadMode.CACHE_ONLY)
            if (cached.sufficientForStrategyTestSimulation()) {
                publish(cached)
                return true
            }
            if (strategyTestM15Points.size < 2) {
                strategyTestError =
                    "Нет 15м данных в кэше. Нажмите «Обновить» для загрузки с MOEX."
            }
            return strategyTestM15Points.size >= 2
        }

        val loaded = loadM15ForStrategyTest(networkMode)
        publish(loaded)
        if (!strategyTestM15Points.sufficientForStrategyTestSimulation()) {
            strategyTestError = when (networkMode) {
                PortfolioM15LoadMode.FULL_REFRESH ->
                    "Нет 15м данных (ISS / сеть). Попробуйте позже."
                else ->
                    "Нет 15м данных (ISS / сеть). Попробуйте «MOEX заново»."
            }
        }
        return strategyTestM15Points.size >= 2
    } finally {
        strategyTestM15Loading = false
    }
}
