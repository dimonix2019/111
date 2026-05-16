package com.example.moexmvp

import android.content.Context
import java.time.LocalDate

/** Z-score и время бара с последней точки 15м ряда (как на «Рынке»). */
internal data class PortfolioMarketBarSnapshot(
    val zScore: Double,
    val timestampMillis: Long
)

internal suspend fun loadCurrentPortfolioMarketSnapshot(context: Context): PortfolioMarketBarSnapshot {
    val app = context.applicationContext
    val till = LocalDate.now()
    val from = till.minusDays(10)
    val points = loadPortfolio15mDataPoints(
        app,
        from,
        till,
        PortfolioM15LoadMode.INCREMENTAL
    )
    val last = points.lastOrNull()
    return if (last != null) {
        PortfolioMarketBarSnapshot(zScore = last.zScore, timestampMillis = last.timestampMillis)
    } else {
        PortfolioMarketBarSnapshot(zScore = 0.0, timestampMillis = System.currentTimeMillis())
    }
}
