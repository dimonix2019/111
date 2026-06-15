package com.example.moexmvp

import android.content.Context
import java.io.IOException

internal fun currentExecutionMode(context: Context): TinkoffExecutionMode =
    TinkoffSandboxStorage.getExecutionMode(context)

internal fun executionModeLabelRu(mode: TinkoffExecutionMode): String = mode.titleRu.lowercase()

internal fun portfolioTabTitleRu(mode: TinkoffExecutionMode): String = when (mode) {
    TinkoffExecutionMode.Sandbox -> "Портфель · демо-счёт"
    TinkoffExecutionMode.Prod -> "Портфель · боевой счёт"
}

internal fun executionAccountShortRu(mode: TinkoffExecutionMode): String = when (mode) {
    TinkoffExecutionMode.Sandbox -> "демо"
    TinkoffExecutionMode.Prod -> "боевой счёт"
}

internal fun executionSettingsHintRu(mode: TinkoffExecutionMode): String =
    "вкладка «Песочница» · режим ${executionModeLabelRu(mode)}"

internal suspend fun executeSpreadEntryDetailedForConfiguredMode(
    context: Context,
    signalType: StrategySignalType,
): List<SandboxLegOrderResult> {
    val mode = currentExecutionMode(context)
    val token = TinkoffSandboxStorage.getActiveToken(context, mode)
        ?: throw IOException("Нет токена (${executionModeLabelRu(mode)}).")
    val accountId = TinkoffSandboxStorage.getActiveAccountId(context, mode)
        ?: throw IOException("Нет accountId (${executionModeLabelRu(mode)}).")
    return tinkoffExecuteSpreadEntryDetailed(
        mode = mode,
        token = token,
        accountId = accountId,
        signalType = signalType,
    )
}

internal suspend fun executeSpreadExitDetailedForConfiguredMode(
    context: Context,
    openedWithEntrySignal: StrategySignalType,
): List<SandboxLegOrderResult> {
    val mode = currentExecutionMode(context)
    val token = TinkoffSandboxStorage.getActiveToken(context, mode)
        ?: throw IOException("Нет токена (${executionModeLabelRu(mode)}).")
    val accountId = TinkoffSandboxStorage.getActiveAccountId(context, mode)
        ?: throw IOException("Нет accountId (${executionModeLabelRu(mode)}).")
    return tinkoffExecuteSpreadExitDetailed(
        mode = mode,
        token = token,
        accountId = accountId,
        openedWithEntrySignal = openedWithEntrySignal,
    )
}
