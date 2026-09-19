package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Test

class WebDeskPollTest {
    @Test
    fun pollDelay_usesNormalCadenceBeforeAndAfterFirstFailure() {
        assertEquals(WEB_DESK_POLL_MS, webDeskPollDelayMs(0))
        assertEquals(WEB_DESK_POLL_MS, webDeskPollDelayMs(1))
    }

    @Test
    fun pollDelay_backsOffExponentiallyAndCapsAtFifteenMinutes() {
        assertEquals(WEB_DESK_POLL_MS * 2, webDeskPollDelayMs(2))
        assertEquals(WEB_DESK_POLL_MS * 4, webDeskPollDelayMs(3))
        assertEquals(WEB_DESK_POLL_MAX_BACKOFF_MS, webDeskPollDelayMs(20))
    }
}
