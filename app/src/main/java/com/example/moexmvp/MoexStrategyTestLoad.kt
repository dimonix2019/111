package com.example.moexmvp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** Хвост ~1M для Z-графика (полный ряд симуляции не кладём в Compose state). */
internal fun strategyTestM15PointsForChart(full: List<DataPoint>): List<DataPoint> {
    if (full.size < 2) return emptyList()
    val tail = filterM15PointsForMarketsPeriod(full, Period.OneMonth)
    return if (tail.size >= 2) tail else full
}

internal fun MoexScreenState.clearStrategyTestSession() {
    strategyTestM15SessionCache = emptyList()
    strategyTestM15ChartTail = emptyList()
    strategyTestSimComputing = false
}

/** Симуляция на полном ряду; в UI state — только хвост для графика. */
internal suspend fun MoexScreenState.runStrategyTestSimulation(points: List<DataPoint>) {
    if (selectedTab != MainTab.StrategyTest) return
    strategyTestSimComputing = true
    try {
        val chartTail = withContext(Dispatchers.Default) {
            strategyTestM15PointsForChart(points)
        }
        strategyTestM15ChartTail = chartTail
        strategyTestPortfolioMetrics = withContext(Dispatchers.Default) {
            if (!points.sufficientForStrategyTestSimulation()) return@withContext null
            val entry = (strategyTestEntryThreshold ?: dynamicThresholds.entry)
                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
            val exit = (strategyTestExitThreshold ?: dynamicThresholds.exit)
                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
            buildZStrategyPortfolioMetrics(
                points = points,
                thresholds = DynamicThresholds(entry, exit, dynamicThresholds.calculatedDate),
                notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
                leverage = portfolioLeverage,
                commissionPercentPerSide = portfolioCommissionPercent,
                periodDescription = "Тест страт. · ${PORTFOLIO_M15_LOOKBACK_DAYS}д",
                compoundReturns = strategyTestCompoundReturns,
                exitMode = ZStrategyExitMode.FixedThreshold,
            )
        }
    } finally {
        strategyTestSimComputing = false
    }
}

/**
 * Загрузка 15м + симуляция. Полный ряд (~255д) только в [strategyTestM15SessionCache], не в mutableStateOf.
 * @param preferNetwork только по кнопке «Обновить» / «MOEX заново» — не при открытии вкладки.
 */
internal suspend fun MoexScreenState.refreshStrategyTestTab(
    preferNetwork: Boolean = false,
    networkMode: PortfolioM15LoadMode = PortfolioM15LoadMode.INCREMENTAL,
    reloadFromDb: Boolean = true,
) {
    if (selectedTab != MainTab.StrategyTest) return
    strategyTestM15Loading = true
    strategyTestError = null
    try {
        val points: List<DataPoint> = when {
            !reloadFromDb && strategyTestM15SessionCache.sufficientForStrategyTestSimulation() ->
                strategyTestM15SessionCache
            preferNetwork -> withContext(Dispatchers.IO) {
                loadM15ForStrategyTest(networkMode)
            }.also { loaded ->
                strategyTestM15SessionCache = loaded
                if (!loaded.sufficientForStrategyTestSimulation()) {
                    strategyTestError = when (networkMode) {
                        PortfolioM15LoadMode.FULL_REFRESH ->
                            "Нет 15м данных (ISS / сеть). Попробуйте позже."
                        else ->
                            "Нет 15м данных (ISS / сеть). Попробуйте «MOEX заново»."
                    }
                }
            }
            else -> withContext(Dispatchers.IO) {
                loadM15ForStrategyTest(PortfolioM15LoadMode.CACHE_ONLY)
            }.also { cached ->
                strategyTestM15SessionCache = cached
                when {
                    cached.size < 2 -> strategyTestError =
                        "Нет 15м в кэше. Нажмите «Обновить» для загрузки с MOEX."
                    !cached.sufficientForStrategyTestSimulation() -> strategyTestError =
                        "В кэше ~${cached.m15CalendarSpanDays()} дн. (нужно ≥$STRATEGY_TEST_MIN_SPAN_DAYS). " +
                            "Нажмите «Обновить»."
                }
            }
        }
        if (points.size < 2) return
        runStrategyTestSimulation(points)
    } finally {
        strategyTestM15Loading = false
    }
}

/**
 * Подгрузка 15м для симуляции без полного refresh портфеля.
 * @return true если после вызова есть ≥2 точки
 */
internal suspend fun MoexScreenState.ensureM15PointsForStrategyTest(
    preferNetwork: Boolean = false,
    networkMode: PortfolioM15LoadMode = PortfolioM15LoadMode.INCREMENTAL,
): Boolean {
    refreshStrategyTestTab(
        preferNetwork = preferNetwork,
        networkMode = networkMode,
        reloadFromDb = true,
    )
    return strategyTestM15SessionCache.size >= 2
}
