package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class MoexMarketsSpreadDeltaTest {

    @Test
    fun spreadDeltaFromDayOpenSeries_zerosAt0730Bar() {
        val points = listOf(
            point("2026-05-19 07:30", spread = 4.78),
            point("2026-05-19 10:00", spread = 5.00),
            point("2026-05-19 12:00", spread = 5.10),
        )
        val series = requireNotNull(spreadDeltaFromDayOpenSeries(points))
        assertEquals(3, series.deltasPp.size)
        assertEquals(0.0, series.deltasPp[0], 1e-9)
        assertEquals(0.22, series.deltasPp[1], 1e-9)
        assertEquals(0.32, series.deltasPp[2], 1e-9)
        assertEquals(4.78, series.dayOpenSpreadPercent!!, 1e-9)
    }

    @Test
    fun spreadDeltaFromDayOpenSeries_resetsEachTradingDay() {
        val points = listOf(
            point("2026-05-18 07:30", spread = 4.50),
            point("2026-05-18 18:00", spread = 5.00),
            point("2026-05-19 07:30", spread = 4.80),
            point("2026-05-19 12:00", spread = 5.20),
        )
        val series = requireNotNull(spreadDeltaFromDayOpenSeries(points))
        assertEquals(4, series.deltasPp.size)
        assertEquals(0.0, series.deltasPp[0], 1e-9)
        assertEquals(0.50, series.deltasPp[1], 1e-9)
        assertEquals(0.0, series.deltasPp[2], 1e-9)
        assertEquals(0.40, series.deltasPp[3], 1e-9)
        assertEquals(4.80, series.dayOpenSpreadPercent!!, 1e-9)
    }

    @Test
    fun buildIntraday1mSpreadDeltaSeries_usesM15DayOpen() {
        val zone = ZoneId.of("Europe/Moscow")
        val today = LocalDate.now(zone)
        val openLabel = LocalDateTime.of(today.year, today.month, today.dayOfMonth, 7, 30)
            .format(portfolio15mLabelFormatter)
        val m15 = listOf(
            point(openLabel, spread = 4.78),
            point(
                LocalDateTime.of(today.year, today.month, today.dayOfMonth, 10, 0)
                    .format(portfolio15mLabelFormatter),
                spread = 5.00,
            ),
        )
        val aligned = AlignedIntraday1mQuotes(
            labels = listOf("10:00", "10:01"),
            tatnCloses = listOf(650.0, 651.0),
            tatnpCloses = listOf(600.0, 600.5),
        )
        val series = buildIntraday1mSpreadDeltaSeries(m15, aligned, zone = zone)
        requireNotNull(series)
        assertEquals(2, series.deltasPp.size)
        assertEquals(4.78, series.dayOpenSpreadPercent, 1e-9)
        val spread10 = spreadPercentFromPairCloses(650.0, 600.0)!!
        assertEquals(spread10 - 4.78, series.deltasPp[0], 1e-9)
        val spread11 = spreadPercentFromPairCloses(651.0, 600.5)!!
        assertEquals(spread11 - 4.78, series.deltasPp[1], 1e-9)
    }

    @Test
    fun formatSpreadDeltaPp_includesSign() {
        assertEquals("+0.22", formatSpreadDeltaPp(0.22))
        assertEquals("-0.15", formatSpreadDeltaPp(-0.15))
        assertEquals("+0.00", formatSpreadDeltaPp(0.0))
    }

    private fun point(label: String, spread: Double): DataPoint {
        val ts = LocalDateTime.parse(label, portfolio15mLabelFormatter)
        return DataPoint(
            timestampMillis = ts.atZone(ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli(),
            tradeDate = label,
            tatnClose = 100.0,
            tatnpClose = 90.0,
            spreadPercent = spread,
            diff = 10.0,
            zScore = 0.0,
        )
    }
}
