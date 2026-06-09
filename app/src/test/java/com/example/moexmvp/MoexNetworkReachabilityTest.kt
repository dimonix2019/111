package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class MoexNetworkReachabilityTest {

    @Test
    fun nextSignalMonitorDelayMs_staysAtBaseWhenNoFailures() {
        assertEquals(SIGNAL_MONITOR_INTERVAL_MS, nextSignalMonitorDelayMs(0))
    }

    @Test
    fun nextSignalMonitorDelayMs_exponentialBackoffCappedAtMax() {
        assertEquals(15_000L, nextSignalMonitorDelayMs(1))
        assertEquals(30_000L, nextSignalMonitorDelayMs(2))
        assertEquals(60_000L, nextSignalMonitorDelayMs(3))
        assertEquals(120_000L, nextSignalMonitorDelayMs(4))
        assertEquals(240_000L, nextSignalMonitorDelayMs(5))
        assertEquals(SIGNAL_MONITOR_MAX_BACKOFF_MS, nextSignalMonitorDelayMs(6))
        assertEquals(SIGNAL_MONITOR_MAX_BACKOFF_MS, nextSignalMonitorDelayMs(8))
    }

    @Test
    fun isTransientNetworkFailure_recognizesCommonNetworkErrors() {
        assertTrue(isTransientNetworkFailure(UnknownHostException("iss.moex.com")))
        assertTrue(isTransientNetworkFailure(ConnectException("refused")))
        assertTrue(isTransientNetworkFailure(SocketTimeoutException("timeout")))
        assertTrue(isTransientNetworkFailure(SSLException("handshake")))
        assertTrue(
            isTransientNetworkFailure(
                RuntimeException("wrapped", UnknownHostException("iss.moex.com")),
            ),
        )
    }

    @Test
    fun isTransientNetworkFailure_rejectsNonNetworkErrors() {
        assertFalse(isTransientNetworkFailure(IllegalStateException("logic")))
        assertFalse(isTransientNetworkFailure(NullPointerException()))
    }
}
