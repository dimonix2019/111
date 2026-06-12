package com.example.moexmvp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MoexZStrategySignalSeriesTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    @Test
    fun rollingZ_onSameBarDiffersWhenHistoryWindowTruncated() {
        fun point(dayOffset: Int, slot: Int, spread: Double): DataPoint {
            val day = LocalDate.of(2026, 1, 1).plusDays(dayOffset.toLong())
            val ts = day.atTime(7 + slot / 4, (slot % 4) * 15)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
            return DataPoint(
                timestampMillis = ts,
                tradeDate = Instant.ofEpochMilli(ts).atZone(zone).format(portfolio15mLabelFormatter),
                tatnClose = 100.0,
                tatnpClose = 100.0,
                spreadPercent = spread,
                diff = 0.0,
                zScore = 0.0,
            )
        }
        val longHistory = buildList {
            for (day in 0 until 120) {
                for (slot in 0 until 4) {
                    add(point(day, slot, spread = 6.0 + 0.02 * (day % 11 - 5) + 0.001 * slot))
                }
            }
        }
        val shortHistory = longHistory.takeLast(15 * 4)
        val zLongLast = applyZScoresDefault(longHistory).last().zScore
        val zShortLast = applyZScoresDefault(shortHistory).last().zScore
        assertTrue(
            "Укороченный ряд (<30д) должен давать другой Z на последнем баре",
            kotlin.math.abs(zLongLast - zShortLast) > 1e-6,
        )
    }

    @Test
    fun signalEdges_onFullSeries_matchSimCrossingsForDay() {
        val thresholds = DynamicThresholds(entry = 0.7, exit = 0.5, calculatedDate = null)
        val day = LocalDate.of(2026, 6, 9)
        val points = listOf(
            bar(day, "15:30", z = 0.39),
            bar(day, "15:45", z = 0.73),
            bar(day, "16:00", z = 0.43),
            bar(day, "16:45", z = 0.08),
            bar(day, "17:00", z = 0.75),
        )

        val (edges, _) = collectZStrategy15mSignalEdgesSinceProcessedBar(
            points = points,
            lastProcessedBarTimestampMillis = points.first().timestampMillis,
            initialPosition = ZStrategyPosition.Flat,
            thresholds = thresholds,
        )
        assertTrue(edges.any { it.signal == ZStrategySignal.EnterShort && it.bar.tradeDate.endsWith("15:45") })
        assertTrue(edges.any { it.signal == ZStrategySignal.ExitShort && it.bar.tradeDate.endsWith("16:00") })
    }

    private fun bar(day: LocalDate, hhmm: String, z: Double): DataPoint {
        val (h, m) = hhmm.split(":").map { it.toInt() }
        val ts = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        return DataPoint(
            timestampMillis = ts,
            tradeDate = "$day $hhmm",
            tatnClose = 100.0,
            tatnpClose = 100.0,
            spreadPercent = 0.0,
            diff = 0.0,
            zScore = z,
        )
    }
}
