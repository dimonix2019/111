package com.example.moexmvp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.sqrt

internal fun calculateBestDynamicThresholds(from: LocalDate, till: LocalDate): DynamicThresholds? {
    val tatnBars = aggregateTo15MinuteBars(
        loadCandleBars("TATN", from, till, interval = 1)
    ).associateBy { it.timestamp }
    val tatnpBars = aggregateTo15MinuteBars(
        loadCandleBars("TATNP", from, till, interval = 1)
    ).associateBy { it.timestamp }
    val alignedTimes = tatnBars.keys.intersect(tatnpBars.keys).sorted()
    if (alignedTimes.size < 20) return null

    val spreads = alignedTimes.mapNotNull { timestamp ->
        val tatnClose = tatnBars[timestamp]?.close ?: return@mapNotNull null
        val tatnpClose = tatnpBars[timestamp]?.close ?: return@mapNotNull null
        if (tatnpClose == 0.0) return@mapNotNull null
        (tatnClose / tatnpClose - 1.0) * 100.0
    }
    if (spreads.size < 20) return null

    val mean = spreads.average()
    val variance = spreads
        .map { (it - mean) * (it - mean) }
        .average()
    val stdDev = kotlin.math.sqrt(variance).takeIf { it > 0.0 } ?: 1.0
    val points = spreads.map { spread ->
        BacktestPoint(
            spread = spread,
            z = (spread - mean) / stdDev
        )
    }
    if (points.size < 20) return null

    var best: BacktestResult? = null
    for (entryTenths in Z_STRATEGY_ENTRY_MIN_TENTHS..Z_STRATEGY_ENTRY_MAX_TENTHS) {
        val entry = entryTenths / 10.0
        for (exitTenths in Z_STRATEGY_EXIT_MIN_TENTHS..Z_STRATEGY_EXIT_MAX_TENTHS) {
            val exit = exitTenths / 10.0
            if (exit >= entry) continue
            val result = backtestZStrategy(points, entry, exit)
            if (result.trades < Z_STRATEGY_MIN_TRADES) continue
            if (best == null || result.pnl > best.pnl) {
                best = result
            }
        }
    }

    val winner = best ?: return null
    return DynamicThresholds(
        entry = winner.entry,
        exit = winner.exit,
        calculatedDate = till.toString()
    )
}

internal fun backtestZStrategy(points: List<BacktestPoint>, entry: Double, exit: Double): BacktestResult {
    var position = ZStrategyPosition.Flat
    var entrySpread = 0.0
    var pnl = 0.0
    var trades = 0

    for (index in 1 until points.size) {
        val prev = points[index - 1]
        val current = points[index]
        when (position) {
            ZStrategyPosition.Long -> {
                if (prev.z < -exit && current.z >= -exit) {
                    pnl += current.spread - entrySpread
                    position = ZStrategyPosition.Flat
                    trades += 1
                }
            }

            ZStrategyPosition.Short -> {
                if (prev.z > exit && current.z <= exit) {
                    pnl += entrySpread - current.spread
                    position = ZStrategyPosition.Flat
                    trades += 1
                }
            }

            ZStrategyPosition.Flat -> Unit
        }
        if (position == ZStrategyPosition.Flat) {
            if (prev.z > -entry && current.z <= -entry) {
                position = ZStrategyPosition.Long
                entrySpread = current.spread
            } else if (prev.z < entry && current.z >= entry) {
                position = ZStrategyPosition.Short
                entrySpread = current.spread
            }
        }
    }

    if (position != ZStrategyPosition.Flat) {
        val lastSpread = points.last().spread
        pnl += if (position == ZStrategyPosition.Long) {
            lastSpread - entrySpread
        } else {
            entrySpread - lastSpread
        }
        trades += 1
    }

    return BacktestResult(
        pnl = pnl,
        trades = trades,
        entry = entry,
        exit = exit
    )
}

internal fun determineZStrategySignal(
    previousZ: Double?,
    currentZ: Double,
    position: ZStrategyPosition,
    thresholds: DynamicThresholds
): ZStrategySignal {
    val prev = previousZ ?: return ZStrategySignal.None
    return when (position) {
        ZStrategyPosition.Flat -> {
            when {
                prev > -thresholds.entry && currentZ <= -thresholds.entry -> ZStrategySignal.EnterLong
                prev < thresholds.entry && currentZ >= thresholds.entry -> ZStrategySignal.EnterShort
                else -> ZStrategySignal.None
            }
        }

        ZStrategyPosition.Long -> {
            if (prev < -thresholds.exit && currentZ >= -thresholds.exit) {
                ZStrategySignal.ExitLong
            } else {
                ZStrategySignal.None
            }
        }

        ZStrategyPosition.Short -> {
            if (prev > thresholds.exit && currentZ <= thresholds.exit) {
                ZStrategySignal.ExitShort
            } else {
                ZStrategySignal.None
            }
        }
    }
}

/** Соседние закрытые 15м бары (ровно +15 мин MSK). Без этого prev→current через пропуски даёт ложные пересечения. */
internal fun isConsecutiveM15Bar(previous: DataPoint, current: DataPoint): Boolean {
    val prevMs = barMillisAt(previous)
    val curMs = barMillisAt(current)
    if (prevMs <= 0L || curMs <= 0L) return false
    val prev = Instant.ofEpochMilli(prevMs).atZone(moexZoneId).toLocalDateTime()
    val cur = Instant.ofEpochMilli(curMs).atZone(moexZoneId).toLocalDateTime()
    return ChronoUnit.MINUTES.between(prev, cur) == 15L
}

/** [determineZStrategySignal] только на соседней паре 15м баров (parity sim / live replay). */
internal fun determineZStrategySignalBetweenBars(
    previous: DataPoint,
    current: DataPoint,
    position: ZStrategyPosition,
    thresholds: DynamicThresholds,
): ZStrategySignal {
    if (!isConsecutiveM15Bar(previous, current)) return ZStrategySignal.None
    return determineZStrategySignal(previous.zScore, current.zScore, position, thresholds)
}
