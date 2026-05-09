package com.example.moexmvp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

internal fun spreadPnlToRubApprox(pnlSpreadPoints: Double, notionalRub: Double): Double {
    return notionalRub * (pnlSpreadPoints / 100.0)
}

/**
 * Mirrors [backtestZStrategy] but records round-trips, open leg, equity path (realized + MTM each bar),
 * and portfolio statistics. PnL is in spread %-points; rub figures are a rough scaling by notional.
 */
internal fun buildZStrategyPortfolioMetrics(
    points: List<DataPoint>,
    thresholds: DynamicThresholds,
    notionalRub: Double,
    periodDescription: String
): PortfolioMetrics? {
    if (points.size < 2) return null

    val entry = thresholds.entry
    val exit = thresholds.exit

    var position = ZStrategyPosition.Flat
    var entrySpread = 0.0
    var entryDate = ""
    var realizedSpread = 0.0
    val closed = mutableListOf<PortfolioClosedTrade>()
    val equityRubSeries = mutableListOf<Double>()

    fun pushEquitySnapshot(atIndex: Int) {
        val bar = points[atIndex]
        val mtm = when (position) {
            ZStrategyPosition.Flat -> 0.0
            ZStrategyPosition.Long -> bar.spreadPercent - entrySpread
            ZStrategyPosition.Short -> entrySpread - bar.spreadPercent
        }
        val totalSpread = realizedSpread + mtm
        equityRubSeries += spreadPnlToRubApprox(totalSpread, notionalRub)
    }

    for (index in 1 until points.size) {
        val prev = points[index - 1]
        val current = points[index]
        when (position) {
            ZStrategyPosition.Long -> {
                if (prev.zScore < -exit && current.zScore >= -exit) {
                    val pnl = current.spreadPercent - entrySpread
                    realizedSpread += pnl
                    closed += PortfolioClosedTrade(
                        direction = ZStrategyPosition.Long,
                        entryDate = entryDate,
                        exitDate = current.tradeDate,
                        entrySpreadPercent = entrySpread,
                        exitSpreadPercent = current.spreadPercent,
                        pnlSpreadPoints = pnl,
                        pnlRubApprox = spreadPnlToRubApprox(pnl, notionalRub)
                    )
                    position = ZStrategyPosition.Flat
                }
            }

            ZStrategyPosition.Short -> {
                if (prev.zScore > exit && current.zScore <= exit) {
                    val pnl = entrySpread - current.spreadPercent
                    realizedSpread += pnl
                    closed += PortfolioClosedTrade(
                        direction = ZStrategyPosition.Short,
                        entryDate = entryDate,
                        exitDate = current.tradeDate,
                        entrySpreadPercent = entrySpread,
                        exitSpreadPercent = current.spreadPercent,
                        pnlSpreadPoints = pnl,
                        pnlRubApprox = spreadPnlToRubApprox(pnl, notionalRub)
                    )
                    position = ZStrategyPosition.Flat
                }
            }

            ZStrategyPosition.Flat -> Unit
        }
        if (position == ZStrategyPosition.Flat) {
            if (prev.zScore > -entry && current.zScore <= -entry) {
                position = ZStrategyPosition.Long
                entrySpread = current.spreadPercent
                entryDate = current.tradeDate
            } else if (prev.zScore < entry && current.zScore >= entry) {
                position = ZStrategyPosition.Short
                entrySpread = current.spreadPercent
                entryDate = current.tradeDate
            }
        }
        pushEquitySnapshot(index)
    }

    val last = points.last()
    val open: PortfolioOpenPosition?
    val unrealizedSpread: Double
    if (position != ZStrategyPosition.Flat) {
        unrealizedSpread = when (position) {
            ZStrategyPosition.Long -> last.spreadPercent - entrySpread
            ZStrategyPosition.Short -> entrySpread - last.spreadPercent
            ZStrategyPosition.Flat -> 0.0
        }
        open = PortfolioOpenPosition(
            direction = position,
            entryDate = entryDate,
            entrySpreadPercent = entrySpread,
            lastSpreadPercent = last.spreadPercent,
            unrealizedPnlSpread = unrealizedSpread,
            unrealizedRubApprox = spreadPnlToRubApprox(unrealizedSpread, notionalRub)
        )
    } else {
        unrealizedSpread = 0.0
        open = null
    }

    val totalSpread = realizedSpread + unrealizedSpread
    val totalRub = spreadPnlToRubApprox(totalSpread, notionalRub)

    var peak = doubleArrayOf(0.0)
    var maxDdRub = 0.0
    var maxDdPct = 0.0
    for (eq in equityRubSeries) {
        peak[0] = max(peak[0], eq)
        val dd = peak[0] - eq
        if (dd > maxDdRub) maxDdRub = dd
        if (peak[0] != 0.0) {
            val pct = dd / abs(peak[0]) * 100.0
            if (pct > maxDdPct) maxDdPct = pct
        }
    }

    val wins = closed.count { it.pnlRubApprox > 0 }
    val losses = closed.count { it.pnlRubApprox < 0 }
    val winRate = if (closed.isEmpty()) 0.0 else wins * 100.0 / closed.size

    val grossWin = closed.filter { it.pnlRubApprox > 0 }.sumOf { it.pnlRubApprox }
    val grossLoss = closed.filter { it.pnlRubApprox < 0 }.sumOf { -it.pnlRubApprox }
    val profitFactor = if (grossLoss > 0) grossWin / grossLoss else null

    val winRubList = closed.mapNotNull { if (it.pnlRubApprox > 0) it.pnlRubApprox else null }
    val lossRubList = closed.mapNotNull { if (it.pnlRubApprox < 0) it.pnlRubApprox else null }
    val avgWin = if (winRubList.isEmpty()) 0.0 else winRubList.average()
    val avgLoss = if (lossRubList.isEmpty()) 0.0 else lossRubList.average()
    val largestWin = winRubList.maxOrNull() ?: 0.0
    val largestLoss = lossRubList.minOrNull() ?: 0.0

    val fullDescription = "$periodDescription · Z-вход ±${String.format(Locale.US, "%.1f", entry)}, выход ±${String.format(Locale.US, "%.1f", exit)}"
    return PortfolioMetrics(
        periodDescription = fullDescription,
        notionalRub = notionalRub,
        closedTrades = closed,
        openPosition = open,
        cumulativeRealizedSpread = realizedSpread,
        cumulativeRealizedRubApprox = spreadPnlToRubApprox(realizedSpread, notionalRub),
        unrealizedRubApprox = spreadPnlToRubApprox(unrealizedSpread, notionalRub),
        totalPnlSpread = totalSpread,
        totalPnlRubApprox = totalRub,
        totalReturnPercent = if (notionalRub > 0) totalRub / notionalRub * 100.0 else 0.0,
        maxDrawdownRubApprox = maxDdRub,
        maxDrawdownPercent = maxDdPct,
        winCount = wins,
        lossCount = losses,
        winRate = winRate,
        profitFactor = profitFactor,
        avgWinRub = avgWin,
        avgLossRub = avgLoss,
        largestWinRub = largestWin,
        largestLossRub = largestLoss
    )
}

@Composable
internal fun MainTabSelector(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MainTab.entries.forEach { tab ->
            val isSel = tab == selected
            Button(
                onClick = { onSelect(tab) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSel) Color(0xFF1565C0) else Color(0xFF424242),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(tab.label, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
internal fun PortfolioTabContent(
    metrics: PortfolioMetrics?,
    portfolioLoading: Boolean,
    portfolioError: String?,
    onRefresh: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Портфель Z-стратегии (TATN/TATNP)",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Обновить", fontSize = 12.sp)
            }
        }
        Text(
            text = "Оценка в ₽: условный капитал ${"%.0f".format(Locale.US, metrics?.notionalRub ?: DEFAULT_PORTFOLIO_NOTIONAL_RUB)} ₽, PnL ∝ движению спрэда в п.п. (не учёт комиссий и плеча).",
            color = Color(0xFFBDBDBD),
            fontSize = 11.sp
        )

        if (portfolioLoading) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(color = Color(0xFF64B5F6))
            }
            return@Column
        }

        if (portfolioError != null) {
            Text(portfolioError, color = Color(0xFFEF9A9A), fontSize = 12.sp)
            Button(onClick = onRefresh) { Text("Повторить") }
            return@Column
        }

        if (metrics == null) {
            Text("Недостаточно данных для расчёта.", color = Color(0xFFBDBDBD), fontSize = 12.sp)
            return@Column
        }

        Text(
            text = metrics.periodDescription,
            color = Color(0xFF9E9E9E),
            fontSize = 11.sp
        )

        PortfolioMetricGrid(metrics)

        Text(
            text = "Сделки (${metrics.closedTrades.size} закрытых)",
            color = Color(0xFFE0E0E0),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 8.dp)
        )

        val recent = metrics.closedTrades.takeLast(25).asReversed()
        if (recent.isEmpty()) {
            Text("Закрытых сделок за период нет.", color = Color(0xFF9E9E9E), fontSize = 12.sp)
        } else {
            recent.forEachIndexed { index, t ->
                PortfolioTradeRow(index = index + 1, t = t)
            }
        }
    }
}

@Composable
private fun PortfolioMetricGrid(m: PortfolioMetrics) {
    val pf = m.profitFactor?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PortfolioStatCard(
            "Итого PnL (оценка)",
            "${formatRubSigned(m.totalPnlRubApprox)} (${formatPercentSigned(m.totalReturnPercent)})"
        )
        PortfolioStatCard(
            "Реализовано / нереализ.",
            "${formatRubSigned(m.cumulativeRealizedRubApprox)} / ${formatRubSigned(m.unrealizedRubApprox)}"
        )
        PortfolioStatCard(
            "Макс. просадка",
            "${formatRubSigned(-m.maxDrawdownRubApprox)} (~ ${String.format(Locale.US, "%.1f", m.maxDrawdownRubApprox / m.notionalRub * 100.0)}% от капитала, по эквити-пику ${String.format(Locale.US, "%.1f", m.maxDrawdownPercent)}%)"
        )
        PortfolioStatCard(
            "Сделки / винрейт",
            "${m.winCount}W / ${m.lossCount}L · ${String.format(Locale.US, "%.0f", m.winRate)}%"
        )
        PortfolioStatCard("Profit factor", pf)
        PortfolioStatCard(
            "Средний W / L",
            "${formatRubSigned(m.avgWinRub)} / ${formatRubSigned(m.avgLossRub)}"
        )
        PortfolioStatCard(
            "Лучшая / худшая",
            "${formatRubSigned(m.largestWinRub)} / ${formatRubSigned(m.largestLossRub)}"
        )
        m.openPosition?.let { o ->
            val dir = when (o.direction) {
                ZStrategyPosition.Long -> "LONG спрэд"
                ZStrategyPosition.Short -> "SHORT спрэд"
                ZStrategyPosition.Flat -> ""
            }
            PortfolioStatCard(
                "Открытая позиция",
                "$dir с ${o.entryDate}, спрэд ${String.format(Locale.US, "%.2f", o.entrySpreadPercent)}% → ${String.format(Locale.US, "%.2f", o.lastSpreadPercent)}%"
            )
            PortfolioStatCard("Нереализ. PnL", formatRubSigned(o.unrealizedRubApprox))
        } ?: PortfolioStatCard("Открытая позиция", "Нет")
    }
}

@Composable
private fun PortfolioStatCard(title: String, value: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(title, color = Color(0xFF9E9E9E), fontSize = 11.sp)
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PortfolioTradeRow(index: Int, t: PortfolioClosedTrade) {
    val dir = when (t.direction) {
        ZStrategyPosition.Long -> "LONG"
        ZStrategyPosition.Short -> "SHORT"
        ZStrategyPosition.Flat -> "—"
    }
    val pnlColor = when {
        t.pnlRubApprox > 0 -> Color(0xFF81C784)
        t.pnlRubApprox < 0 -> Color(0xFFE57373)
        else -> Color(0xFFBDBDBD)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF252525), RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("#$index  $dir ${t.entryDate} → ${t.exitDate}", color = Color(0xFFE0E0E0), fontSize = 12.sp)
            Text(formatRubSigned(t.pnlRubApprox), color = pnlColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "спрэд ${String.format(Locale.US, "%.2f", t.entrySpreadPercent)}% → ${String.format(Locale.US, "%.2f", t.exitSpreadPercent)}% (${String.format(Locale.US, "%+.2f", t.pnlSpreadPoints)} п.п.)",
            color = Color(0xFF9E9E9E),
            fontSize = 10.sp
        )
    }
}

private fun formatRubSigned(v: Double): String {
    val s = String.format(Locale.US, "%+.0f ₽", v)
    return s
}

private fun formatPercentSigned(v: Double): String {
    return String.format(Locale.US, "%+.2f%%", v)
}
