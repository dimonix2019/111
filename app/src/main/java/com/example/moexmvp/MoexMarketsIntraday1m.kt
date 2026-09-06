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

internal fun MoexScreenState.cachedMarketsIntraday1mSnapshot(): MarketsIntraday1mSnapshot? {
    if (marketsIntraday1mTatn.isEmpty() || marketsIntraday1mTatnp.isEmpty()) return null
    return MarketsIntraday1mSnapshot(
        tatn = marketsIntraday1mTatn,
        tatnp = marketsIntraday1mTatnp,
        tatnLastBarMillis = marketsIntraday1mLastBarMillis,
        tatnpLastBarMillis = marketsIntraday1mLastBarMillis,
        fetchedAtMillis = marketsIntraday1mFetchedAtMillis,
    )
}

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

/** Утро/день/вечер MOEX — для предупреждения о залипании 1м котировок. */
internal fun isMoexQuotesSessionLikelyOpen(
    now: ZonedDateTime = ZonedDateTime.now(moexZoneId),
): Boolean {
    when (now.dayOfWeek) {
        DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> return false
        else -> Unit
    }
    val t = now.toLocalTime()
    if (t.isBefore(LocalTime.of(7, 0))) return false
    if (!t.isBefore(LocalTime.of(18, 50))) return !t.isBefore(LocalTime.of(19, 0)) && t.isBefore(LocalTime.of(23, 55))
    return true
}

internal fun intraday1mLastBarAgeMinutes(lastBarMillis: Long, nowMs: Long = System.currentTimeMillis()): Long? {
    if (lastBarMillis <= 0L) return null
    return ((nowMs - lastBarMillis) / 60_000L).coerceAtLeast(0L)
}

internal fun formatIntraday1mLastBarLabel(lastBarMillis: Long, zone: ZoneId = moexZoneId): String? {
    if (lastBarMillis <= 0L) return null
    return Instant.ofEpochMilli(lastBarMillis).atZone(zone).format(quotesLogTimeFmt)
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

/** Окно графика: последние [visibleBars] минут + отступ справа (как Z-score). */
internal fun intraday1mChartInitialWindow(
    barCount: Int,
    visibleBars: Int = 120,
): Pair<Float, Float> {
    if (barCount <= 0) return 1f to 0f
    val width = if (barCount <= visibleBars) {
        1f
    } else {
        visibleBars.toFloat() / barCount.toFloat()
    }.coerceIn(CHART_ZOOM_MIN_WINDOW, 1f)
    val start = chartInitialWindowStartWithRightGap(width)
    return width to start
}

internal fun appendFormingIntraday1mFrom10m(
    bars1m: List<CandleBar>,
    bars10m: List<CandleBar>,
    now: ZonedDateTime = ZonedDateTime.now(moexZoneId),
): List<CandleBar> {
    if (!isMoexQuotesSessionLikelyOpen(now)) return bars1m
    if (bars10m.isEmpty()) return bars1m
    val nowLdt = now.toLocalDateTime()
    val latest10 = bars10m.filter { !it.timestamp.isAfter(nowLdt) }.maxByOrNull { it.timestamp }
        ?: return bars1m
    val price = latest10.close
    val minuteBucket = now.withSecond(0).withNano(0).toLocalDateTime()
    val result = bars1m.toMutableList()
    val last1m = result.lastOrNull()
    when {
        last1m != null && last1m.timestamp == minuteBucket -> {
            val i = result.lastIndex
            result[i] = last1m.copy(
                close = price,
                high = maxOf(last1m.high, price),
                low = minOf(last1m.low, price),
            )
        }
        last1m == null || last1m.timestamp.isBefore(minuteBucket) -> {
            val open = last1m?.close ?: latest10.open
            result.add(
                CandleBar(
                    timestamp = minuteBucket,
                    open = open,
                    high = maxOf(open, price, latest10.high),
                    low = minOf(open, price, latest10.low),
                    close = price,
                ),
            )
        }
    }
    return result
}

internal suspend fun fetchMarketsIntraday1mLive(): MarketsIntraday1mSnapshot = withContext(Dispatchers.IO) {
    val today = LocalDate.now(moexZoneId)
    val till = today.plusDays(1)
    val tenFrom = today.minusDays(1)
    coroutineScope {
        val tatn1 = async { loadCandleBars("TATN", today, till, interval = 1) }
        val tatnp1 = async { loadCandleBars("TATNP", today, till, interval = 1) }
        val tatn10 = async { loadCandleBars("TATN", tenFrom, till, interval = 10) }
        val tatnp10 = async { loadCandleBars("TATNP", tenFrom, till, interval = 10) }
        val tatnBars = appendFormingIntraday1mFrom10m(tatn1.await(), tatn10.await())
        val tatnpBars = appendFormingIntraday1mFrom10m(tatnp1.await(), tatnp10.await())
        MarketsIntraday1mSnapshot(
            tatn = candleBarsToIntradayCandlePoints(tatnBars),
            tatnp = candleBarsToIntradayCandlePoints(tatnpBars),
            tatnLastBarMillis = lastCandleBarMillis(tatnBars),
            tatnpLastBarMillis = lastCandleBarMillis(tatnpBars),
        )
    }
}

/** @deprecated Используйте [fetchMarketsIntraday1mLive] — с хвостом из 10м. */
internal suspend fun fetchMarketsIntraday1mDay(): MarketsIntraday1mSnapshot = fetchMarketsIntraday1mLive()

/** Журнал [quotes]: новые 1м бары и залипание котировок / Z. */
internal object MarketsQuotesDiagnostics {
    private var lastLoggedTatnBarMillis: Long = 0L
    private var lastLoggedTatnpBarMillis: Long = 0L
    private var lastStaleWarnAtMs: Long = 0L
    private var lastPollLogAtMs: Long = 0L

    fun reset() {
        lastLoggedTatnBarMillis = 0L
        lastLoggedTatnpBarMillis = 0L
        lastStaleWarnAtMs = 0L
        lastPollLogAtMs = 0L
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
        val ageMin = if (newest1m > 0L) (nowMs - newest1m) / 60_000L else -1L
        if (nowMs - lastPollLogAtMs > 120_000L || newest1m > lastLoggedTatnBarMillis.coerceAtLeast(lastLoggedTatnpBarMillis)) {
            lastPollLogAtMs = nowMs
            MoexDiagnostics.log(
                context,
                "quotes",
                "poll ok 1m=${formatBarMillis(newest1m, zone)} ageMin=$ageMin reason=$reason",
            )
        }
        if (newest1m > 0L && isMoexQuotesSessionLikelyOpen()) {
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
