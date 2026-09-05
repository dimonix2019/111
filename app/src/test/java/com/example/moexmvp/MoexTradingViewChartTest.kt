package com.example.moexmvp

import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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
        assertTrue(json.getInt("rightOffsetBars") >= 12)
    }

    @Test
    fun buildTradingViewChartPayloadJson_includesSpreadLevelsZonesAndCurrentPrice() {
        val candles = listOf(
            CandlePoint("2026-09-04 10:00", 3.4, 3.5, 3.4, 3.5),
            CandlePoint("2026-09-04 10:01", 3.5, 3.6, 3.5, 3.6),
        )
        val points = candles.map { point(it.label, it.close) }
        val json = JSONObject(
            buildTradingViewChartPayloadJson(
                candles = candles,
                displayPoints = points,
                referenceLines = listOf(
                    ChartReferenceLine(3.2, Color.Blue, "L вх 3,2"),
                    ChartReferenceLine(4.0, Color.Green, "L вых 4"),
                    ChartReferenceLine(5.8, Color.Green, "S вых 5,8"),
                    ChartReferenceLine(6.1, Color.Blue, "S вх 6,1"),
                    ChartReferenceLine(3.6, Color.Yellow, "3,60"),
                ),
                pointMarkers = listOf(
                    ChartPointMarker(
                        index = 1,
                        value = 3.6,
                        color = Color.Green,
                        label = "Вх 1А",
                        shape = ChartMarkerShape.TriangleUp,
                        badgeText = "1А",
                    )
                ),
                zoneFills = listOf(
                    ChartZoneFill(3.2, 4.0, Color(0x3300BCD4)),
                    ChartZoneFill(4.0, 5.8, Color(0x33B76E2D)),
                    ChartZoneFill(5.8, 6.1, Color(0x38880E4F)),
                ),
            )
        )

        assertEquals(2, json.getJSONArray("candles").length())
        assertEquals(5, json.getJSONArray("hlines").length())
        assertEquals(3, json.getJSONArray("zones").length())
        assertEquals(1, json.getJSONArray("markers").length())
        assertEquals(3.2, json.getJSONArray("zones").getJSONObject(0).getDouble("low"), 0.001)
        assertTrue(json.getJSONArray("zones").getJSONObject(0).getString("color").startsWith("rgba("))
    }

    @Test
    fun formatTradingViewOhlcLegend_usesRussianDecimalsAndOhlcLabels() {
        assertEquals(
            "O 3,40  H 3,55  L 3,38  C 3,50",
            formatTradingViewOhlcLegend(open = 3.4, high = 3.55, low = 3.38, close = 3.5),
        )
        val last = CandlePoint("2026-09-05 12:01", open = 4.01, high = 4.20, low = 3.90, close = 4.10)
        assertEquals(
            "O 4,01  H 4,20  L 3,90  C 4,10",
            formatTradingViewOhlcLegend(last.open, last.high, last.low, last.close),
        )
    }

    @Test
    fun zChartHtml_hooksCrosshairOhlcToBridge() {
        val html = listOf(
            File("src/main/assets/tradingview/z_chart.html"),
            File("app/src/main/assets/tradingview/z_chart.html"),
        ).first { it.exists() }.readText()
        assertTrue(html.contains("subscribeCrosshairMove"))
        assertTrue(html.contains("MoexChartBridge.onOhlc"))
        assertTrue(html.contains("function pushOhlc"))
    }

    @Test
    fun injectTradingViewLibrary_buildsSelfContainedHtml() {
        val html = injectTradingViewLibrary(
            "<html><!-- INJECT_LIGHTWEIGHT_CHARTS --><script>window.updateMoexChart={}</script></html>",
            "window.LightweightCharts={};",
        )

        assertTrue(html.contains("window.LightweightCharts={};"))
        assertTrue(html.contains("window.updateMoexChart"))
        assertTrue(!html.contains("INJECT_LIGHTWEIGHT_CHARTS"))
    }

    @Test
    fun remapTradingViewTradeSegmentsToDisplayValues_usesSpreadPercent() {
        val points = listOf(
            point("2026-09-04 10:00", 3.4),
            point("2026-09-04 10:01", 3.6),
        )
        val segment = TradingViewTradeSegment(
            id = "1A",
            entryTimeSec = m15CandleLabelToUnixSec("2026-09-04 10:00"),
            exitTimeSec = m15CandleLabelToUnixSec("2026-09-04 10:01"),
            entryZ = -1.6,
            exitZ = -1.3,
            isOpen = false,
        )

        val remapped = remapTradingViewTradeSegmentsToDisplayValues(listOf(segment), points).single()
        assertEquals(3.4, remapped.entryZ, 0.001)
        assertEquals(3.6, remapped.exitZ ?: Double.NaN, 0.001)
    }

    @Test
    fun tradingViewZChartRightOffsetBars_usesTenPercentVisibleBars() {
        assertEquals(12, tradingViewZChartRightOffsetBars(barCount = 50, windowWidth = 0.2f))
        assertEquals(20, tradingViewZChartRightOffsetBars(barCount = 200, windowWidth = 1f))
    }

    @Test
    fun buildTradingViewChartPayloadJson_includesOptionalAreaFillColor() {
        val candles = listOf(
            CandlePoint("2026-05-19 10:00", open = 0.0, high = 0.1, low = -0.1, close = 0.05),
        )
        val points = listOf(point("2026-05-19 10:00", z = 0.05))
        val json = JSONObject(
            buildTradingViewChartPayloadJson(
                candles = candles,
                displayPoints = points,
                referenceLines = emptyList(),
                pointMarkers = emptyList(),
                areaFillColor = STRATEGY_TEST_Z_CHART_AREA_FILL_HEX,
            )
        )
        assertEquals(STRATEGY_TEST_Z_CHART_AREA_FILL_HEX, json.getString("areaFillColor"))
    }

    @Test
    fun buildTradingViewChartPayloadJson_marksFormingBarCandle() {
        val label = "2026-05-19 10:15"
        val candles = listOf(
            CandlePoint("2026-05-19 10:00", open = 0.0, high = 0.1, low = -0.1, close = 0.05),
            CandlePoint(label, open = 0.05, high = 0.2, low = -2.0, close = -2.0),
        )
        val points = candles.map { c ->
            val ts = LocalDateTime.parse(c.label, portfolio15mLabelFormatter)
                .atZone(ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli()
            DataPoint(ts, c.label, 100.0, 90.0, 10.0, 10.0, c.close)
        }
        val hint = MarketsFormingBarHint(
            barLabel = label,
            barTimeSec = m15CandleLabelToUnixSec(label),
            liveZ = -2.0,
            baseCloseZ = 0.05,
        )
        val json = JSONObject(
            buildTradingViewChartPayloadJson(
                candles = candles,
                displayPoints = points,
                referenceLines = emptyList(),
                pointMarkers = emptyList(),
                formingBar = hint,
            )
        )
        val forming = json.getJSONObject("formingBar")
        assertEquals(hint.barTimeSec, forming.getLong("time"))
        assertEquals(-2.0, forming.getDouble("liveZ"), 1e-9)
        val lastCandle = json.getJSONArray("candles").getJSONObject(1)
        assertEquals(CHART_FORMING_BAR_BORDER_HEX, lastCandle.getString("borderColor"))
    }

    @Test
    fun buildTradingViewChartPayloadJson_markerTradeIdMatchesTradeSegment() {
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
            ),
            ChartPointMarker(
                index = 1,
                value = 0.1,
                color = Color(0xFFFFCC80),
                label = "Вых 1А",
                shape = ChartMarkerShape.Diamond,
                badgeText = "1А",
            ),
        )
        val segments = listOf(
            TradingViewTradeSegment(
                id = "1A",
                entryTimeSec = m15CandleLabelToUnixSec("2026-05-19 10:00"),
                exitTimeSec = m15CandleLabelToUnixSec("2026-05-19 10:15"),
                entryZ = 0.0,
                exitZ = 0.1,
                isOpen = false,
            ),
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
        val marker0 = json.getJSONArray("markers").getJSONObject(0)
        assertEquals("1A", marker0.getString("tradeId"))
        assertEquals("1A", json.getJSONArray("trades").getJSONObject(0).getString("id"))
    }

    @Test
    fun remapChartMarkersToDisplaySeries_keepsStrategyTestMarkersAfterDownsample() {
        val source = (0 until 20).map { i ->
            val label = LocalDateTime.of(2026, 5, 19, 10, 0).plusMinutes(i * 15L)
                .format(portfolio15mLabelFormatter)
            point(label, z = i * 0.01)
        }
        val display = source.filterIndexed { index, _ -> index % 2 == 0 }
        val markers = buildZScoreMarkersFromStrategyTestTrades(
            source,
            listOf(
                StrategyTestTradeItem(
                    trade = PortfolioClosedTrade(
                        direction = ZStrategyPosition.Long,
                        entryDate = source[2].tradeDate,
                        exitDate = source[4].tradeDate,
                        entrySpreadPercent = 10.0,
                        exitSpreadPercent = 10.4,
                        pnlSpreadPoints = 0.4,
                        grossPnlRubApprox = 100.0,
                        pnlRubApprox = 90.0,
                    ),
                ),
            ),
        )
        val remapped = remapChartMarkersToDisplaySeries(source, display, markers)
        assertEquals(2, remapped.size)
        assertTrue(remapped.all { it.index in display.indices })
    }

    @Test
    fun strategyTestChartPointMarkersPath_matchesMarketsStyleAfterRemap() {
        val full = (0 until 120).map { i ->
            val label = LocalDateTime.of(2026, 1, 2, 10, 0).plusMinutes(i * 15L)
                .format(portfolio15mLabelFormatter)
            point(label, z = i * 0.01)
        }
        val (display, displayCandles) = downsampleM15ChartSeries(
            full,
            buildZScoreCandlesFromM15Points(full),
            maxBars = 12,
        )
        val tradeItems = listOf(
            StrategyTestTradeItem(
                trade = PortfolioClosedTrade(
                    direction = ZStrategyPosition.Long,
                    entryDate = full[15].tradeDate,
                    exitDate = full[45].tradeDate,
                    entrySpreadPercent = 10.0,
                    exitSpreadPercent = 10.4,
                    pnlSpreadPoints = 0.4,
                    grossPnlRubApprox = 100.0,
                    pnlRubApprox = 90.0,
                ),
            ),
        )
        val sourceMarkers = buildZScoreMarkersFromStrategyTestTrades(full, tradeItems)
        val remapped = remapChartMarkersToDisplaySeries(full, display, sourceMarkers)
        assertEquals(2, remapped.size)
        val payload = JSONObject(
            buildTradingViewChartPayloadJson(
                candles = displayCandles,
                displayPoints = display,
                referenceLines = emptyList(),
                pointMarkers = remapped,
                tradeSegments = buildTradingViewTradeSegmentsFromStrategyTest(
                    tradeItems,
                    display,
                    displayCandles,
                ),
            ),
        )
        assertEquals(2, payload.getJSONArray("markers").length())
        val candleTimes = displayCandles.map { m15CandleLabelToUnixSec(it.label) }.toSet()
        for (i in 0 until payload.getJSONArray("markers").length()) {
            val t = payload.getJSONArray("markers").getJSONObject(i).getLong("time")
            assertTrue(t in candleTimes)
        }
    }

    @Test
    fun buildZScoreMarkersFromStrategyTestTrades_onDownsampledDisplaySeries() {
        val full = (0 until 120).map { i ->
            val label = LocalDateTime.of(2026, 1, 2, 10, 0).plusMinutes(i * 15L)
                .format(portfolio15mLabelFormatter)
            point(label, z = i * 0.01)
        }
        val (display, displayCandles) = downsampleM15ChartSeries(
            full,
            buildZScoreCandlesFromM15Points(full),
            maxBars = 12,
        )
        val tradeItems = listOf(
            StrategyTestTradeItem(
                trade = PortfolioClosedTrade(
                    direction = ZStrategyPosition.Long,
                    entryDate = full[15].tradeDate,
                    exitDate = full[45].tradeDate,
                    entrySpreadPercent = 10.0,
                    exitSpreadPercent = 10.4,
                    pnlSpreadPoints = 0.4,
                    grossPnlRubApprox = 100.0,
                    pnlRubApprox = 90.0,
                ),
            ),
        )
        val markers = buildZScoreMarkersFromStrategyTestTrades(display, tradeItems)
        assertEquals(2, markers.size)
        val candleTimes = displayCandles.map { m15CandleLabelToUnixSec(it.label) }.toSet()
        val payload = JSONObject(
            buildTradingViewChartPayloadJson(
                candles = displayCandles,
                displayPoints = display,
                referenceLines = emptyList(),
                pointMarkers = emptyList(),
                tradeSegments = buildTradingViewTradeSegmentsFromStrategyTest(
                    tradeItems,
                    display,
                    displayCandles,
                ),
                strategyTestTradeItems = tradeItems,
            ),
        )
        assertTrue(payload.getJSONArray("markers").length() >= 2)
        assertEquals(1, payload.getJSONArray("trades").length())
        for (i in 0 until payload.getJSONArray("markers").length()) {
            val t = payload.getJSONArray("markers").getJSONObject(i).getLong("time")
            assertTrue("marker time $t must match a candle", candleTimes.contains(t))
        }
        val tradeEntry = payload.getJSONArray("trades").getJSONObject(0).getLong("entryTime")
        assertTrue(candleTimes.contains(tradeEntry))
    }

    @Test
    fun buildStrategyTestTradingViewMarkerBuckets_snapsAndMergesOnDownsampledSeries() {
        val full = (0 until 200).map { i ->
            val label = LocalDateTime.of(2026, 1, 2, 10, 0).plusMinutes(i * 15L)
                .format(portfolio15mLabelFormatter)
            val ts = LocalDateTime.parse(label, portfolio15mLabelFormatter)
                .atZone(ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli()
            DataPoint(ts, label, 100.0, 90.0, 10.0, 10.0, zScore = i * 0.01)
        }
        val (display, candles) = downsampleM15ChartSeries(
            full,
            buildZScoreCandlesFromM15Points(full),
            maxBars = 40,
        )
        val tradeItems = (0 until 12).map { i ->
            StrategyTestTradeItem(
                trade = PortfolioClosedTrade(
                    direction = if (i % 2 == 0) ZStrategyPosition.Long else ZStrategyPosition.Short,
                    entryDate = full[20 + i * 12].tradeDate,
                    exitDate = full[24 + i * 12].tradeDate,
                    entrySpreadPercent = 10.0,
                    exitSpreadPercent = 10.4,
                    pnlSpreadPoints = 0.4,
                    grossPnlRubApprox = 100.0,
                    pnlRubApprox = 90.0,
                ),
            )
        }
        val candleTimes = candles.map { m15CandleLabelToUnixSec(it.label) }.toSet()
        val json = JSONObject(
            buildTradingViewChartPayloadJson(
                candles = candles,
                displayPoints = display,
                referenceLines = emptyList(),
                pointMarkers = emptyList(),
                tradeSegments = buildTradingViewTradeSegmentsFromStrategyTest(tradeItems, display, candles),
                strategyTestTradeItems = tradeItems,
            ),
        )
        val markerCount = json.getJSONArray("markers").length()
        assertTrue("expected many markers, got $markerCount", markerCount >= 12)
        for (i in 0 until markerCount) {
            val t = json.getJSONArray("markers").getJSONObject(i).getLong("time")
            assertTrue(t in candleTimes)
        }
    }

    @Test
    fun buildStrategyTestTradingViewMarkerBuckets_openPositionHasEntryOnly() {
        val candles = listOf(
            CandlePoint("2026-05-19 10:00", open = 0.0, high = 0.1, low = -0.1, close = 0.05),
            CandlePoint("2026-05-19 10:15", open = 0.05, high = 0.2, low = 0.0, close = 0.1),
        )
        val points = candles.map { point(it.label, z = it.close) }
        val open = PortfolioOpenPosition(
            direction = ZStrategyPosition.Long,
            entryDate = "2026-05-19 10:00",
            entrySpreadPercent = 10.0,
            lastSpreadPercent = 10.2,
            unrealizedPnlSpread = 0.2,
            unrealizedRubApprox = 50.0,
        )
        val json = JSONObject(
            buildTradingViewChartPayloadJson(
                candles = candles,
                displayPoints = points,
                referenceLines = emptyList(),
                pointMarkers = emptyList(),
                tradeSegments = buildTradingViewTradeSegmentsFromStrategyTest(emptyList(), points, candles, open),
                strategyTestTradeItems = emptyList(),
                openPosition = open,
            ),
        )
        assertEquals(1, json.getJSONArray("markers").length())
        val marker = json.getJSONArray("markers").getJSONObject(0)
        assertEquals("1A", marker.getString("text"))
        assertEquals(true, marker.getBoolean("isEntry"))
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

    @Test
    fun buildTradingViewReplayCursorJson_includesCandlesMarkersAndPlayingFlag() {
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
                label = "Вх Long",
                shape = ChartMarkerShape.TriangleUp,
                badgeText = "1А",
            ),
        )
        val json = JSONObject(
            buildTradingViewReplayCursorJson(
                candles = candles,
                displayPoints = points,
                referenceLines = listOf(ChartReferenceLine(0.7, Color.White, "+Entry")),
                pointMarkers = markers,
                playing = true,
            ),
        )
        assertEquals(2, json.getJSONArray("candles").length())
        assertEquals(1, json.getJSONArray("hlines").length())
        assertEquals(1, json.getJSONArray("markers").length())
        assertTrue(json.getBoolean("playing"))
        assertEquals(1.0, json.getDouble("windowWidth"), 0.001)
    }
}
