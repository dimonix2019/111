package com.example.moexmvp

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Проблема целостности 15м ряда «Рынок» / live overlay. */
internal data class MarketsM15PipelineIssue(
    val code: String,
    val detail: String,
)

/**
 * Проверки канонического 15м ряда перед записью в session cache.
 * Live Z сюда не входит — только MOEX/SQLite closes.
 */
internal fun validateMarketsM15CanonicalSeries(points: List<DataPoint>): List<MarketsM15PipelineIssue> {
    if (points.isEmpty()) return emptyList()
    val issues = mutableListOf<MarketsM15PipelineIssue>()
    val ordered = ensureAscendingM15Points(points)
    if (ordered.size != points.size) {
        issues += MarketsM15PipelineIssue("order", "ряд не отсортирован по timestamp")
    }
    val labels = mutableSetOf<String>()
    for (i in ordered.indices) {
        val p = ordered[i]
        if (!labels.add(p.tradeDate)) {
            issues += MarketsM15PipelineIssue("dup_label", "дубликат бара ${p.tradeDate}")
        }
        if (i == 0) continue
        val prev = ordered[i - 1]
        val gapMin = ChronoUnit.MINUTES.between(
            Instant.ofEpochMilli(barMillisAt(prev)).atZone(moexZoneId).toLocalDateTime(),
            Instant.ofEpochMilli(barMillisAt(p)).atZone(moexZoneId).toLocalDateTime(),
        )
        if (gapMin <= 0L) {
            issues += MarketsM15PipelineIssue("time", "не монотонный шаг ${prev.tradeDate}→${p.tradeDate}")
        } else if (gapMin != 15L && gapMin < 12 * 60L) {
            issues += MarketsM15PipelineIssue(
                "gap",
                "пропуск ${prev.tradeDate}→${p.tradeDate} Δ=${gapMin}min",
            )
        }
    }
    return issues
}

/** Пропуск >15м между соседними барами в торговой сессии (не overnight). */
internal fun m15SeriesHasIntradayTradingGap(
    points: List<DataPoint>,
    zone: ZoneId = moexZoneId,
): Boolean {
    val ordered = ensureAscendingM15Points(points)
    if (ordered.size < 2) return false
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
        if (prevDay == curDay) return true
    }
    return false
}

/**
 * 1D + time-axis: overnight между вчера и сегодня даёт пустую полосу и «сиротскую» свечу справа.
 * Если есть бары за сегодня — показываем только сегодняшнюю сессию.
 */
internal fun trimMarketsOneDayChartForCrossSessionGap(
    points: List<DataPoint>,
    zone: ZoneId = moexZoneId,
): List<DataPoint> {
    if (points.size < 2) return points
    val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    val todayBars = points.filter { it.timestampMillis >= todayStart }
    if (todayBars.isEmpty()) return points
    val beforeToday = points.filter { it.timestampMillis < todayStart }
    if (beforeToday.isEmpty()) return points
    val gapMin = ChronoUnit.MINUTES.between(
        Instant.ofEpochMilli(barMillisAt(beforeToday.last())).atZone(zone).toLocalDateTime(),
        Instant.ofEpochMilli(barMillisAt(todayBars.first())).atZone(zone).toLocalDateTime(),
    )
    return if (gapMin > 15L) todayBars else points
}

/** Live overlay не должен менять закрытые бары в каноническом ряду. */
internal fun validateMarketsLiveOverlay(
    basePoints: List<DataPoint>,
    patchedPoints: List<DataPoint>,
    liveZ: Double?,
    liveBarAt: String?,
): List<MarketsM15PipelineIssue> {
    if (liveZ == null || basePoints.isEmpty() || patchedPoints.isEmpty()) return emptyList()
    val issues = mutableListOf<MarketsM15PipelineIssue>()
    val target = liveBarAt?.trim()?.takeIf { it.isNotEmpty() }
    val baseByLabel = basePoints.associateBy { it.tradeDate }
    val overlap = minOf(basePoints.size, patchedPoints.size, basePoints.size)
    for (i in 0 until overlap) {
        val base = basePoints[i]
        if (target != null && base.tradeDate == target) continue
        val patched = patchedPoints.getOrNull(i) ?: continue
        if (patched.tradeDate != base.tradeDate) break
        if (abs(patched.zScore - base.zScore) > 1e-6) {
            issues += MarketsM15PipelineIssue(
                "frozen_live",
                "live Z изменил закрытый бар ${base.tradeDate}: " +
                    "${base.zScore}→${patched.zScore}",
            )
        }
    }
    val baseLast = basePoints.last()
    val patchedLast = patchedPoints.last()
    if (target != null &&
        isM15BarLabelAfter(target, baseLast.tradeDate) &&
        patchedPoints.size <= basePoints.size &&
        abs(patchedLast.zScore - liveZ) < 1e-6 &&
        patchedLast.tradeDate == baseLast.tradeDate
    ) {
        issues += MarketsM15PipelineIssue(
            "live_on_closed",
            "live Z $liveZ на закрытом ${baseLast.tradeDate} вместо слота $target",
        )
    }
    return issues
}

/** Согласованность cache / portfolio / live overlay (для диагностики). */
internal fun validateMarketsUiSnapshot(
    cache: List<DataPoint>,
    portfolio: List<DataPoint>,
    liveZ: Double?,
    liveBarAt: String?,
): List<MarketsM15PipelineIssue> {
    val issues = mutableListOf<MarketsM15PipelineIssue>()
    issues += validateMarketsM15CanonicalSeries(cache)
    if (cache.isEmpty() || portfolio.isEmpty()) return issues
    val cLast = cache.last()
    val pLast = portfolio.last()
    if (cLast.tradeDate != pLast.tradeDate &&
        abs(cLast.timestampMillis - pLast.timestampMillis) > 15 * 60_000L
    ) {
        issues += MarketsM15PipelineIssue(
            "cache_portfolio_tail",
            "хвост cache=${cLast.tradeDate} portfolio=${pLast.tradeDate}",
        )
    }
    if (liveZ != null && liveBarAt != null) {
        val baseClose = cache.lastOrNull()?.takeIf { it.tradeDate == liveBarAt }?.zScore
        if (baseClose != null && abs(liveZ - baseClose) > 2.0) {
            issues += MarketsM15PipelineIssue(
                "live_divergence",
                "live Z $liveZ vs close $baseClose на $liveBarAt (>|2|)",
            )
        }
    }
    return issues
}

internal fun formatMarketsM15PipelineIssues(issues: List<MarketsM15PipelineIssue>): String =
    issues.joinToString("; ") { "${it.code}:${it.detail}" }
