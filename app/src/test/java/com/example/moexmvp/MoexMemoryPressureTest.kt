package com.example.moexmvp

import android.content.ComponentCallbacks2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexMemoryPressureTest {
    @Test
    fun shouldPauseAutoRefresh_atRunningLow() {
        assertFalse(MoexMemoryPressure.shouldPauseAutoRefresh(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertTrue(MoexMemoryPressure.shouldPauseAutoRefresh(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertTrue(MoexMemoryPressure.shouldPauseAutoRefresh(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
    }

    @Test
    fun autoPollIntervalMs_slowsUnderModeratePressure() {
        assertTrue(MoexMemoryPressure.autoPollIntervalMs(0) == FIXED_REALTIME_INTERVAL_MS)
        assertTrue(MoexMemoryPressure.autoPollIntervalMs(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) == 15_000L)
        assertTrue(MoexMemoryPressure.autoPollIntervalMs(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) == 0L)
    }

    @Test
    fun mayRunMarketsRefresh_autoPollRequiresForegroundMarketsTab() {
        assertTrue(
            mayRunMarketsRefresh(
                tab = MainTab.Markets,
                activityResumed = true,
                realtimeEnabled = true,
                memoryPressureLevel = 0,
                policy = MarketsRefreshPolicy.AutoPoll,
            ),
        )
        assertFalse(
            mayRunMarketsRefresh(
                tab = MainTab.StrategyTest,
                activityResumed = true,
                realtimeEnabled = true,
                memoryPressureLevel = 0,
                policy = MarketsRefreshPolicy.AutoPoll,
            ),
        )
        assertFalse(
            mayRunMarketsRefresh(
                tab = MainTab.Markets,
                activityResumed = false,
                realtimeEnabled = true,
                memoryPressureLevel = 0,
                policy = MarketsRefreshPolicy.AutoPoll,
            ),
        )
        assertFalse(
            mayRunMarketsRefresh(
                tab = MainTab.Markets,
                activityResumed = true,
                realtimeEnabled = true,
                memoryPressureLevel = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
                policy = MarketsRefreshPolicy.AutoPoll,
            ),
        )
    }

    @Test
    fun mayRunMarketsRefresh_userInitiatedRequiresResumedMarketsTab() {
        assertFalse(
            mayRunMarketsRefresh(
                tab = MainTab.Markets,
                activityResumed = false,
                realtimeEnabled = true,
                memoryPressureLevel = 0,
                policy = MarketsRefreshPolicy.UserInitiated,
            ),
        )
        assertTrue(
            mayRunMarketsRefresh(
                tab = MainTab.Markets,
                activityResumed = true,
                realtimeEnabled = false,
                memoryPressureLevel = 0,
                policy = MarketsRefreshPolicy.UserInitiated,
            ),
        )
    }
}
