package com.example.moexmvp

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max

/** ~10:00–18:50 МСК, 15м бары (оценка для прогресс-бара). */
internal const val PORTFOLIO_M15_BARS_PER_CALENDAR_DAY_ESTIMATE = 34

internal enum class DataLoadPhase {
    Idle,
    /** Чтение SQLite (кэш на устройстве). */
    CacheRead,
    /** Загрузка 10м→15м с MOEX ISS. */
    MoexDownload,
    /** Z-score по ряду после чтения кэша. */
    ApplyingZ,
    /** Дневной ряд TATN/TATNP для портретных графиков. */
    MarketsDaily,
}

internal data class DataLoadProgress(
    val phase: DataLoadPhase = DataLoadPhase.Idle,
    val cacheBarsLoaded: Int = 0,
    val cacheBarsTotal: Int = 0,
    val moexBarsLoaded: Int = 0,
    val moexBarsTotal: Int = 0,
    val moexChunkIndex: Int = 0,
    val moexChunkTotal: Int = 0,
    val detail: String = "",
    val marketsPeriodLabel: String? = null,
) {
    val active: Boolean
        get() = phase != DataLoadPhase.Idle

    val cacheFraction: Float
        get() = fraction(cacheBarsLoaded, cacheBarsTotal)

    val moexFraction: Float
        get() = fraction(moexBarsLoaded, moexBarsTotal)

    val showMoexBar: Boolean
        get() = moexBarsTotal > 0 &&
            (phase == DataLoadPhase.MoexDownload || moexBarsLoaded > 0)

    val showCacheBar: Boolean
        get() = cacheBarsTotal > 0 || phase == DataLoadPhase.CacheRead

    companion object {
        fun idle() = DataLoadProgress(phase = DataLoadPhase.Idle)
    }
}

internal typealias DataLoadProgressCallback = (suspend (DataLoadProgress) -> Unit)?

internal fun estimateM15BarCount(from: LocalDate, till: LocalDate): Int {
    if (from.isAfter(till)) return 0
    val days = ChronoUnit.DAYS.between(from, till) + 1
    return max(1, (days * PORTFOLIO_M15_BARS_PER_CALENDAR_DAY_ESTIMATE).toInt())
}

internal fun countMoexDateChunks(
    from: LocalDate,
    till: LocalDate,
    chunkDays: Long = PORTFOLIO_M15_FETCH_CHUNK_DAYS,
): Int {
    if (from.isAfter(till)) return 0
    var chunks = 0
    var chunkStart = from
    while (!chunkStart.isAfter(till)) {
        chunks++
        val chunkEnd = minOf(chunkStart.plusDays(chunkDays - 1), till)
        chunkStart = chunkEnd.plusDays(1)
    }
    return max(1, chunks)
}

internal fun formatDataLoadProgressSummary(progress: DataLoadProgress): String = buildString {
    if (progress.showCacheBar) {
        append("Кэш: ")
        append(progress.cacheBarsLoaded)
        append(" / ")
        append(progress.cacheBarsTotal.coerceAtLeast(progress.cacheBarsLoaded))
        append(" бар.")
    }
    if (progress.showMoexBar) {
        if (isNotEmpty()) append("  ·  ")
        append("MOEX: ")
        append(progress.moexBarsLoaded)
        append(" / ")
        append(progress.moexBarsTotal)
        append(" бар.")
        if (progress.moexChunkTotal > 0) {
            append(" (чанк ")
            append(progress.moexChunkIndex.coerceAtMost(progress.moexChunkTotal))
            append('/')
            append(progress.moexChunkTotal)
            append(')')
        }
    }
    progress.marketsPeriodLabel?.let { label ->
        if (isNotEmpty()) append("  ·  ")
        append("дневной ряд ")
        append(label)
    }
    progress.detail.takeIf { it.isNotBlank() }?.let { d ->
        if (isNotEmpty()) append(" — ")
        append(d)
    }
}

private fun fraction(loaded: Int, total: Int): Float {
    if (total <= 0) return 0f
    return (loaded.toFloat() / total).coerceIn(0f, 1f)
}
