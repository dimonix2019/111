package com.example.moexmvp

/**
 * Подстановка актуального Z и оценки PnL для открытых сделок на демо при обновлении 15м ряда.
 */
internal fun enrichOpenSandboxExecutions(
    executions: List<SandboxSpreadExecUi>,
    points: List<DataPoint>,
    notionalRub: Double,
    leverage: Double,
    commissionPercentPerSide: Double = 0.04
): List<SandboxSpreadExecUi> {
    if (executions.isEmpty() || points.size < 2) return executions
    val last = points.last()
    return executions.map { exec ->
        val entryZ = if (exec.zScore == PORTFOLIO_TEST_SIGNAL_Z_MARKER) last.zScore else exec.zScore
        val entrySpread = resolveEntrySpreadPercent(
            exec.entrySpreadPercent,
            exec.barTimestampMillis,
            points
        )
        val mtmRub = estimateOpenSpreadMtmNetRub(
            signalType = exec.signalType,
            entrySpreadPercent = entrySpread,
            currentSpreadPercent = last.spreadPercent,
            notionalRub = notionalRub,
            leverage = leverage,
            commissionPercentPerSide = commissionPercentPerSide,
            points = points,
            barTimestampMillis = exec.barTimestampMillis
        )
        val half = mtmRub / 2.0
        val entryDateLabel = points.minByOrNull { kotlin.math.abs(it.timestampMillis - exec.barTimestampMillis) }
            ?.tradeDate ?: exec.entryTimeMsk
        val (commRub, ovnRub) = portfolioTradeCommissionAndOvernightRub(
            notionalRub = notionalRub,
            leverage = leverage,
            commissionPercentPerSide = commissionPercentPerSide,
            entryDateLabel = entryDateLabel,
            exitDateLabel = last.tradeDate,
            includeExitCommission = false
        )
        exec.copy(
            zScore = entryZ,
            entrySpreadPercent = entrySpread,
            exitZDisplay = last.zScore,
            legLongPnlSplitRubApprox = half,
            legShortPnlSplitRubApprox = half,
            netPnlRubApprox = mtmRub,
            commissionRubApprox = commRub,
            overnightRubApprox = ovnRub
        )
    }
}
