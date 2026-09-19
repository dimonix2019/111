package com.example.moexmvp

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class MoexApplicationExitTest {
    @Test
    fun otherExitNearPackageUpdate_isClassifiedAsLikelyReplacement() {
        val updatedAt = 1_000_000L
        assertEquals(
            "package_replaced_likely",
            classifyPreviousProcessExit(
                reason = ApplicationExitInfo.REASON_OTHER,
                exitTimestampMs = updatedAt - 3_000L,
                packageLastUpdateTimeMs = updatedAt,
            ),
        )
    }

    @Test
    fun laterOtherExit_isClassifiedAsRuntimeExit() {
        val updatedAt = 1_000_000L
        assertEquals(
            "runtime_exit",
            classifyPreviousProcessExit(
                reason = ApplicationExitInfo.REASON_OTHER,
                exitTimestampMs = updatedAt + 10 * 60_000L,
                packageLastUpdateTimeMs = updatedAt,
            ),
        )
    }
}
