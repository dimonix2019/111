package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexChartInteractionTest {

    @Test
    fun chartViewportState_zoomX_keepsFocusPoint() {
        val state = ChartViewportState(
            initialWindowWidth = 0.5f,
            initialWindowStart = 0.25f,
            dataYMin = -2.0,
            dataYMax = 2.0,
        )
        val anchorBefore = state.windowStart + 0.5f * state.windowWidth
        state.zoomX(0.5f, focusFrac = 0.5f)
        val anchorAfter = state.windowStart + 0.5f * state.windowWidth
        assertEquals(anchorBefore, anchorAfter, 1e-4f)
        assertTrue(state.windowWidth < 0.5f)
    }

    @Test
    fun chartViewportState_resetWindow_restoresY() {
        val state = ChartViewportState(1f, 0f, -1.0, 1.0)
        state.zoomY(2f)
        state.panY(0.5)
        state.resetWindow(0.4f, 0.6f)
        assertEquals(0.4f, state.windowWidth, 1e-4f)
        assertEquals(0.6f, state.windowStart, 1e-4f)
        assertEquals(1f, state.yZoom, 1e-4f)
    }
}
