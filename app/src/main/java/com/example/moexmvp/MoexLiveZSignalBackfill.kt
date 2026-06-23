package com.example.moexmvp

import android.content.Context
import java.time.LocalDate

/** versionCode 1.7.225 — одноразовый догон пропущенных live-сигналов после fix stale Z. */
internal const val STALE_Z_SIGNAL_BACKFILL_VERSION_CODE = 343
private const val PREF_STALE_Z_SIGNAL_BACKFILL_DONE = "stale_z_signal_backfill_v343_done"

/**
 * После fix stale Z: если монитор продвинул lastProcessed без записи в журнал,
 * пересчитываем пересечения за сегодня с rolling Z и дописываем в журнал (без push).
 */
internal fun maybeBackfillMissedLiveZSignalsAfterStaleZFix(
    context: Context,
    signalPoints: List<DataPoint>,
    thresholds: DynamicThresholds,
): Int {
    if (signalPoints.size < 2) return 0
    val app = context.applicationContext
    val prefs = app.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
    if (prefs.getBoolean(PREF_STALE_Z_SIGNAL_BACKFILL_DONE, false)) return 0

    val today = LocalDate.now(moexZoneId)
    val journal = loadStrategySignalEvents(app)
    val eventsToday = journal.filter { barLocalDateFromMillis(it.timestampMillis) == today }
    val replayFromMillis = when {
        eventsToday.isNotEmpty() -> eventsToday.maxOf { it.timestampMillis }
        else -> {
            val firstTodayIdx = firstBarIndexOnDay(signalPoints, today) ?: run {
                prefs.edit().putBoolean(PREF_STALE_Z_SIGNAL_BACKFILL_DONE, true).apply()
                return 0
            }
            if (firstTodayIdx <= 0) {
                prefs.edit().putBoolean(PREF_STALE_Z_SIGNAL_BACKFILL_DONE, true).apply()
                return 0
            }
            signalPoints[firstTodayIdx - 1].timestampMillis
        }
    }

    val initialPosition = inferJournalPositionBeforeDay(journal, today)
    val (edges, finalPosition) = collectZStrategy15mSignalEdgesSinceProcessedBar(
        points = signalPoints,
        lastProcessedBarTimestampMillis = replayFromMillis,
        initialPosition = initialPosition,
        thresholds = thresholds,
    )

    var recorded = 0
    for (edge in edges) {
        val bar = edge.bar
        if (is15mStrategySignalEdgeConsumed(app, bar.timestampMillis, edge.signal)) continue
        val recordedOne = recordStrategySignalEvent(
            context = app,
            signalType = zStrategySignalToStrategySignalType(edge.signal),
            zScore = bar.zScore,
            timestampMillis = bar.timestampMillis,
        )
        mark15mStrategySignalEdgeConsumed(app, bar.timestampMillis, edge.signal)
        if (recordedOne) recorded++
    }

    if (finalPosition != loadSavedStrategyPosition(app)) {
        saveStrategyPosition(app, finalPosition)
    }

    prefs.edit().putBoolean(PREF_STALE_Z_SIGNAL_BACKFILL_DONE, true).apply()
    if (recorded > 0) {
        MoexDiagnostics.log(
            app,
            "signal",
            "stale_z_backfill recorded=$recorded edges=${edges.size} from=$replayFromMillis thr=${thresholds.entry}/${thresholds.exit}",
        )
    }
    return recorded
}

private fun barLocalDateFromMillis(timestampMillis: Long): LocalDate =
    java.time.Instant.ofEpochMilli(timestampMillis).atZone(moexZoneId).toLocalDate()
