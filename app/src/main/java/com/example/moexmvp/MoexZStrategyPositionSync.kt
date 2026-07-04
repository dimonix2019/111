package com.example.moexmvp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Позиция Z-стратегии по последней открытой сделке (EnterLong / EnterShort). */
internal fun inferZStrategyPositionFromOpenExecutions(
    openExecutions: List<SandboxSpreadExecUi>,
): ZStrategyPosition? {
    val latest = openExecutions
        .asSequence()
        .filter {
            it.signalType == StrategySignalType.EnterLong ||
                it.signalType == StrategySignalType.EnterShort
        }
        .maxByOrNull { it.barTimestampMillis }
        ?: return null
    return when (latest.signalType) {
        StrategySignalType.EnterLong -> ZStrategyPosition.Long
        StrategySignalType.EnterShort -> ZStrategyPosition.Short
        else -> null
    }
}

/**
 * Открытые записи демо/Prod без парного выхода в журнале сигналов.
 * Не требует 15м ряда — достаточно для синхронизации prefs с шторкой монитора.
 */
internal fun stillOpenExecutionsAfterJournalExits(
    openExecutions: List<SandboxSpreadExecUi>,
    journalEvents: List<StrategySignalEvent>,
): List<SandboxSpreadExecUi> {
    if (openExecutions.isEmpty()) return emptyList()
    val remainingExits = journalEvents
        .filter {
            it.signalType == StrategySignalType.ExitLong ||
                it.signalType == StrategySignalType.ExitShort
        }
        .sortedBy { it.timestampMillis }
        .toMutableList()
    val stillOpen = mutableListOf<SandboxSpreadExecUi>()
    for (open in openExecutions.sortedBy { it.barTimestampMillis }) {
        val exitType = when (open.signalType) {
            StrategySignalType.EnterLong -> StrategySignalType.ExitLong
            StrategySignalType.EnterShort -> StrategySignalType.ExitShort
            else -> {
                stillOpen += open
                continue
            }
        }
        val exitIdx = remainingExits.indexOfFirst { ev ->
            ev.signalType == exitType && ev.timestampMillis >= open.barTimestampMillis
        }
        if (exitIdx < 0) {
            stillOpen += open
        } else {
            remainingExits.removeAt(exitIdx)
        }
    }
    return stillOpen
}

/** Исполнение важнее prefs: FLAT при открытой 1L/1S — подтягиваем Long/Short. */
internal fun reconcileZStrategyPositionWithOpenTrade(
    savedPosition: ZStrategyPosition,
    openExecutions: List<SandboxSpreadExecUi>,
): ZStrategyPosition {
    val fromOpen = inferZStrategyPositionFromOpenExecutions(openExecutions) ?: return savedPosition
    return when {
        savedPosition == ZStrategyPosition.Flat -> fromOpen
        savedPosition != fromOpen -> fromOpen
        else -> savedPosition
    }
}

internal suspend fun resolveOpenExecutionsForPositionSync(context: Context): List<SandboxSpreadExecUi> =
    withContext(Dispatchers.IO) {
        val raw = TinkoffSandboxSpreadExecLog.loadRecent(context)
        if (raw.isEmpty()) return@withContext emptyList()
        val journal = loadStrategySignalEvents(context)
        val includeAuto = TinkoffSandboxStorage.isPortfolioLedgerIncludeAuto(context)
        filterSandboxExecutionsByPortfolioMode(
            stillOpenExecutionsAfterJournalExits(raw, journal),
            includeAuto,
        )
    }

/** Обновляет SharedPreferences, если открытая сделка расходится с сохранённой позицией. */
internal suspend fun syncSavedZStrategyPositionFromOpenExecutions(
    context: Context,
    openExecutions: List<SandboxSpreadExecUi>? = null,
): ZStrategyPosition {
    val saved = loadSavedStrategyPosition(context)
    val opens = openExecutions ?: resolveOpenExecutionsForPositionSync(context)
    val reconciled = reconcileZStrategyPositionWithOpenTrade(saved, opens)
    if (reconciled != saved) {
        saveStrategyPosition(context, reconciled)
        MoexDiagnostics.log(
            context,
            "position",
            "sync prefs $saved -> $reconciled from ${opens.size} open exec(s)",
        )
    }
    return reconciled
}

internal suspend fun MoexScreenState.syncZStrategyPositionFromOpenExecutions(
    openExecutions: List<SandboxSpreadExecUi>? = null,
) {
    val opens = openExecutions
        ?: sandboxSpreadExecutions.takeIf { it.isNotEmpty() }
        ?: resolveOpenExecutionsForPositionSync(context)
    val reconciled = reconcileZStrategyPositionWithOpenTrade(zStrategyPosition, opens)
    if (reconciled != zStrategyPosition) {
        zStrategyPosition = reconciled
        saveStrategyPosition(context, reconciled)
        MoexDiagnostics.log(
            context,
            "position",
            "ui sync $reconciled from ${opens.size} open exec(s)",
        )
    }
}
