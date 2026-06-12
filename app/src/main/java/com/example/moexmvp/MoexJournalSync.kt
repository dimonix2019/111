package com.example.moexmvp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun strategySignalJournalFingerprint(events: List<StrategySignalEvent>): Long {
    var key = events.size.toLong()
    for (ev in events) {
        key = key * 31 + ev.timestampMillis + ev.signalType.ordinal * 31L
    }
    return key
}

/** 15м ряд для PnL закрытых сделок: полный кэш теста (255д) если свежее/длиннее окна портфеля. */
internal fun pickPortfolioExecutionReplayPoints(
    sim: List<DataPoint>,
    portfolio: List<DataPoint>,
): List<DataPoint> {
    if (sim.size < 2) return portfolio
    if (portfolio.size < 2) return sim
    val simLast = sim.last().timestampMillis
    val portLast = portfolio.last().timestampMillis
    return when {
        simLast > portLast -> sim
        simLast == portLast && sim.size >= portfolio.size -> sim
        else -> portfolio
    }
}

internal fun MoexScreenState.portfolioExecutionReplayPoints(): List<DataPoint> =
    pickPortfolioExecutionReplayPoints(m15SeriesForZSimulation(), portfolioM15Points)

/** Журнал мог обновиться в фоне (BG monitor) — подтягиваем с диска в Compose state. */
internal suspend fun MoexScreenState.syncSignalJournalFromDisk(): Boolean {
    val loaded = withContext(Dispatchers.IO) { loadStrategySignalEvents(context) }
    if (strategySignalJournalFingerprint(loaded) == strategySignalJournalFingerprint(signalEvents)) {
        return false
    }
    signalEvents = loaded
    portfolioTabUiBuiltKey = 0L
    val replay = portfolioExecutionReplayPoints()
    if (replay.size >= 2) {
        backfillPersistedZFromJournal(context, loaded, replay)
    }
    return true
}

/**
 * Пересборка закрытых сделок после нового сигнала в журнале.
 * Сначала догружаем хвост 15м (если устарел), иначе PnL считается по старому бару.
 */
internal suspend fun MoexScreenState.refreshPortfolioAfterJournalChange(
    refreshTailIfStale: Boolean = true,
) {
    if (portfolioExecutionReplayPoints().size < 2) return
    if (refreshTailIfStale && portfolioM15Points.size >= 2 && portfolio15mSeriesIntradayStale(portfolioM15Points)) {
        refreshPortfolioM15TailSilent()
        return
    }
    rebuildPortfolioUiFromPoints()
}
