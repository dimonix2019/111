package com.example.moexmvp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
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

/** Сброс UI «Тест страт.»; полный 15м ряд в [strategyTestM15SessionCache] сохраняем. */
internal fun MoexScreenState.clearStrategyTestVisibleState() {
    strategyTestM15ChartTail = emptyList()
    strategyTestPortfolioMetrics = null
    strategyTestSimComputing = false
    strategyTestM15Loading = false
    strategyTestError = null
}

/** Полный сброс, включая кэш 255д (например при нехватке памяти). */
internal fun MoexScreenState.clearStrategyTestSession() {
    strategyTestM15SessionCache = emptyList()
    clearStrategyTestVisibleState()
}

private fun MoexScreenState.isOnStrategyTestTab(): Boolean = selectedTab == MainTab.StrategyTest

private fun MoexScreenState.isStrategyTestWorkCurrent(workId: Int): Boolean =
    isOnStrategyTestTab() && workId == strategyTestWorkGeneration

/** Симуляция на полном ряду; в UI state — только хвост для графика. */
internal suspend fun MoexScreenState.runStrategyTestSimulation(
    points: List<DataPoint>,
    workId: Int,
    reason: String,
) {
    if (!isStrategyTestWorkCurrent(workId)) return
    strategyTestSimComputing = true
    MoexDiagnostics.log(context, "st_sim", "start id=$workId reason=$reason points=${points.size}")
    MoexDiagnostics.logMemory(context, "st_sim")
    try {
        val chartTail = withContext(Dispatchers.Default) {
            strategyTestM15PointsForChart(points)
        }
        if (!isStrategyTestWorkCurrent(workId)) return
        strategyTestM15ChartTail = chartTail
        val metrics = withContext(Dispatchers.Default) {
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
        if (!isStrategyTestWorkCurrent(workId)) return
        strategyTestPortfolioMetrics = metrics
        val trades = metrics?.closedTrades?.size ?: 0
        MoexDiagnostics.log(context, "st_sim", "done id=$workId trades=$trades chartTail=${chartTail.size}")
    } catch (e: OutOfMemoryError) {
        MoexDiagnostics.log(context, "st_sim", "OOM id=$workId points=${points.size}")
        clearStrategyTestSession()
        strategyTestError =
            "Не хватает памяти (${points.size} баров). Закройте другие вкладки, перезапустите приложение."
    } catch (e: Exception) {
        MoexDiagnostics.log(
            context,
            "st_sim",
            "fail id=$workId ${e.javaClass.simpleName}: ${e.message?.take(120)}",
        )
        strategyTestError = e.message?.take(200) ?: e.javaClass.simpleName
    } finally {
        strategyTestSimComputing = false
    }
}

/** Только пересчёт симуляции из кэша (без SQLite/MOEX). */
internal suspend fun MoexScreenState.scheduleStrategyTestResimOnly(reason: String) {
    if (!isOnStrategyTestTab()) return
    if (!strategyTestM15SessionCache.sufficientForStrategyTestSimulation()) return
    if (strategyTestM15Loading) return
    val workId = ++strategyTestWorkGeneration
    MoexDiagnostics.log(context, "strategy_test", "resim id=$workId reason=$reason")
    refreshMutex.withLock {
        if (!isStrategyTestWorkCurrent(workId)) return@withLock
        runStrategyTestSimulation(strategyTestM15SessionCache, workId, reason)
    }
}

/**
 * Открытие вкладки / кнопка «Обновить».
 * @param preferNetwork true → MOEX (INCREMENTAL/FULL); false → только SQLite без сети.
 */
internal suspend fun MoexScreenState.scheduleStrategyTestTabWork(
    reason: String,
    preferNetwork: Boolean = false,
    networkMode: PortfolioM15LoadMode = PortfolioM15LoadMode.INCREMENTAL,
) {
    if (!isOnStrategyTestTab()) return
    if (reason.startsWith("tab_open") && strategyTestM15Loading) {
        MoexDiagnostics.log(context, "strategy_test", "skip $reason — load in progress")
        return
    }
    val workId = ++strategyTestWorkGeneration
    MoexDiagnostics.log(
        context,
        "strategy_test",
        "work id=$workId reason=$reason preferNetwork=$preferNetwork mode=$networkMode",
    )
    refreshMutex.withLock {
        if (!isStrategyTestWorkCurrent(workId)) return@withLock
        if (!preferNetwork && strategyTestM15SessionCache.sufficientForStrategyTestSimulation()) {
            runStrategyTestSimulation(strategyTestM15SessionCache, workId, "$reason+cache_hit")
            return@withLock
        }
        refreshStrategyTestTab(
            preferNetwork = preferNetwork,
            networkMode = networkMode,
            reloadFromDb = true,
            workId = workId,
        )
    }
}

/**
 * Загрузка 15м + симуляция. Полный ряд (~255д) только в [strategyTestM15SessionCache], не в mutableStateOf.
 */
internal suspend fun MoexScreenState.refreshStrategyTestTab(
    preferNetwork: Boolean = false,
    networkMode: PortfolioM15LoadMode = PortfolioM15LoadMode.INCREMENTAL,
    reloadFromDb: Boolean = true,
    workId: Int = strategyTestWorkGeneration,
) {
    if (!isStrategyTestWorkCurrent(workId)) return
    strategyTestM15Loading = true
    strategyTestError = null
    try {
        val points: List<DataPoint> = when {
            !reloadFromDb && strategyTestM15SessionCache.sufficientForStrategyTestSimulation() ->
                strategyTestM15SessionCache

            preferNetwork -> {
                val loaded = withContext(Dispatchers.IO) {
                    loadM15ForStrategyTest(networkMode)
                }
                if (!isStrategyTestWorkCurrent(workId)) return
                strategyTestM15SessionCache = loaded
                if (!loaded.sufficientForStrategyTestSimulation()) {
                    strategyTestError = when (networkMode) {
                        PortfolioM15LoadMode.FULL_REFRESH ->
                            "Нет 15м данных (ISS / сеть). Попробуйте позже."
                        else ->
                            "Нет 15м данных (ISS / сеть). Попробуйте «MOEX заново»."
                    }
                }
                loaded
            }

            else -> {
                MoexDiagnostics.log(context, "st_load", "cache_only_no_moex workId=$workId")
                val cached = withContext(Dispatchers.IO) {
                    loadM15ForStrategyTest(PortfolioM15LoadMode.CACHE_ONLY)
                }
                if (!isStrategyTestWorkCurrent(workId)) return
                strategyTestM15SessionCache = cached
                when {
                    cached.size < 2 -> strategyTestError =
                        "Нет 15м в кэше. Нажмите «Обновить» для загрузки с MOEX."
                    !cached.sufficientForStrategyTestSimulation() -> strategyTestError =
                        "В кэше ~${cached.m15CalendarSpanDays()} дн. (нужно ≥$STRATEGY_TEST_MIN_SPAN_DAYS). " +
                            "Нажмите «Обновить»."
                }
                cached
            }
        }
        if (!isStrategyTestWorkCurrent(workId) || points.size < 2) return
        runStrategyTestSimulation(points, workId, "after_load")
    } catch (e: OutOfMemoryError) {
        MoexDiagnostics.log(context, "st_load", "OOM workId=$workId")
        clearStrategyTestSession()
        strategyTestError = "Не хватает памяти при загрузке 15м. Перезапустите приложение."
    } catch (e: Exception) {
        MoexDiagnostics.log(
            context,
            "st_load",
            "fail workId=$workId ${e.javaClass.simpleName}: ${e.message?.take(120)}",
        )
        strategyTestError = e.message?.take(200) ?: e.javaClass.simpleName
    } finally {
        if (workId == strategyTestWorkGeneration || !isOnStrategyTestTab()) {
            strategyTestM15Loading = false
        }
    }
}

internal suspend fun MoexScreenState.ensureM15PointsForStrategyTest(
    preferNetwork: Boolean = false,
    networkMode: PortfolioM15LoadMode = PortfolioM15LoadMode.INCREMENTAL,
): Boolean {
    scheduleStrategyTestTabWork(
        reason = if (preferNetwork) "user_refresh" else "user_cache",
        preferNetwork = preferNetwork,
        networkMode = networkMode,
    )
    return strategyTestM15SessionCache.size >= 2
}
