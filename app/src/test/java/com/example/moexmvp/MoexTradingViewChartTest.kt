package com.example.moexmvp

import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class MoexTradingViewChartTest {

    @Test
    fun buildTradingViewChartPayloadJson_includesCandlesAndMarkers() {
        val candles = listOf(
            CandlePoint("2026-05-19 10:00", open = 0.0, high = 0.1, low = -0.1, close = 0.05),
            CandlePoint("2026-05-19 10:15", open = 0.05, high = 0.2, low = 0.0, close = 0.1),
        )
        val points = candles.map { c ->
            val ts = LocalDateTime.parse(c.label, portfolio15mLabelFormatter)
                .atZone(ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli()
            DataPoint(ts, c.label, 100.0, 90.0, 10.0, 10.0, c.close)
        }
        val markers = listOf(
            ChartPointMarker(
                index = 0,
                value = 0.0,
                color = Color(0xFF69F0AE),
                label = "Вх 1А",
                shape = ChartMarkerShape.TriangleUp,
                badgeText = "1А",
            )
        )
        val json = JSONObject(
            buildTradingViewChartPayloadJson(
                candles = candles,
                displayPoints = points,
                referenceLines = listOf(
                    ChartReferenceLine(1.3, Color.White, "+Entry")
                ),
                pointMarkers = markers,
                initialWindowWidth = 0.5f,
                initialWindowStart = 0.2f,
            )
        )
        assertEquals(2, json.getJSONArray("candles").length())
        assertEquals(1, json.getJSONArray("hlines").length())
        assertEquals(1, json.getJSONArray("markers").length())
        assertEquals(0.2, json.getJSONObject("window").getDouble("start"), 0.001)
    }

    @Test
    fun m15CandleLabelToUnixSec_matchesMoscowWallClock() {
        val sec = m15CandleLabelToUnixSec("2026-05-19 10:00")
        assertTrue(sec > 1_700_000_000L)
    }

    @Test
    fun tradingViewMarkerFromChartMarker_mapsShortEntry() {
        val m = tradingViewMarkerFromChartMarker(
            ChartPointMarker(
                index = 0,
                value = 0.0,
                color = Color(0xFFFF8A80),
                label = "Вх 2Р",
                shape = ChartMarkerShape.TriangleDown,
                badgeText = "2Р",
            ),
            barTimeSec = 1_768_000_000L,
        )
        assertEquals("aboveBar", m.position)
        assertEquals("arrowDown", m.shape)
        assertEquals("2Р", m.text)
    }
}
