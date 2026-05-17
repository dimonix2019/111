package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MoexNotificationLogTest {

    @Test
    fun serialization_roundTrip_withVirtualTapFields() {
        val original = PushNotificationLogEntry(
            wallTimestampMillis = 1_700_000_000_000L,
            title = "Вход: LONG",
            body = "Z test",
            posted = true,
            skipReason = null,
            virtualTapSignalType = StrategySignalType.EnterLong.name,
            virtualTapZ = -1.85,
            virtualTapBarTimestampMillis = 1_699_999_000_000L
        )
        val back = pushNotificationLogEntrySerializationRoundTrip(original)
        assertNotNull(back)
        assertEquals(original, back)
    }

    @Test
    fun serialization_roundTrip_skippedWithReason() {
        val original = PushNotificationLogEntry(
            wallTimestampMillis = 100L,
            title = "T",
            body = "B",
            posted = false,
            skipReason = PushNotificationLogSkipReason.DUPLICATE_WITHIN_WINDOW,
            virtualTapSignalType = null,
            virtualTapZ = null,
            virtualTapBarTimestampMillis = null
        )
        assertEquals(original, pushNotificationLogEntrySerializationRoundTrip(original))
    }
}
