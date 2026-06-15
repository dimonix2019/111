package com.example.moexmvp

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Время последнего 15м бара в формате [updatedAtFormatter] (для сводки «Рынок»). */
internal fun m15LastBarLoadedAtLabel(points: List<DataPoint>, zone: ZoneId = moexZoneId): String? {
    val last = points.lastOrNull() ?: return null
    return runCatching {
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(last.timestampMillis), zone)
        dt.format(updatedAtFormatter)
    }.getOrNull()
        ?: last.tradeDate.trim().takeIf { it.isNotEmpty() }?.let { td ->
            runCatching { LocalDateTime.parse(td, portfolio15mLabelFormatter).format(updatedAtFormatter) }
                .getOrNull()
        }
}

/** Показываем более свежее из daily-snapshot и 15м хвоста (Z-график живёт на 15м). */
internal fun resolveMarketsLoadedAtLabel(
    m15Points: List<DataPoint>,
    dailyLoadedAt: String?,
): String? {
    val m15 = m15LastBarLoadedAtLabel(m15Points)
    if (m15.isNullOrBlank()) return dailyLoadedAt
    if (dailyLoadedAt.isNullOrBlank() || dailyLoadedAt == "—") return m15
    val m15Dt = parseMarketsLoadedAt(m15)
    val dailyDt = parseMarketsLoadedAt(dailyLoadedAt)
    return when {
        m15Dt != null && dailyDt != null ->
            if (m15Dt >= dailyDt) m15Dt.format(updatedAtFormatter) else dailyDt.format(updatedAtFormatter)
        dailyDt != null -> dailyDt.format(updatedAtFormatter)
        m15Dt != null -> m15Dt.format(updatedAtFormatter)
        else -> sanitizeMarketsLoadedAtRaw(dailyLoadedAt).ifBlank { m15 }
    }
}

private val marketsLoadedAtLegacyFormatter = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm", Locale.forLanguageTag("ru"))

internal fun parseMarketsLoadedAt(raw: String?): LocalDateTime? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty() || trimmed == "—") return null
    val sanitized = sanitizeMarketsLoadedAtRaw(trimmed)
    val candidates = if (sanitized == trimmed) listOf(trimmed) else listOf(trimmed, sanitized)
    return candidates.asSequence().mapNotNull { candidate ->
        runCatching { LocalDateTime.parse(candidate, updatedAtFormatter) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(candidate, portfolio15mLabelFormatter) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(candidate, marketsLoadedAtLegacyFormatter) }.getOrNull()
    }.firstOrNull()
}

internal fun parseMarketsLoadedAtMillis(raw: String?): Long? =
    parseMarketsLoadedAt(raw)?.atZone(moexZoneId)?.toInstant()?.toEpochMilli()

internal fun sanitizeMarketsLoadedAtRaw(raw: String?): String =
    raw?.trim()
        ?.replace(" : ", " ")
        ?.replace(',', ':')
        ?.replace(Regex("\\s+"), " ")
        .orEmpty()

internal fun MoexScreenState.bumpMarketsLoadedAtFromM15(points: List<DataPoint>) {
    val cached = lastGoodMarkets ?: return
    val resolved = resolveMarketsLoadedAtLabel(points, cached.loadedAt) ?: return
    if (resolved == cached.loadedAt) return
    val updated = cached.copy(loadedAt = resolved)
    lastGoodMarkets = updated
    if (state is UiState.Success) {
        state = updated
    }
}
