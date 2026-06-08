package com.example.moexmvp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Симуляция Z на полном 15м ряду (~255 дн.); без графика Z и без трейлинга выхода. */
@Composable
internal fun StrategyTestTabContent(
    metrics: PortfolioMetrics?,
    simulationComputing: Boolean = false,
    tradeItems: List<StrategyTestTradeItem>,
    m15Loading: Boolean,
    m15Error: String?,
    m15ChartPoints: List<DataPoint> = emptyList(),
    zScoreCandles: List<CandlePoint> = emptyList(),
    chartThresholds: DynamicThresholds? = null,
    zInitialWindow: Pair<Float, Float> = 1f to 0f,
    onRefresh: () -> Unit,
    onMoex15mFullReload: () -> Unit,
    leverage: Double,
    commissionPercentPerSide: Double,
    entryThreshold: Double,
    exitThreshold: Double,
    compoundReturns: Boolean,
    onCompoundReturnsChange: (Boolean) -> Unit,
    onLeverageChange: (Double) -> Unit,
    onCommissionChange: (Double) -> Unit,
    onEntryThresholdChange: (Double) -> Unit,
    onExitThresholdChange: (Double) -> Unit,
    presets: List<PortfolioPreset>,
    onApplyPreset: (PortfolioPreset) -> Unit,
    onDeletePreset: (String) -> Unit,
    onWalkForward: () -> Unit,
    walkForwardBusy: Boolean,
    dailyReconciliation: DailyPortfolioReconciliation? = null,
    portfolioEntryThreshold: Double? = null,
    portfolioExitThreshold: Double? = null,
    embedTradeRows: Boolean = true,
) {
    val exitRuleNote =
        "выход по фиксированному порогу ±${String.format(Locale.US, "%.2f", exitThreshold)}"
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        PortfolioDataRefreshHeader(
            title = "Тест стратегии · 15м",
            portfolioLoading = m15Loading || simulationComputing,
            onRefresh = onRefresh,
            onMoex15mFullReload = onMoex15mFullReload
        )
        if (simulationComputing && metrics == null && m15Error == null) {
            Text(
                text = "Считаем симуляцию на ${PORTFOLIO_M15_LOOKBACK_DAYS} дн. 15м…",
                color = Color(0xFF9FA8DA),
                fontSize = 11.sp
            )
        }
        Text(
            text = "${"%.0f".format(Locale.US, metrics?.notionalRub ?: DEFAULT_PORTFOLIO_NOTIONAL_RUB)} ₽ · x${String.format(Locale.US, "%.1f", leverage)} · ${String.format(Locale.US, "%.3f", commissionPercentPerSide)}% / сторона · симуляция по Z",
            color = Color(0xFF9E9E9E),
            fontSize = 10.sp,
            maxLines = 2
        )
        Text(
            text = "Пороги ниже — только «Тест страт.». Розовые на «Портфеле» — отдельно; при одинаковых ± и фикс. номинале списки закрытых сделок совпадают.",
            color = Color(0xFFF48FB1),
            fontSize = 10.sp,
            maxLines = 3
        )
        PortfolioParamsControls(
            leverage = leverage,
            commissionPercentPerSide = commissionPercentPerSide,
            entryThreshold = entryThreshold,
            exitThreshold = exitThreshold,
            showZThresholdSteppers = true,
            showExitThresholdStepper = true,
            onLeverageChange = onLeverageChange,
            onCommissionChange = onCommissionChange,
            onEntryThresholdChange = onEntryThresholdChange,
            onExitThresholdChange = onExitThresholdChange
        )
        if (zScoreCandles.isNotEmpty() && chartThresholds != null) {
            val zReferenceLines = buildZScoreReferenceLines(chartThresholds, desktopStyle = true)
            val chartMarkers by produceState(
                initialValue = emptyList<ChartPointMarker>(),
                m15ChartPoints,
                tradeItems,
            ) {
                value = withContext(Dispatchers.Default) {
                    buildZScoreMarkersFromStrategyTestTrades(
                        m15ChartPoints,
                        filterStrategyTestTradesForChart(m15ChartPoints, tradeItems),
                    )
                }
            }
            CandlestickChartCard(
                title = "Z-score · 15м (тот же ряд, что симуляция)",
                candles = zScoreCandles,
                chartHeightDp = 260,
                referenceLines = zReferenceLines,
                pointMarkers = chartMarkers,
                showLegend = false,
                showMinMax = false,
                enableZoomPan = true,
                markerScale = 1.25f,
                rightPlotPaddingFraction = CHART_RIGHT_PLOT_PADDING_FRACTION,
                showZoomHint = false,
                compactLayout = true,
                trackpadGestures = false,
                initialWindowWidth = zInitialWindow.first,
                initialWindowStart = zInitialWindow.second,
                useDesktopStyle = true,
                displayMode = ChartDisplayMode.Candles,
                showPlotlyToolbar = true,
            )
            metrics?.let { m ->
                if (m.equityCurveRub.isNotEmpty() && m.drawdownCurveRub.isNotEmpty()) {
                    StrategyTestEquityDrawdownChartCard(
                        labels = m.equityCurveLabels,
                        equityRub = m.equityCurveRub,
                        drawdownRub = m.drawdownCurveRub,
                        chartHeightDp = 280,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Капитализация",
                color = Color(0xFFE0E0E0),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Switch(
                checked = compoundReturns,
                onCheckedChange = onCompoundReturnsChange
            )
        }
        Text(
            text = "Сделки ниже — все закрытые круги симуляции на 15м ряду за ${PORTFOLIO_M15_LOOKBACK_DAYS} дн. " +
                "до сегодня включительно ($exitRuleNote, не журнал и не демо).",
            color = Color(0xFF757575),
            fontSize = 10.sp,
            maxLines = 3
        )
        if (portfolioEntryThreshold != null && portfolioExitThreshold != null) {
            val entryDiffers = kotlin.math.abs(entryThreshold - portfolioEntryThreshold) > 0.009
            val exitDiffers = kotlin.math.abs(exitThreshold - portfolioExitThreshold) > 0.009
            if (entryDiffers || exitDiffers) {
                Text(
                    text = "Пороги «Тест страт.» (вход ±${String.format(Locale.US, "%.2f", entryThreshold)} / выход ±${String.format(Locale.US, "%.2f", exitThreshold)}) " +
                        "не совпадают с розовыми на «Портфеле» (±${String.format(Locale.US, "%.2f", portfolioEntryThreshold)} / ±${String.format(Locale.US, "%.2f", portfolioExitThreshold)}). " +
                        "Сделки на вкладках будут различаться.",
                    color = Color(0xFFFFCC80),
                    fontSize = 10.sp,
                    maxLines = 4
                )
            } else if (!compoundReturns) {
                Text(
                    text = "Пороги совпадают с «Портфелем» и режим «Фикс. номинал» — закрытые сделки в списке должны совпасть со сводкой портфеля (после «Обновить» на обеих вкладках).",
                    color = Color(0xFFA5D6A7),
                    fontSize = 10.sp,
                    maxLines = 4
                )
            }
        }
        PortfolioCollapsibleSection(
            title = "Сводка: Итого PnL и просадка",
            subtitle = metrics?.let { m ->
                "${formatRubSigned(m.totalPnlRubApprox)} · просадка ${formatRubSigned(-m.maxDrawdownRubApprox)}"
            },
            defaultExpanded = false
        ) {
            PortfolioHeroMetricsRow(metrics = metrics)
        }
        PortfolioPresetSection(
            presets = presets,
            onApply = onApplyPreset,
            onDelete = onDeletePreset,
            onSave = {},
            showSaveButton = false
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onWalkForward,
                enabled = !walkForwardBusy,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AutoGraph, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFFFCC80))
                    Spacer(Modifier.width(4.dp))
                    Text("Walk-forward", fontSize = 10.sp, color = Color(0xFFFFCC80))
                }
            }
            if (walkForwardBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Color(0xFF64B5F6),
                    strokeWidth = 2.dp
                )
            }
            Text(
                text = "OOS по кварталам, штраф за сделки",
                color = Color(0xFF616161),
                fontSize = 9.sp,
                modifier = Modifier.weight(1f)
            )
        }

        val dataTailWarning = m15Error
        if (dataTailWarning != null && metrics == null) {
            Text(dataTailWarning, color = Color(0xFFEF9A9A), fontSize = 11.sp)
            Button(onClick = onRefresh, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Повторить", fontSize = 11.sp)
                }
            }
        } else if (!m15Loading && !simulationComputing && metrics == null) {
            Text("Недостаточно данных для симуляции.", color = Color(0xFFBDBDBD), fontSize = 11.sp)
        } else {
            metrics?.let { m ->
                PortfolioCollapsibleSection(
                    title = "Детальные показатели симуляции",
                    subtitle = "таблица метрик",
                    defaultExpanded = false
                ) {
                    Text(
                        text = m.periodDescription,
                        color = Color(0xFF757575),
                        fontSize = 10.sp
                    )
                    PortfolioMetricGrid(m, showHeroDuplicate = false)
                }
                Text(
                    text = "Сделки симуляции (${tradeItems.size}) · ${m.periodDescription}",
                    color = Color(0xFFE0E0E0),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (!dataTailWarning.isNullOrBlank()) {
                    Text(
                        text = dataTailWarning,
                        color = Color(0xFFFFCC80),
                        fontSize = 10.sp,
                        maxLines = 3
                    )
                }
                if (tradeItems.isEmpty()) {
                    Text(
                        text = "Нет закрытых сделок в симуляции. Проверьте пороги и нажмите «MOEX заново».",
                        color = Color(0xFF9E9E9E),
                        fontSize = 11.sp,
                        maxLines = 3
                    )
                } else if (embedTradeRows) {
                    tradeItems.forEachIndexed { index, item ->
                        StrategyTestTradeRow(index = index + 1, item = item)
                    }
                }
            }
        }
        dailyReconciliation?.let { rec ->
            DailyReconciliationSection(rec)
        }
    }
}


@Composable
internal fun StrategyTestExitModeControls(
    exitMode: ZStrategyExitMode,
    zPeakTrailZ: Double,
    onExitModeChange: (ZStrategyExitMode) -> Unit,
    onZPeakTrailZChange: (Double) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Выход из сделки",
            color = Color(0xFFE0E0E0),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            StrategyExitModeButton(
                text = "Фикс. |Z|",
                selected = exitMode == ZStrategyExitMode.FixedThreshold,
                onClick = { onExitModeChange(ZStrategyExitMode.FixedThreshold) },
                modifier = Modifier.weight(1f)
            )
            StrategyExitModeButton(
                text = "Трейл Z",
                selected = exitMode == ZStrategyExitMode.ZPeakTrailing,
                onClick = { onExitModeChange(ZStrategyExitMode.ZPeakTrailing) },
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = when (exitMode) {
                ZStrategyExitMode.FixedThreshold ->
                    "Закрытие по прежнему порогу выхода |Z|; розовые пороги «Портфеля» не меняются."
                ZStrategyExitMode.ZPeakTrailing ->
                    "После входа запоминаем лучший Z и закрываемся при откате от пика на выбранный шаг."
            },
            color = Color(0xFF757575),
            fontSize = 9.sp,
            maxLines = 3
        )
        if (exitMode == ZStrategyExitMode.ZPeakTrailing) {
            ParamStepper(
                title = "Трейл Z от пика",
                valueLabel = String.format(Locale.US, "%.2f", zPeakTrailZ),
                onMinus = {
                    onZPeakTrailZChange(
                        (zPeakTrailZ - PORTFOLIO_Z_THRESHOLD_STEP)
                            .coerceAtLeast(STRATEGY_TEST_Z_PEAK_TRAIL_MIN)
                    )
                },
                onPlus = {
                    onZPeakTrailZChange(
                        (zPeakTrailZ + PORTFOLIO_Z_THRESHOLD_STEP)
                            .coerceAtMost(STRATEGY_TEST_Z_PEAK_TRAIL_MAX)
                    )
                },
                containerColor = Color(0xFF263238),
                titleColor = Color(0xFFB3E5FC),
                valueTextColor = Color(0xFFE1F5FE),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
internal fun StrategyExitModeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFF1565C0) else Color(0xFF37474F),
            contentColor = Color.White
        ),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Text(text, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
internal fun StrategyTestTradeRow(index: Int, item: StrategyTestTradeItem) {
    PortfolioTradeRow(index = index, t = item.trade, showTradeDuration = true)
}
