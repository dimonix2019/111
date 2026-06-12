package com.example.moexmvp

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.time.LocalDate

/** Parity: экспорт журнала пользователя (1.7.53) vs sim на MOEX 10–11.06.2026. */
class UserJournalJune1011ParityTest {

    private fun loadJournal(): Pair<DynamicThresholds, List<StrategySignalEvent>> {
        val stream = javaClass.classLoader.getResourceAsStream("user_journal_june1011.json")
            ?: error("missing user_journal_june1011.json")
        val o = JSONObject(stream.readBytes().toString(StandardCharsets.UTF_8))
        val th = o.getJSONObject("thresholds")
        val events = buildList {
            val arr = o.getJSONArray("events")
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                add(
                    StrategySignalEvent(
                        timestampMillis = e.getLong("timestampMillis"),
                        signalType = StrategySignalType.valueOf(e.getString("signalType")),
                        zScore = e.getDouble("zScore"),
                        receivedAtMillis = e.getLong("receivedAtMillis"),
                    ),
                )
            }
        }
        return DynamicThresholds(th.getDouble("entry"), th.getDouble("exit"), null) to events
    }

    private fun journalRoundTrips(events: List<StrategySignalEvent>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var entry: String? = null
        for (e in events.sortedBy { it.timestampMillis }) {
            when (e.signalType) {
                StrategySignalType.EnterLong, StrategySignalType.EnterShort ->
                    entry = formatPortfolioExecutionTableMsk(e.timestampMillis)
                StrategySignalType.ExitLong, StrategySignalType.ExitShort -> {
                    if (entry != null) out += entry to formatPortfolioExecutionTableMsk(e.timestampMillis)
                    entry = null
                }
            }
        }
        return out
    }

    private fun simShortTrips(points: List<DataPoint>, thresholds: DynamicThresholds) =
        buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "user-parity",
        )?.closedTrades.orEmpty()
            .filter {
                it.direction == ZStrategyPosition.Short &&
                    (it.entryDate.startsWith("2026-06-10") || it.entryDate.startsWith("2026-06-11"))
            }
            .map { it.entryDate to it.exitDate }

    @Test
    fun userJournal_vs_sim_june1011_fourRoundTripsMatch() = runBlocking {
        val (thresholds, events) = loadJournal()
        val journal = journalRoundTrips(events)
        val raw = fetchPortfolio15mSpreadEntities(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 6, 15))
            .map { it.toDataPoint() }
        val simPoints = prepareM15PointsForZStrategySim(
            points = raw,
            journalEvents = events,
            journalThresholds = thresholds,
        )
        val simTrips = simShortTrips(simPoints, thresholds)

        assertEquals(4, journal.size)
        assertEquals(journal, simTrips)
        assertFalse(
            simTrips.any { it.first == "2026-06-10 08:00" && it.second == "2026-06-10 09:30" },
        )
        assertEquals("2026-06-10 06:45" to "2026-06-10 10:00", simTrips.first())
    }
}
