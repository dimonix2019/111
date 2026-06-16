package com.example.moexmvp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Если нет новой 1м свечи дольше — пишем в журнал отладки (только в торговую сессию). */
internal const val MARKETS_QUOTES_STALE_WARN_MS = 3L * 60L * 1000L

/** Не спамить предупреждением чаще раза в 5 минут. */
private const val QUOTES_STALE_WARN_THROTTLE_MS = 5L * 60L * 1000L

internal data class MarketsIntraday1mSnapshot(
    val tatn: List<CandlePoint>,
    val tatnp: List<CandlePoint>,
    val tatnLastBarMillis: Long,
    val tatnpLastBarMillis: Long,
    val fetchedAtMillis: Long = System.currentTimeMillis(),
)

private val quotesLogTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

internal fun isMoexMainSessionLikelyOpen(
    now: ZonedDateTime = ZonedDateTime.now(moexZoneId),
): Boolean {
    when (now.dayOfWeek) {
        DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> return false
        else -> Unit
    }
    val t = now.toLocalTime()
    return !t.isBefore(LocalTime.of(10, 0)) && t.isBefore(LocalTime.of(18, 50))
}

internal fun candleBarsToIntradayCandlePoints(bars: List<CandleBar>): List<CandlePoint> =
    bars.sortedBy { it.timestamp }.map { bar ->
        CandlePoint(
            label = bar.timestamp.toLocalTime().format(intradayLabelFormatter),
            open = bar.open,
            high = bar.high,
            low = bar.low,
            close = bar.close,
        )
    }

internal fun lastCandleBarMillis(bars: List<CandleBar>, zone: ZoneId = moexZoneId): Long =
    bars.maxOfOrNull { it.timestamp.atZone(zone).toInstant().toEpochMilli() } ?: 0L

/** Окно графика: последние [visibleBars] свечей (для 1м дня). */
internal fun intraday1mChartInitialWindow(
    barCount: Int,
    visibleBars: Int = 120,
): Pair<Float, Float> {
    if (barCount <= 0) return 1f to 0f
    if (barCount <= visibleBars) return 1f to 0f
    val w = visibleBars.toFloat() / barCount.toFloat()
    val start = 1f - w
    return w.coerceIn(0.05f, 1f) to start.coerceIn(0f, 1f)
}

internal suspend fun fetchMarketsIntraday1mDay(): MarketsIntraday1mSnapshot = withContext(Dispatchers.IO) {
    val today = LocalDate.now(moexZoneId)
    val till = today.plusDays(1)
    coroutineScope {
        val tatnDeferred = async { loadCandleBars("TATN", today, till, interval = 1) }
        val tatnpDeferred = async { loadCandleBars("TATNP", today, till, interval = 1) }
        val tatnBars = tatnDeferred.await()
        val tatnpBars = tatnpDeferred.await()
        MarketsIntraday1mSnapshot(
            tatn = candleBarsToIntradayCandlePoints(tatnBars),
            tatnp = candleBarsToIntradayCandlePoints(tatnpBars),
            tatnLastBarMillis = lastCandleBarMillis(tatnBars),
            tatnpLastBarMillis = lastCandleBarMillis(tatnpBars),
        )
    }
}

/** Журнал [quotes]: новые 1м бары и залипание котировок / Z. */
internal object MarketsQuotesDiagnostics {
    private var lastLoggedTatnBarMillis: Long = 0L
    private var lastLoggedTatnpBarMillis: Long = 0L
    private var lastStaleWarnAtMs: Long = 0L

    fun reset() {
        lastLoggedTatnBarMillis = 0L
        lastLoggedTatnpBarMillis = 0L
        lastStaleWarnAtMs = 0L
    }

    fun logQuoteUpdate(
        context: Context,
        snap: MarketsIntraday1mSnapshot,
        m15Last: DataPoint?,
        reason: String,
    ) {
        val zone = moexZoneId
        val nowMs = System.currentTimeMillis()
        if (snap.tatnLastBarMillis > lastLoggedTatnBarMillis) {
            val label = formatBarMillis(snap.tatnLastBarMillis, zone)
            val close = snap.tatn.lastOrNull()?.close
            MoexDiagnostics.log(
                context,
                "quotes",
                "TATN new 1m $label close=${close?.let { "%.2f".format(Locale.US, it) } ?: "?"} reason=$reason",
            )
            lastLoggedTatnBarMillis = snap.tatnLastBarMillis
        }
        if (snap.tatnpLastBarMillis > lastLoggedTatnpBarMillis) {
            val label = formatBarMillis(snap.tatnpLastBarMillis, zone)
            val close = snap.tatnp.lastOrNull()?.close
            MoexDiagnostics.log(
                context,
                "quotes",
                "TATNP new 1m $label close=${close?.let { "%.2f".format(Locale.US, it) } ?: "?"} reason=$reason",
            )
            lastLoggedTatnpBarMillis = snap.tatnpLastBarMillis
        }

        val newest1m = maxOf(snap.tatnLastBarMillis, snap.tatnpLastBarMillis)
        if (newest1m > 0L && isMoexMainSessionLikelyOpen()) {
            val age1mMs = nowMs - newest1m
            if (age1mMs > MARKETS_QUOTES_STALE_WARN_MS &&
                nowMs - lastStaleWarnAtMs > QUOTES_STALE_WARN_THROTTLE_MS
            ) {
                lastStaleWarnAtMs = nowMs
                val m15AgeMin = m15Last?.let { (nowMs - it.timestampMillis) / 60_000L }
                val m15Label = m15Last?.tradeDate ?: "—"
                MoexDiagnostics.log(
                    context,
                    "quotes",
                    "STALE 1m age=${age1mMs / 60_000L}min newest=${formatBarMillis(newest1m, zone)} " +
                        "m15=$m15Label ageMin=${m15AgeMin ?: "?"} reason=$reason",
                )
            }
        }

        m15Last?.let { last ->
            val newest1mMs = maxOf(snap.tatnLastBarMillis, snap.tatnpLastBarMillis)
            if (newest1mMs > last.timestampMillis + 5 * 60_000L) {
                MoexDiagnostics.log(
                    context,
                    "quotes",
                    "1m ahead of 15m Z: 1m=${formatBarMillis(newest1mMs, zone)} m15=${last.tradeDate} " +
                        "z=${"%.2f".format(Locale.US, last.zScore)} reason=$reason",
                )
            }
        }
    }

    private fun formatBarMillis(tsMillis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(tsMillis).atZone(zone).format(quotesLogTimeFmt)
}
