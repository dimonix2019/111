package com.example.moexmvp

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.floor

internal const val DYNAMIC_RANGE_COLOR_HEX = "#38bdf8"
internal const val DYNAMIC_RANGE_MIN_DAYS = 4
internal const val DYNAMIC_RANGE_EPISODE_DAYS = 40
internal const val DYNAMIC_RANGE_MIN_MINUTES = 30
internal const val DYNAMIC_RANGE_MIN_HEIGHT = 0.25
internal const val DYNAMIC_RANGE_MAX_HEIGHT = 3.0
internal const val DYNAMIC_RANGE_TOUCH_PAD = 0.25
internal const val DYNAMIC_RANGE_ESCAPE_PAD = 0.50

internal data class SpreadSessionDay(
    val date: LocalDate,
    val min: Double,
    val max: Double,
    val bodyLow: Double,
    val bodyHigh: Double,
)

internal data class DynamicRangeBox(
    val floor: Double,
    val ceiling: Double,
)

/** Горизонталь с утра дня, когда коробка стала известна, до утра следующего пересчёта. */
internal data class DynamicRangeSegment(
    val fromMillis: Long,
    val toMillis: Long,
    val floor: Double,
    val ceiling: Double,
)

internal data class MinuteSpreadSample(
    val time: LocalDateTime,
    val spreadPercent: Double,
)

private val sessionStart: LocalTime = LocalTime.of(7, 0)
private val sessionEnd: LocalTime = LocalTime.of(23, 50)

internal fun isDynamicRangeSessionMinute(time: LocalDateTime): Boolean {
    if (time.dayOfWeek == DayOfWeek.SATURDAY || time.dayOfWeek == DayOfWeek.SUNDAY) return false
    val clock = time.toLocalTime()
    return !clock.isBefore(sessionStart) && !clock.isAfter(sessionEnd)
}

/** Линейный перцентиль (тип 7): p в долях, 0.25 и 0.75. */
internal fun percentileLinear(values: List<Double>, fraction: Double): Double {
    require(values.isNotEmpty())
    val sorted = values.sorted()
    if (sorted.size == 1) return sorted[0]
    val h = (sorted.size - 1) * fraction.coerceIn(0.0, 1.0)
    val lo = floor(h).toInt()
    val hi = (lo + 1).coerceAtMost(sorted.lastIndex)
    val w = h - lo
    return sorted[lo] * (1.0 - w) + sorted[hi] * w
}

internal fun spreadSessionDays(samples: List<MinuteSpreadSample>): List<SpreadSessionDay> {
    val byDate = linkedMapOf<LocalDate, MutableList<Double>>()
    for (sample in samples) {
        if (!sample.spreadPercent.isFinite()) continue
        if (!isDynamicRangeSessionMinute(sample.time)) continue
        byDate.getOrPut(sample.time.toLocalDate()) { mutableListOf() }.add(sample.spreadPercent)
    }
    return byDate.mapNotNull { (date, values) ->
        if (values.size < DYNAMIC_RANGE_MIN_MINUTES) return@mapNotNull null
        SpreadSessionDay(
            date = date,
            min = values.min(),
            max = values.max(),
            bodyLow = percentileLinear(values, 0.25),
            bodyHigh = percentileLinear(values, 0.75),
        )
    }.sortedBy { it.date }
}

internal fun dynamicRangeBox(days: List<SpreadSessionDay>): DynamicRangeBox? {
    if (days.size < DYNAMIC_RANGE_MIN_DAYS) return null
    val floor = percentileLinear(days.map { it.bodyLow }, 0.25)
    val ceiling = percentileLinear(days.map { it.bodyHigh }, 0.75)
    val height = ceiling - floor
    if (height < DYNAMIC_RANGE_MIN_HEIGHT || height > DYNAMIC_RANGE_MAX_HEIGHT) return null
    val touchedFloor = days.count { it.min <= floor + DYNAMIC_RANGE_TOUCH_PAD }
    val touchedCeiling = days.count { it.max >= ceiling - DYNAMIC_RANGE_TOUCH_PAD }
    if (touchedFloor < 2 || touchedCeiling < 2) return null
    return DynamicRangeBox(floor, ceiling)
}

internal fun lastThreeLeftDynamicRange(days: List<SpreadSessionDay>, box: DynamicRangeBox): Boolean {
    if (days.size < 3) return false
    val last = days.takeLast(3)
    val allAbove = last.all { it.min > box.floor + DYNAMIC_RANGE_ESCAPE_PAD }
    val allBelow = last.all { it.max < box.ceiling - DYNAMIC_RANGE_ESCAPE_PAD }
    return allAbove || allBelow
}

private fun nextWeekday(date: LocalDate): LocalDate {
    var cursor = date.plusDays(1)
    while (cursor.dayOfWeek == DayOfWeek.SATURDAY || cursor.dayOfWeek == DayOfWeek.SUNDAY) {
        cursor = cursor.plusDays(1)
    }
    return cursor
}

private fun morningMillis(date: LocalDate, zone: ZoneId): Long =
    date.atTime(sessionStart).atZone(zone).toInstant().toEpochMilli()

/**
 * Коробка на утро дня D — только из законченных будней до D.
 * Если три последних дня ушли от коробки, эпизод режется до них, и на это утро линий нет.
 */
internal fun buildDynamicSpreadRangeSegments(
    samples: List<MinuteSpreadSample>,
    zone: ZoneId = moexZoneId,
): List<DynamicRangeSegment> {
    val days = spreadSessionDays(samples)
    if (days.isEmpty()) return emptyList()
    val byDate = days.associateBy { it.date }
    val episode = ArrayDeque<SpreadSessionDay>()
    val out = ArrayList<DynamicRangeSegment>()
    var cursor = days.first().date
    val last = days.last().date
    while (!cursor.isAfter(last)) {
        if (episode.size >= 3) {
            val prior = episode.dropLast(3)
            val priorBox = dynamicRangeBox(prior)
            if (priorBox != null && lastThreeLeftDynamicRange(episode, priorBox)) {
                val kept = episode.takeLast(3)
                episode.clear()
                episode.addAll(kept)
            }
        }
        val box = dynamicRangeBox(episode)
        val next = nextWeekday(cursor)
        if (box != null) {
            val from = morningMillis(cursor, zone)
            val to = morningMillis(next, zone)
            val prev = out.lastOrNull()
            if (
                prev != null &&
                prev.toMillis == from &&
                prev.floor == box.floor &&
                prev.ceiling == box.ceiling
            ) {
                out[out.lastIndex] = prev.copy(toMillis = to)
            } else {
                out += DynamicRangeSegment(from, to, box.floor, box.ceiling)
            }
        }
        byDate[cursor]?.let { day ->
            episode.addLast(day)
            while (episode.size > DYNAMIC_RANGE_EPISODE_DAYS) episode.removeFirst()
        }
        cursor = next
    }
    return out
}

internal fun dynamicRangeSamplesFromSpreadCandles(candles: List<CandlePoint>): List<MinuteSpreadSample> =
    candles.mapNotNull { candle ->
        val time = runCatching { LocalDateTime.parse(candle.label.trim(), portfolio15mLabelFormatter) }
            .getOrNull() ?: return@mapNotNull null
        val spread = candle.close.takeIf { it.isFinite() } ?: return@mapNotNull null
        MinuteSpreadSample(time, spread)
    }
