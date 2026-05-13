package com.example.moexmvp

import kotlin.math.max

/**
 * Walk-forward style threshold selection: Q0 must have enough trades; score is sum of OOS PnL
 * on Q1–Q3 with small penalty per trade (robustness vs over-trading).
 */
internal fun calculateWalkForwardRobustThresholds(points: List<DataPoint>): DynamicThresholds? {
    if (points.size < 80) return null
    val bt = points.map { BacktestPoint(spread = it.spreadPercent, z = it.zScore) }
    val n = bt.size
    val q = max(15, n / 4)
    val s0 = bt.subList(0, q)
    val s1 = bt.subList(q, (2 * q).coerceAtMost(n))
    val s2 = bt.subList(2 * q, (3 * q).coerceAtMost(n))
    val s3 = bt.subList(3 * q, n)
    if (s1.isEmpty() || s2.isEmpty() || s3.isEmpty()) return null

    val oosSegments = listOf(s1, s2, s3)
    val penaltyPerTrade = 0.08

    var best: Triple<Double, Double, Double>? = null
    for (entryTenths in Z_STRATEGY_ENTRY_MIN_TENTHS..Z_STRATEGY_ENTRY_MAX_TENTHS) {
        val entry = entryTenths / 10.0
        for (exitTenths in Z_STRATEGY_EXIT_MIN_TENTHS..Z_STRATEGY_EXIT_MAX_TENTHS) {
            val exit = exitTenths / 10.0
            if (exit >= entry) continue
            val train = backtestZStrategy(s0, entry, exit)
            if (train.trades < Z_STRATEGY_MIN_TRADES) continue
            var oosScore = 0.0
            for (seg in oosSegments) {
                val r = backtestZStrategy(seg, entry, exit)
                oosScore += r.pnl - penaltyPerTrade * r.trades
            }
            if (best == null || oosScore > best.first) {
                best = Triple(oosScore, entry, exit)
            }
        }
    }
    val winner = best ?: return null
    return DynamicThresholds(
        entry = winner.second,
        exit = winner.third,
        calculatedDate = java.time.LocalDate.now().toString() + " (walk-forward)"
    )
}
