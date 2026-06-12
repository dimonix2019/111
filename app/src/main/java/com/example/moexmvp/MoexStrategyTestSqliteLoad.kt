package com.example.moexmvp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Размер чанка SQLite для «Тест страт.» (не грузим 255д одним запросом Room). */
internal const val STRATEGY_TEST_SQLITE_CHUNK_ROWS = 2_500

/**
 * Только SQLite: чанками + Z из снимков (persistedZScore) или rolling-пересчёт при пробелах.
 * Без MOEX и без глобального DataLoadProgressCard.
 */
internal suspend fun loadStrategyTestM15CacheOnlyChunked(
    context: Context,
    from: LocalDate,
    till: LocalDate,
): List<DataPoint> = withContext(Dispatchers.IO) {
    val app = context.applicationContext
    val zone = moexZoneId
    val cutoffMillis = from.atStartOfDay(zone).toInstant().toEpochMilli()
    val endMillis = till.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val dao = PortfolioM15Database.get(app).dao()
    val estimated = dao.countSince(cutoffMillis)
    MoexDiagnostics.log(app, "st_sqlite", "chunk_load estimated=$estimated from=$from")
    MoexDiagnostics.logMemory(app, "st_sqlite_start")

    val merged = ArrayList<DataPoint>((estimated.coerceAtMost(25_000) * 1.05).toInt().coerceAtLeast(256))
    val entities = ArrayList<PortfolioM15SpreadEntity>(
        (estimated.coerceAtMost(25_000) * 1.05).toInt().coerceAtLeast(256),
    )
    var cursor = cutoffMillis
    var chunkIndex = 0
    while (cursor < endMillis) {
        val rows = dao.getRangeLimited(cursor, endMillis, STRATEGY_TEST_SQLITE_CHUNK_ROWS)
        if (rows.isEmpty()) break
        chunkIndex++
        for (entity in rows) {
            entities += entity
            merged.add(entity.toDataPoint())
        }
        cursor = rows.last().tsMillis + 1L
        MoexDiagnostics.log(
            app,
            "st_sqlite",
            "chunk=$chunkIndex rows=${rows.size} total=${merged.size}",
        )
        yield()
    }

    if (merged.size < 2) {
        MoexDiagnostics.log(app, "st_sqlite", "done empty")
        return@withContext emptyList()
    }

    val recalculated = fillM15ZScoresInPlace(merged, entities)
    if (recalculated) {
        MoexDiagnostics.log(app, "st_sqlite", "apply_z rows=${merged.size}")
        persistM15ZScoreSnapshots(dao, entities, merged)
    } else {
        MoexDiagnostics.log(app, "st_sqlite", "skip apply_z persisted rows=${merged.size}")
    }
    MoexDiagnostics.log(
        app,
        "st_sqlite",
        "done points=${merged.size} spanDays=${merged.m15CalendarSpanDays()}",
    )
    MoexDiagnostics.logMemory(app, "st_sqlite_done")
    merged
}
