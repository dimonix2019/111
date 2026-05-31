package com.example.moexmvp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 15м ряд для симуляции «Тест страт.» — portfolio, markets или кэш. */
internal fun MoexScreenState.m15PointsForStrategyTest(): List<DataPoint> = when {
    portfolioM15Points.size >= 2 -> portfolioM15Points
    marketsM15Points.size >= 2 -> marketsM15Points
    else -> emptyList()
}

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
            portfolioM15Points = points
            if (marketsM15Points.isEmpty()) marketsM15Points = points
        }

        if (m15PointsForStrategyTest().size >= 2) return true

        val resolved = resolveEnrichmentPoints()
        if (resolved.size >= 2) {
            publish(resolved)
            return true
        }
        if (resolved.isNotEmpty()) publish(resolved)

        if (!preferNetwork) {
            if (m15PointsForStrategyTest().size < 2) {
                strategyTestError =
                    "Нет 15м данных в кэше. Нажмите «Обновить» для загрузки с MOEX."
            }
            return m15PointsForStrategyTest().size >= 2
        }

        val loaded = withContext(Dispatchers.IO) {
            loadPortfolio15mPointsForSignalMonitor(context, networkMode)
        }
        publish(loaded)
        if (m15PointsForStrategyTest().size < 2) {
            strategyTestError = when (networkMode) {
                PortfolioM15LoadMode.FULL_REFRESH ->
                    "Нет 15м данных (ISS / сеть). Попробуйте позже."
                else ->
                    "Нет 15м данных (ISS / сеть). Попробуйте «MOEX заново»."
            }
        }
        return m15PointsForStrategyTest().size >= 2
    } finally {
        strategyTestM15Loading = false
    }
}
