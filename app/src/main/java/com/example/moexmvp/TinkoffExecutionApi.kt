package com.example.moexmvp

import android.content.Context
import java.io.IOException

internal fun currentExecutionMode(context: Context): TinkoffExecutionMode =
    TinkoffSandboxStorage.getExecutionMode(context)

internal fun executionModeLabelRu(mode: TinkoffExecutionMode): String = mode.titleRu.lowercase()

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
