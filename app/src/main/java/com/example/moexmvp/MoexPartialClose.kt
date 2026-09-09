package com.example.moexmvp

import android.content.Context

internal data class PartialCloseSummary(
    val filledLots: Int,
    val totalLots: Int,
    val detail: String,
)

internal class PartialCloseIncompleteException(
    val summary: PartialCloseSummary,
    val tradeId: String,
) : IllegalStateException(
    "Неполное закрытие: ${summary.filledLots}/${summary.totalLots} · ${summary.detail}",
)

/** Detect incomplete spread exit (any leg partial or min executed < expected). */
internal fun summarizeSpreadExitPartialFill(
    legs: List<SandboxLegOrderResult>,
    expectedLots: Int,
): PartialCloseSummary? {
    if (legs.isEmpty() || expectedLots <= 0) return null
    var minExecuted = expectedLots
    var partial = false
    val parts = mutableListOf<String>()
    for (leg in legs) {
        val parsed = parsePostOrderFill(leg.orderJson)
        minExecuted = minOf(minExecuted, parsed.executedLots.coerceAtLeast(0))
        if (parsed.fillStatus == TradeLegFillStatus.Partial || parsed.executedLots < expectedLots) {
            partial = true
            parts += "${leg.ticker} ${parsed.executedLots}/$expectedLots"
        }
    }
    if (!partial && minExecuted >= expectedLots) return null
    return PartialCloseSummary(
        filledLots = minExecuted.coerceAtLeast(0),
        totalLots = expectedLots,
        detail = parts.joinToString("; ").ifBlank { "частичное исполнение" },
    )
}

internal fun notifyPartialCloseIncomplete(
    context: Context,
    summary: PartialCloseSummary,
    tradeId: String,
) {
    val app = context.applicationContext
    showPushNotification(
        context = app,
        title = "Закрытие неполное · $tradeId",
        body = "${summary.filledLots}/${summary.totalLots} лот · ${summary.detail}",
        virtualTradeTap = null,
        skipDuplicateCheck = true,
        correlationTag = "partialClose|$tradeId",
    )
}
