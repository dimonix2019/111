package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Test

class TinkoffSandboxTokenTest {

    @Test
    fun normalizeInvestToken_stripsBearerPrefixAndWhitespace() {
        assertEquals("t.abc123", normalizeInvestToken("  Bearer  t.abc123  "))
    }

    @Test
    fun normalizeInvestToken_stripsNonAsciiCharsFromPastedToken() {
        val degree = "\u00B0"
        val token = "t.${"x".repeat(40)}$degree${"y".repeat(10)}"
        assertEquals("t.${"x".repeat(40)}${"y".repeat(10)}", normalizeInvestToken(token))
    }

    @Test
    fun normalizeInvestToken_stripsNbspAndZeroWidthSpaces() {
        assertEquals("t.token", normalizeInvestToken("t.\u00A0to\u200Bken"))
    }
}
