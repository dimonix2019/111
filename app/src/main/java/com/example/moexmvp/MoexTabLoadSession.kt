package com.example.moexmvp

import java.time.Instant
import java.time.LocalDate

/** Fingerprint последнего бара 15м ряда (без полного списка в state). */
internal fun m15SeriesTailFingerprint(points: List<DataPoint>): Long {
    val last = points.lastOrNull() ?: return 0L
    return last.timestampMillis xor (points.size.toLong() shl 20)
}

/** Достаточно ли in-memory ряда для окна портфеля [lookbackDays]. */
internal fun m15PointsCoverPortfolioLookback(points: List<DataPoint>, lookbackDays: Long): Boolean {
    if (points.size < 2) return false
    val days = normalizePortfolioLookbackDays(lookbackDays)
    val cutoff = LocalDate.now(portfolioCompareZone).minusDays(days - 1)
    val firstDay = Instant.ofEpochMilli(points.first().timestampMillis)
        .atZone(portfolioCompareZone)
        .toLocalDate()
    return !firstDay.isAfter(cutoff)
}

/** Ключ «данные портфеля актуальны» — без перезагрузки SQLite при совпадении. */
internal fun MoexScreenState.portfolioTabDataSessionKey(): Long {
    var key = m15SeriesTailFingerprint(portfolioM15Points)
    key = key * 31 + portfolioLookbackDays
    key = key * 31 + portfolioM15Points.size
    return key
}

/** Ключ «UI портфеля построен» — journal, sandbox, пороги, симуляция. */
internal fun MoexScreenState.portfolioTabUiSessionKey(): Long {
    var key = portfolioTabDataSessionKey()
    key = key * 31 + (realTradeEntryThreshold?.toBits() ?: 0L)
    key = key * 31 + (realTradeExitThreshold?.toBits() ?: 0L)
    key = key * 31 + portfolioLeverage.toBits()
    key = key * 31 + portfolioCommissionPercent.toBits()
    key = key * 31 + if (portfolioLedgerIncludeAuto) 1L else 0L
    key = key * 31 + strategySignalJournalFingerprint(signalEvents)
    key = key * 31 + sandboxSpreadExecReload
    key = key * 31 + strategyTestM15SessionCache.size
    return key
}

internal fun MoexScreenState.marketsM15CoversPeriod(period: Period): Boolean {
    val src = marketsM15Source()
    if (src.size < 2 || portfolio15mSeriesTailStale(src)) return false
    val needed = marketsM15LookbackDays(period.coerceToMarketsUiPeriod())
    return marketsM15SpanDays() + 3 >= needed
}

internal fun MoexScreenState.invalidateTabLoadSessions() {
    portfolioTabUiBuiltKey = 0L
    marketsM15LoadedPeriod = null
}

/** Прогресс 15м/MOEX — только на вкладке «Рынок» (не блокирует остальные табы). */
internal fun MoexScreenState.shouldTrackDataLoadProgress(): Boolean =
    selectedTab == MainTab.Markets

internal val MoexScreenState.isMarketsDataLoadActive: Boolean
    get() = selectedTab == MainTab.Markets &&
        (dataLoadSessions > 0 || dataLoadProgress?.active == true)

internal fun MoexScreenState.clearStaleDataLoadProgress() {
    if (dataLoadSessions == 0) {
        dataLoadProgress = null
    }
}

internal fun MoexScreenState.forceResetDataLoadUi() {
    dataLoadSessions = 0
    dataLoadProgress = null
}
