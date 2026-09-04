package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonExtraLevelAlertsTest {

    @Test
    fun detectAddonCrosses_longDownThrough2() {
        val crosses = detectAddonExtraLevelCrosses(
            prevSpread = 2.2,
            currSpread = 1.9,
            kind = AddonExtraKind.Addon,
            longEnterPct = 2.0,
            shortEnterPct = 7.0,
        )
        assertEquals(listOf(AddonExtraAlertSlot.AddonLong), crosses)
    }

    @Test
    fun detectAddonCrosses_shortUpThrough7() {
        val crosses = detectAddonExtraLevelCrosses(
            prevSpread = 6.8,
            currSpread = 7.1,
            kind = AddonExtraKind.Addon,
            longEnterPct = 2.0,
            shortEnterPct = 7.0,
        )
        assertEquals(listOf(AddonExtraAlertSlot.AddonShort), crosses)
    }

    @Test
    fun detectExtraCrosses_longDownThrough1() {
        val crosses = detectAddonExtraLevelCrosses(
            prevSpread = 1.2,
            currSpread = 0.9,
            kind = AddonExtraKind.Extra,
            longEnterPct = 1.0,
            shortEnterPct = 9.0,
        )
        assertEquals(listOf(AddonExtraAlertSlot.ExtraLong), crosses)
    }

    @Test
    fun detectExtraCrosses_shortUpThrough9() {
        val crosses = detectAddonExtraLevelCrosses(
            prevSpread = 8.8,
            currSpread = 9.2,
            kind = AddonExtraKind.Extra,
            longEnterPct = 1.0,
            shortEnterPct = 9.0,
        )
        assertEquals(listOf(AddonExtraAlertSlot.ExtraShort), crosses)
    }

    @Test
    fun detectAddonExtraLevelCrosses_noCrossInsideBand() {
        assertTrue(
            detectAddonExtraLevelCrosses(3.0, 5.0, AddonExtraKind.Addon, 2.0, 7.0).isEmpty(),
        )
    }

    @Test
    fun parseAddonExtraOpenFromDeskMessage_autoAddonLong() {
        assertEquals(
            AddonExtraAlertSlot.AddonLong,
            parseAddonExtraOpenFromDeskMessage("AUTO_ADDON добор LONG · 1+1 лот · Z=— · бар 2026-09-04 10:00"),
        )
    }

    @Test
    fun parseAddonExtraOpenFromDeskMessage_autoExtraShort() {
        assertEquals(
            AddonExtraAlertSlot.ExtraShort,
            parseAddonExtraOpenFromDeskMessage("AUTO экстра SHORT"),
        )
    }

    @Test
    fun parseAddonExtraOpenFromDeskMessage_ignoresSignalAndExit() {
        assertNull(parseAddonExtraOpenFromDeskMessage("Сигнал добор LONG @ 2026-09-04 (вход 2/7)"))
        assertNull(parseAddonExtraOpenFromDeskMessage("AUTO_ADDON_EXIT добор выход LONG"))
        assertNull(parseAddonExtraOpenFromDeskMessage("AUTO fail добор LONG: нет средств"))
    }

    @Test
    fun addonExtraAlertNotificationId_uniquePerSlot() {
        val ids = AddonExtraAlertSlot.entries.map(::addonExtraAlertNotificationId).toSet()
        assertEquals(4, ids.size)
    }

    @Test
    fun defaultThresholds_matchProdDesk() {
        assertEquals(2.0, DEFAULT_ADDON_ENTER_NARROW, 0.0)
        assertEquals(7.0, DEFAULT_ADDON_ENTER_WIDE, 0.0)
        assertEquals(1.0, DEFAULT_EXTRA_ENTER_NARROW, 0.0)
        assertEquals(9.0, DEFAULT_EXTRA_ENTER_WIDE, 0.0)
    }
}
