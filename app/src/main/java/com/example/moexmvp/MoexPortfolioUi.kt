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
import androidx.compose.material3.OutlinedButton
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
internal fun PortfolioTimeframeSelector(
    selected: PortfolioTimeframe,
    onSelect: (PortfolioTimeframe) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PortfolioTimeframe.entries.forEach { candidate ->
            val isSel = candidate == selected
            Button(
                onClick = { onSelect(candidate) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSel) Color(0xFF6A1B9A) else Color(0xFF424242),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(candidate.label, fontSize = 11.sp, maxLines = 2)
            }
        }
    }
}

@Composable
internal fun StrategyViewModeSelector(
    mode: StrategyViewMode,
    onModeChange: (StrategyViewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StrategyViewMode.entries.forEach { candidate ->
            val selected = candidate == mode
            Button(
                onClick = { onModeChange(candidate) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) Color(0xFF00897B) else Color(0xFF424242),
                    contentColor = Color.White
                )
            ) {
                Text(candidate.label, fontSize = 12.sp)
            }
        }
    }
}

@Composable
internal fun PortfolioTabContent(
    metrics: PortfolioMetrics?,
    portfolioLoading: Boolean,
    portfolioError: String?,
    onRefresh: () -> Unit,
    onMoex15mFullReload: () -> Unit,
    timeframe: PortfolioTimeframe,
    onTimeframeChange: (PortfolioTimeframe) -> Unit,
    leverage: Double,
    commissionPercentPerSide: Double,
    entryThreshold: Double,
    exitThreshold: Double,
    strategyViewMode: StrategyViewMode,
    onLeverageChange: (Double) -> Unit,
    onCommissionChange: (Double) -> Unit,
    onEntryThresholdChange: (Double) -> Unit,
    onExitThresholdChange: (Double) -> Unit
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = onRefresh,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("Обновить", fontSize = 11.sp)
                }
                if (timeframe == PortfolioTimeframe.FifteenMinuteYear) {
                    OutlinedButton(
                        onClick = onMoex15mFullReload,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text("MOEX заново", fontSize = 11.sp, color = Color(0xFFB3E5FC))
                    }
                }
            }
        }
        Text(
            text = "Оценка в ₽: капитал ${"%.0f".format(Locale.US, metrics?.notionalRub ?: DEFAULT_PORTFOLIO_NOTIONAL_RUB)} ₽, плечо x${String.format(Locale.US, "%.1f", leverage)}, комиссия ${String.format(Locale.US, "%.3f", commissionPercentPerSide)}% за сторону.",
            color = Color(0xFFBDBDBD),
            fontSize = 11.sp
        )
        PortfolioTimeframeSelector(selected = timeframe, onSelect = onTimeframeChange)
        Text(
            text = when (timeframe) {
                PortfolioTimeframe.FifteenMinuteYear ->
                    "Интервал портфеля: ISS 10-мин → 15 мин; Z по окну (~$PORTFOLIO_M15_LOOKBACK_DAYS дн.). Ряд кэшируется в SQLite на телефоне; «Обновить» — догрузка хвоста с MOEX."
                PortfolioTimeframe.DailyOneYear ->
                    "Интервал портфеля: дневные закрытия (как график Рынок · 1Y)."
            },
            color = Color(0xFF9E9E9E),
            fontSize = 10.sp
        )
        PortfolioParamsControls(
            leverage = leverage,
            commissionPercentPerSide = commissionPercentPerSide,
            entryThreshold = entryThreshold,
            exitThreshold = exitThreshold,
            strategyViewMode = strategyViewMode,
            onLeverageChange = onLeverageChange,
            onCommissionChange = onCommissionChange,
            onEntryThresholdChange = onEntryThresholdChange,
            onExitThresholdChange = onExitThresholdChange
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
            "Комиссии (сумма)",
            "${formatRubSigned(-m.totalCommissionRub)} · ${String.format(Locale.US, "%.3f", m.commissionPercentPerSide)}% за сторону"
        )
        PortfolioStatCard(
            "Овернайт (Тинькофф)",
            "${formatRubSigned(-m.totalOvernightRub)} · ${String.format(Locale.US, "%.3f", TINKOFF_OVERNIGHT_FEE_PERCENT_PER_DAY)}%/день на заёмную часть"
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
private fun PortfolioParamsControls(
    leverage: Double,
    commissionPercentPerSide: Double,
    entryThreshold: Double,
    exitThreshold: Double,
    strategyViewMode: StrategyViewMode,
    onLeverageChange: (Double) -> Unit,
    onCommissionChange: (Double) -> Unit,
    onEntryThresholdChange: (Double) -> Unit,
    onExitThresholdChange: (Double) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ParamStepper(
                title = "Плечо",
                valueLabel = "x${String.format(Locale.US, "%.1f", leverage)}",
                onMinus = { onLeverageChange((leverage - 0.5).coerceAtLeast(1.0)) },
                onPlus = { onLeverageChange((leverage + 0.5).coerceAtMost(30.0)) },
                modifier = Modifier.weight(1f)
            )
            ParamStepper(
                title = "Комиссия / сторона",
                valueLabel = "${String.format(Locale.US, "%.3f", commissionPercentPerSide)}%",
                onMinus = { onCommissionChange((commissionPercentPerSide - 0.005).coerceAtLeast(0.0)) },
                onPlus = { onCommissionChange((commissionPercentPerSide + 0.005).coerceAtMost(1.0)) },
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ParamStepper(
                title = "Порог входа |Z|",
                valueLabel = String.format(Locale.US, "%.2f", entryThreshold),
                onMinus = {
                    onEntryThresholdChange(
                        (entryThreshold - PORTFOLIO_Z_THRESHOLD_STEP).coerceAtLeast(PORTFOLIO_Z_THRESHOLD_MIN)
                    )
                },
                onPlus = {
                    onEntryThresholdChange(
                        (entryThreshold + PORTFOLIO_Z_THRESHOLD_STEP).coerceAtMost(PORTFOLIO_Z_THRESHOLD_MAX)
                    )
                },
                modifier = Modifier.weight(1f)
            )
            ParamStepper(
                title = "Порог выхода |Z|",
                valueLabel = String.format(Locale.US, "%.2f", exitThreshold),
                onMinus = {
                    onExitThresholdChange(
                        (exitThreshold - PORTFOLIO_Z_THRESHOLD_STEP).coerceAtLeast(PORTFOLIO_Z_THRESHOLD_MIN)
                    )
                },
                onPlus = {
                    onExitThresholdChange(
                        (exitThreshold + PORTFOLIO_Z_THRESHOLD_STEP).coerceAtMost(PORTFOLIO_Z_THRESHOLD_MAX)
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }
        if (strategyViewMode == StrategyViewMode.Executed) {
            Text(
                text = "В режиме 'Реальные сигналы' пороги не влияют на сигналы, но сохраняются для режима 'Текущая модель'.",
                color = Color(0xFF9E9E9E),
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun ParamStepper(
    title: String,
    valueLabel: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = Color(0xFF9E9E9E), fontSize = 11.sp)
        Text(valueLabel, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onMinus, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) {
                Text("−")
            }
            Button(onClick = onPlus, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) {
                Text("+")
            }
        }
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
