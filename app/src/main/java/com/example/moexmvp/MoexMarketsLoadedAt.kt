package com.example.moexmvp

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

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
    val m15Dt = parseLoadedAtMillis(m15) ?: return m15
    val dailyDt = parseLoadedAtMillis(dailyLoadedAt) ?: return m15
    return if (m15Dt >= dailyDt) m15 else dailyLoadedAt
}

private fun parseLoadedAtMillis(raw: String): Long? {
    val trimmed = raw.trim()
    return runCatching { LocalDateTime.parse(trimmed, updatedAtFormatter) }
        .getOrNull()
        ?.atZone(moexZoneId)
        ?.toInstant()
        ?.toEpochMilli()
        ?: runCatching { LocalDateTime.parse(trimmed, portfolio15mLabelFormatter) }
            .getOrNull()
            ?.atZone(moexZoneId)
            ?.toInstant()
            ?.toEpochMilli()
}

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
