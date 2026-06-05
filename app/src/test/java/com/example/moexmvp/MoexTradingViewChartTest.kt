package com.example.moexmvp

import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class MoexTradingViewChartTest {

    private fun point(label: String, z: Double): DataPoint {
        val ts = LocalDateTime.parse(label, portfolio15mLabelFormatter)
            .atZone(ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli()
        return DataPoint(ts, label, 100.0, 90.0, 10.0, 10.0, z)
    }

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
    fun tradingViewMarkerDisplayText_transliteratesCyrillicTypeLetters() {
        assertEquals("1A", tradingViewMarkerDisplayText("1А"))
        assertEquals("2R", tradingViewMarkerDisplayText("2Р"))
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
        assertEquals("2R", m.text)
    }

    @Test
    fun buildTradingViewChartPayloadJson_includesTradesWithoutSignalMarkers() {
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
                value = 1.2,
                color = Color(0xFF69F0AE),
                label = "Вх 1А",
                shape = ChartMarkerShape.TriangleUp,
                badgeText = "1А",
            ),
            ChartPointMarker(
                index = 1,
                value = 0.3,
                color = Color(0xFFFFCC80),
                label = "Вых 1А",
                shape = ChartMarkerShape.Diamond,
                badgeText = "1А",
            ),
        )
        val entrySec = m15CandleLabelToUnixSec("2026-05-19 10:00")
        val exitSec = m15CandleLabelToUnixSec("2026-05-19 10:15")
        val segments = listOf(
            TradingViewTradeSegment(
                id = "1A",
                entryTimeSec = entrySec,
                exitTimeSec = exitSec,
                entryZ = 1.2,
                exitZ = 0.3,
                isOpen = false,
            )
        )
        val json = JSONObject(
            buildTradingViewChartPayloadJson(
                candles = candles,
                displayPoints = points,
                referenceLines = emptyList(),
                pointMarkers = markers,
                tradeSegments = segments,
            )
        )
        assertEquals(2, json.getJSONArray("markers").length())
        val marker0 = json.getJSONArray("markers").getJSONObject(0)
        assertEquals("1A", marker0.getString("text"))
        assertTrue(marker0.getBoolean("isEntry"))
        val trade0 = json.getJSONArray("trades").getJSONObject(0)
        assertEquals("1A", trade0.getString("id"))
        assertEquals(entrySec, trade0.getLong("entryTime"))
        assertEquals(exitSec, trade0.getLong("exitTime"))
    }

    @Test
    fun buildTradingViewTradeSegments_mapsClosedAndOpenTrades() {
        val points = listOf(
            point("2026-05-19 10:00", z = 1.0),
            point("2026-05-19 10:15", z = 0.5),
        )
        val closed = listOf(
            PortfolioConfirmedTradeTableRow(
                tradeId = "t1",
                directionLabel = "LONG",
                entryTimeMsk = "2026-05-19 10:00",
                exitTimeMsk = "2026-05-19 10:15",
                longLegTicker = "TATN",
                shortLegTicker = "TATNP",
                longLegSideRu = "Покупка",
                shortLegSideRu = "Продажа",
                volumeText = "1",
                confirmLabel = "авто",
                entryZ = 1.0,
                exitZ = 0.3,
                notificationIdsText = "—",
                legLongPnlSplitRubApprox = 10.0,
                legShortPnlSplitRubApprox = -5.0,
                grossPnlRubApprox = 5.0,
                netPnlRubApprox = 4.0,
                tradeDisplayId = "1",
            )
        )
        val opens = listOf(
            SandboxSpreadExecUi(
                tradeId = "t2",
                signalType = StrategySignalType.EnterLong,
                zScore = 1.5,
                barTimestampMillis = points[1].timestampMillis,
                executedAtMillis = points[1].timestampMillis,
                entrySpreadPercent = 10.0,
                source = PortfolioExecSource.MANUAL,
                directionLabel = "LONG",
                entryTimeMsk = "2026-05-19 10:15",
                longLegTicker = "TATN",
                shortLegTicker = "TATNP",
                longLegSideRu = "Покупка",
                shortLegSideRu = "Продажа",
                volumeText = "1",
                confirmLabel = "ручное",
                correlationTag = "—",
                notificationIdsText = "—",
                legs = emptyList(),
                exitZDisplay = 0.7,
                tradeDisplayId = "2",
            )
        )
        val segments = buildTradingViewTradeSegments(opens, closed, points)
        assertEquals(2, segments.size)
        val closedSeg = segments.first { !it.isOpen }
        assertEquals("1A", closedSeg.id)
        assertEquals(1.0, closedSeg.entryZ, 1e-9)
        assertEquals(0.3, closedSeg.exitZ!!, 1e-9)
        val openSeg = segments.first { it.isOpen }
        assertEquals("2R", openSeg.id)
        assertEquals(0.7, openSeg.exitZ!!, 1e-9)
        assertEquals(points.last().timestampMillis / 1000L, openSeg.exitTimeSec)
    }
}
