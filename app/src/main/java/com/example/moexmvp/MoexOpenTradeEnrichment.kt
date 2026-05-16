package com.example.moexmvp

/**
 * Подстановка актуального Z и оценки PnL для открытых сделок на демо при обновлении 15м ряда.
 */
internal fun enrichOpenSandboxExecutions(
    executions: List<SandboxSpreadExecUi>,
    points: List<DataPoint>,
    notionalRub: Double,
    leverage: Double
): List<SandboxSpreadExecUi> {
    if (executions.isEmpty() || points.size < 2) return executions
    val last = points.last()
    return executions.map { exec ->
        val entryZ = if (exec.zScore == PORTFOLIO_TEST_SIGNAL_Z_MARKER) last.zScore else exec.zScore
        val mtmRub = estimateOpenSpreadMtmRub(
            signalType = exec.signalType,
            entrySpreadPercent = exec.entrySpreadPercent,
            currentSpreadPercent = last.spreadPercent,
            notionalRub = notionalRub,
            leverage = leverage
        )
        val half = mtmRub / 2.0
        exec.copy(
            zScore = entryZ,
            exitZDisplay = last.zScore,
            legLongPnlSplitRubApprox = half,
            legShortPnlSplitRubApprox = half,
            netPnlRubApprox = mtmRub
        )
    }
}
