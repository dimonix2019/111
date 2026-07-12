package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexZRegimeAnalyticsTest {

    @Test
    fun classify_negativeBand_whenMajorityInMinus2To0() {
        val zs = List(20) { -0.8 - (it % 5) * 0.1 }
        val regime = classifyZConcentrationRegime(zs)
        assertEquals(ZConcentrationRegime.NegativeBand, regime)
    }

    @Test
    fun classify_positiveBand_whenMajorityIn0To2() {
        val zs = List(20) { 0.6 + (it % 4) * 0.1 }
        val regime = classifyZConcentrationRegime(zs)
        assertEquals(ZConcentrationRegime.PositiveBand, regime)
    }

    @Test
    fun classify_neutral_whenTightAroundZero() {
        val zs = listOf(0.05, -0.08, 0.1, -0.04, 0.02, -0.06, 0.0, 0.07)
        val regime = classifyZConcentrationRegime(zs)
        assertEquals(ZConcentrationRegime.Neutral, regime)
    }

    @Test
    fun walkForward_producesFolds() {
        val windows = listOf(
            win("W1", -0.9, ZConcentrationRegime.NegativeBand),
            win("W2", -0.8, ZConcentrationRegime.NegativeBand),
            win("W3", -0.7, ZConcentrationRegime.NegativeBand),
            win("W4", -0.6, ZConcentrationRegime.NegativeBand),
            win("W5", -0.5, ZConcentrationRegime.NegativeBand),
            win("W6", -0.4, ZConcentrationRegime.NegativeBand),
            win("W7", 0.8, ZConcentrationRegime.PositiveBand),
        )
        val report = walkForwardZRegimeForecast(windows, minTrainWindows = 3)
        assertTrue(report.folds.isNotEmpty())
    }

    @Test
    fun zSignedBandShares_partitionBands() {
        val zs = listOf(-2.5, -1.0, -0.2, 0.3, 1.5, 2.5)
        val shares = zSignedBandShares(zs)
        assertEquals(2 / 6.0, shares.negativeBandShare, 0.001)
        assertEquals(2 / 6.0, shares.positiveBandShare, 0.001)
        assertEquals(1 / 6.0, shares.belowNegative2Share, 0.001)
        assertEquals(1 / 6.0, shares.abovePositive2Share, 0.001)
    }

    private fun win(id: String, meanZ: Double, regime: ZConcentrationRegime): ZRegimeWindowStats {
        val shares = when (regime) {
            ZConcentrationRegime.NegativeBand -> ZSignedBandShares(0.6, 0.2, 0.05, 0.05, 0.1)
            ZConcentrationRegime.PositiveBand -> ZSignedBandShares(0.2, 0.6, 0.05, 0.05, 0.1)
            else -> ZSignedBandShares(0.3, 0.3, 0.1, 0.1, 0.4)
        }
        return ZRegimeWindowStats(
            windowId = id,
            startLabel = "2026-01-01",
            endLabel = "2026-01-10",
            barCount = 100,
            meanZ = meanZ,
            medianZ = meanZ,
            meanAbsZ = kotlin.math.abs(meanZ),
            bandShares = shares,
            regime = regime,
        )
    }
}
