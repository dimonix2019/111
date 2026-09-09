package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class MoexMarketsPipelineGuardTest {

    private fun point(label: String, z: Double): DataPoint {
        val ts = LocalDateTime.parse(label, portfolio15mLabelFormatter)
            .atZone(ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli()
        return DataPoint(ts, label, 100.0, 90.0, 10.0, 10.0, z)
    }

    @Test
    fun validateMarketsM15CanonicalSeries_flagsDuplicateLabels() {
        val points = listOf(
            point("2026-06-23 17:30", 0.6),
            point("2026-06-23 17:45", 0.7),
            point("2026-06-23 17:45", 0.8),
        )
        val issues = validateMarketsM15CanonicalSeries(points)
        assertTrue(issues.any { it.code == "dup_label" })
    }

    @Test
    fun validateMarketsLiveOverlay_flagsFrozenLiveOnClosedBar() {
        val base = listOf(
            point("2026-06-23 17:30", 0.65),
            point("2026-06-23 17:45", 0.70),
        )
        val wronglyPatched = listOf(
            point("2026-06-23 17:30", 0.65),
            point("2026-06-23 17:45", -2.59),
        )
        val issues = validateMarketsLiveOverlay(
            basePoints = base,
            patchedPoints = wronglyPatched,
            liveZ = -2.59,
            liveBarAt = "2026-06-23 22:30",
        )
        assertTrue(issues.any { it.code == "frozen_live" || it.code == "live_on_closed" })
    }

    @Test
    fun validateMarketsLiveOverlay_okWhenFormingBarAppended() {
        val base = listOf(
            point("2026-06-23 17:45", 0.70),
        )
        val withForming = listOf(
            point("2026-06-23 17:45", 0.70),
            point("2026-06-23 22:30", -2.74),
        )
        val chart = applyLiveZToM15ChartSeries(
            points = base,
            candles = buildZScoreCandlesFromM15Points(base),
            liveZ = -2.74,
            liveBarAt = "2026-06-23 22:30",
        )
        val overlayIssues = validateMarketsLiveOverlay(
            basePoints = base,
            patchedPoints = chart.first,
            liveZ = -2.74,
            liveBarAt = "2026-06-23 22:30",
        )
        assertTrue(overlayIssues.none { it.code == "frozen_live" })
        assertEquals(withForming.size, chart.first.size)
        assertEquals(-2.74, chart.first.last().zScore, 1e-9)
    }

    @Test
    fun validateMarketsUiSnapshot_flagsCachePortfolioTailMismatch() {
        val cache = listOf(point("2026-06-23 17:45", 0.7))
        val portfolio = listOf(point("2026-06-23 15:00", 0.6))
        val issues = validateMarketsUiSnapshot(cache, portfolio, liveZ = null, liveBarAt = null)
        assertTrue(issues.any { it.code == "cache_portfolio_tail" })
    }

    @Test
    fun expectedM15Gaps_skipNightAndEveningAuction() {
        assertTrue(isExpectedM15CalendarGapMinutes(15))
        assertTrue(isExpectedM15CalendarGapMinutes(30))
        assertTrue(isExpectedM15CalendarGapMinutes(435))
        assertTrue(!isExpectedM15CalendarGapMinutes(45))
        val overnight = listOf(
            point("2026-09-04 18:30", 0.5),
            point("2026-09-04 19:00", 0.6),
            point("2026-09-04 23:30", 0.7),
            point("2026-09-05 06:45", 0.8),
        )
        val issues = validateMarketsM15CanonicalSeries(overnight)
        assertTrue(issues.none { it.code == "gap" })
    }

    @Test
    fun formatPipelineIssues_summarizesRepeats() {
        val text = formatMarketsM15PipelineIssues(
            listOf(
                MarketsM15PipelineIssue("gap", "a"),
                MarketsM15PipelineIssue("gap", "b"),
            ),
        )
        assertTrue(text.contains("gap x2"))
        assertTrue(text.length < 80)
    }
}
