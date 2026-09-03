package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpreadLevelAlertsTest {

    @Test
    fun detectSpreadLevelCrosses_downThroughMultipleLevels() {
        val crosses = detectSpreadLevelCrosses(2.6, 0.4)
        assertEquals(
            listOf(
                SpreadLevelCross(0.5, SpreadLevelCrossDirection.Down),
                SpreadLevelCross(1.0, SpreadLevelCrossDirection.Down),
                SpreadLevelCross(2.0, SpreadLevelCrossDirection.Down),
                SpreadLevelCross(2.5, SpreadLevelCrossDirection.Down),
            ),
            crosses,
        )
    }

    @Test
    fun detectSpreadLevelCrosses_upThroughLevels() {
        val crosses = detectSpreadLevelCrosses(0.4, 2.6)
        assertEquals(
            listOf(
                SpreadLevelCross(0.5, SpreadLevelCrossDirection.Up),
                SpreadLevelCross(1.0, SpreadLevelCrossDirection.Up),
                SpreadLevelCross(2.0, SpreadLevelCrossDirection.Up),
                SpreadLevelCross(2.5, SpreadLevelCrossDirection.Up),
            ),
            crosses,
        )
    }

    @Test
    fun detectSpreadLevelCrosses_singleLevelTouch() {
        val down = detectSpreadLevelCrosses(1.05, 0.95)
        assertEquals(listOf(SpreadLevelCross(1.0, SpreadLevelCrossDirection.Down)), down)
        val up = detectSpreadLevelCrosses(0.95, 1.05)
        assertEquals(listOf(SpreadLevelCross(1.0, SpreadLevelCrossDirection.Up)), up)
    }

    @Test
    fun detectSpreadLevelCrosses_noCrossInsideBand() {
        assertTrue(detectSpreadLevelCrosses(1.2, 1.4).isEmpty())
    }

    @Test
    fun spreadLevelAlertNotificationId_uniquePerLevelAndDirection() {
        val up05 = spreadLevelAlertNotificationId(0.5, SpreadLevelCrossDirection.Up)
        val down05 = spreadLevelAlertNotificationId(0.5, SpreadLevelCrossDirection.Down)
        val up25 = spreadLevelAlertNotificationId(2.5, SpreadLevelCrossDirection.Up)
        assertTrue(up05 != down05)
        assertTrue(up05 != up25)
    }

    @Test
    fun parseSpreadLevelAlertCorrelationTag_parsesLevel() {
        assertEquals(0.5, parseSpreadLevelAlertCorrelationTag("spread_lvl_0.5_up")!!, 1e-9)
        assertEquals(2.5, parseSpreadLevelAlertCorrelationTag("spread_lvl_2.5_down")!!, 1e-9)
        assertEquals(null, parseSpreadLevelAlertCorrelationTag("web_desk_ev_1"))
    }

    @Test
    fun parseDisabledSpreadLevelKeysCsv_roundTrip() {
        val keys = setOf("0.5", "2.0")
        val csv = formatDisabledSpreadLevelKeysCsv(keys)
        assertEquals(keys, parseDisabledSpreadLevelKeysCsv(csv))
    }
}
