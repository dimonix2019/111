package com.example.moexmvp

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Logcat: `adb logcat -s MoexDiagnostics | grep m15_chart` */
internal object MarketsM15ChartDiagnostics {
    private const val CATEGORY = "m15_chart"
    private var lastChartBuildFingerprint: String? = null

    fun logStage(context: Context, stage: String, detail: String) {
        MoexDiagnostics.log(context, CATEGORY, "stage=$stage $detail")
    }

    fun logStageError(context: Context, stage: String, throwable: Throwable, detail: String) {
        MoexDiagnostics.logError(context, CATEGORY, throwable, "stage=$stage $detail")
    }

    /** Не спамить одинаковым chart_build на каждый recompose. */
    fun logChartBuildIfChanged(context: Context, fingerprint: String, detail: String) {
        if (fingerprint == lastChartBuildFingerprint) return
        lastChartBuildFingerprint = fingerprint
        logStage(context, "chart_build", detail)
    }

    fun resetChartBuildThrottle() {
        lastChartBuildFingerprint = null
    }
}

internal data class M15SeriesSnapshot(
    val total: Int,
    val first: String?,
    val last: String?,
    val todayCount: Int,
    val hasIntradayGap: Boolean,
    val firstIntradayGap: String?,
    val canonicalGapCount: Int,
)

internal fun snapshotM15Series(
    points: List<DataPoint>,
    zone: ZoneId = moexZoneId,
): M15SeriesSnapshot {
    if (points.isEmpty()) {
        return M15SeriesSnapshot(0, null, null, 0, false, null, 0)
    }
    val ordered = ensureAscendingM15Points(points)
    val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    val todayCount = ordered.count { it.timestampMillis >= todayStart }
    val todayOnly = ordered.filter { it.timestampMillis >= todayStart }
    val firstGap = firstIntradayTradingGapLabel(todayOnly, zone)
    val canonicalGaps = validateMarketsM15CanonicalSeries(ordered).count { it.code == "gap" }
    return M15SeriesSnapshot(
        total = ordered.size,
        first = ordered.first().tradeDate,
        last = ordered.last().tradeDate,
        todayCount = todayCount,
        hasIntradayGap = firstGap != null,
        firstIntradayGap = firstGap,
        canonicalGapCount = canonicalGaps,
    )
}

internal fun M15SeriesSnapshot.toLogFields(): String {
    val gapPart = when {
        firstIntradayGap != null -> "intraday_gap=YES gap=$firstIntradayGap"
        else -> "intraday_gap=no"
    }
    return buildString {
        append("bars=$total")
        if (first != null) append(" first=$first")
        if (last != null) append(" last=$last")
        append(" today=$todayCount")
        append(' ')
        append(gapPart)
        if (canonicalGapCount > 0) append(" canonical_gaps=$canonicalGapCount")
    }
}

internal fun firstIntradayTradingGapLabel(
    points: List<DataPoint>,
    zone: ZoneId = moexZoneId,
): String? {
    val ordered = ensureAscendingM15Points(points)
    if (ordered.size < 2) return null
    for (i in 1 until ordered.size) {
        val prev = ordered[i - 1]
        val cur = ordered[i]
        val gapMin = ChronoUnit.MINUTES.between(
            Instant.ofEpochMilli(barMillisAt(prev)).atZone(zone).toLocalDateTime(),
            Instant.ofEpochMilli(barMillisAt(cur)).atZone(zone).toLocalDateTime(),
        )
        if (gapMin <= 15L) continue
        val prevDay = Instant.ofEpochMilli(barMillisAt(prev)).atZone(zone).toLocalDate()
        val curDay = Instant.ofEpochMilli(barMillisAt(cur)).atZone(zone).toLocalDate()
        if (prevDay == curDay) {
            return "${prev.tradeDate}→${cur.tradeDate} Δ${gapMin}min"
        }
    }
    return null
}

internal fun MoexScreenState.logMarketsM15ChartBuild(period: Period) {
    val session = marketsM15Source()
    val sqlite = marketsM15SqliteChartCache
    val overlay = marketsM15TodayChartOverlay
    val base = mergeM15SessionWithSqliteForChart(session, sqlite)
    val merged = applyTodayM15OverlayForChart(base, overlay)
    val filtered = filterM15PointsForMarketsPeriod(merged, period)
    val fp = buildString {
        append(session.size).append('|')
        append(sqlite.size).append('|')
        append(overlay.size).append('|')
        append(marketsM15DataEpoch).append('|')
        append(marketsM15SqliteChartEpoch).append('|')
        append(marketsM15ChartOverlayEpoch).append('|')
        append(period.label).append('|')
        append(filtered.lastOrNull()?.tradeDate ?: "-")
    }
    MarketsM15ChartDiagnostics.logChartBuildIfChanged(
        context,
        fp,
        buildString {
            append("period=${period.label} ")
            append("epoch=session:${marketsM15DataEpoch} sqlite:${marketsM15SqliteChartEpoch} overlay:${marketsM15ChartOverlayEpoch} ")
            append("session{${snapshotM15Series(session).toLogFields()}} ")
            if (sqlite.isNotEmpty()) append("sqlite{${snapshotM15Series(sqlite).toLogFields()}} ")
            if (overlay.isNotEmpty()) append("overlay{${snapshotM15Series(overlay).toLogFields()}} ")
            append("merged{${snapshotM15Series(merged).toLogFields()}} ")
            append("chart{${snapshotM15Series(filtered).toLogFields()}} ")
            append("live_z=${marketsLiveZScore?.let { String.format(Locale.US, "%+.2f", it) } ?: "—"} ")
            append("live_bar=${marketsLiveZBarAt ?: "—"}")
        },
    )
}
