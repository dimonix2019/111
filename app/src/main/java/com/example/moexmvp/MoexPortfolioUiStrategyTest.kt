package com.example.moexmvp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

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
    chartTradeSegments: List<TradingViewTradeSegment> = emptyList(),
    chartPointMarkers: List<ChartPointMarker> = emptyList(),
    zInitialWindow: Pair<Float, Float> = 1f to 0f,
    durationSummary: StrategyTestDurationSummary? = null,
    monthlyReturnSummary: StrategyTestMonthlyReturnSummary? = null,
    tradeRiskAssessments: List<StrategyTestTradeRiskAssessment> = emptyList(),
    onRefresh: () -> Unit,
    onMoex15mFullReload: () -> Unit,
    leverage: Double,
    commissionPercentPerSide: Double,
    accountSizeRub: Double,
    capitalUsagePercent: Double,
    maxLossDdPercent: Double,
    usePortfolioThresholds: Boolean = true,
    onUsePortfolioThresholdsChange: (Boolean) -> Unit = {},
    useLiveZSignals: Boolean = true,
    onUseLiveZSignalsChange: (Boolean) -> Unit = {},
    parityItems: List<StrategyTestProdParityItem> = emptyList(),
    onApplyProdAccountCash: (() -> Unit)? = null,
    onApplyProdReservePercent: (() -> Unit)? = null,
    simCommissionPercentPerSide: Double,
    execLogSummary: String,
    entryThreshold: Double,
    exitThreshold: Double,
    compoundReturns: Boolean,
    onCompoundReturnsChange: (Boolean) -> Unit,
    excludeRedZone: Boolean = false,
    onExcludeRedZoneChange: (Boolean) -> Unit = {},
    onLeverageChange: (Double) -> Unit,
    onCommissionChange: (Double) -> Unit,
    onAccountSizeChange: (Double) -> Unit,
    onCapitalUsageChange: (Double) -> Unit,
    onMaxLossDdPercentChange: (Double) -> Unit,
    onEntryThresholdChange: (Double) -> Unit,
    onExitThresholdChange: (Double) -> Unit,
    onExportCompareCsv: () -> Unit = {},
    dailyReconciliation: DailyPortfolioReconciliation? = null,
) {
    val (displayTradeItems, displayRiskAssessments) = remember(
        tradeItems,
        tradeRiskAssessments,
        excludeRedZone,
    ) {
        if (!excludeRedZone) {
            tradeItems to tradeRiskAssessments
        } else {
            filterStrategyTestTradeItemsWithRiskExcludingRedZone(tradeItems, tradeRiskAssessments)
        }
    }
    val displayDurationSummary = remember(displayTradeItems, durationSummary, excludeRedZone) {
        when {
            !excludeRedZone -> durationSummary
            displayTradeItems.isEmpty() -> null
            else -> buildStrategyTestDurationSummary(displayTradeItems.map { it.trade })
        }
    }
    val displayMetrics = remember(metrics, displayTradeItems, excludeRedZone) {
        strategyTestMetricsForDisplay(metrics, displayTradeItems, excludeRedZone)
    }
    val battleModeSubtitle = remember(parityItems, accountSizeRub, capitalUsagePercent) {
        val ok = parityItems.count { it.ok }
        buildString {
            append("$ok/${parityItems.size}")
            append(" · ${"%.0f".format(Locale.US, accountSizeRub)} ₽")
            append(" · ${"%.0f".format(Locale.US, capitalUsagePercent)}%")
        }
    }
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val (zChartHeightDp, equityChartHeightDp) = remember(screenHeightDp) {
        strategyTestLiveChartHeightsDp(screenHeightDp)
    }
    val zReferenceLines = remember(entryThreshold, exitThreshold, chartThresholds?.calculatedDate) {
        buildZScoreReferenceLines(
            DynamicThresholds(
                entry = entryThreshold,
                exit = exitThreshold,
                calculatedDate = chartThresholds?.calculatedDate,
            ),
            desktopStyle = true,
        )
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        PortfolioDataRefreshHeader(
            title = "Тест страт. · 15м",
            portfolioLoading = m15Loading || simulationComputing,
            onRefresh = onRefresh,
            onMoex15mFullReload = onMoex15mFullReload,
            compact = true,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF141414), RoundedCornerShape(10.dp))
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StrategyTestLiveTuningPanel(
                leverage = leverage,
                commissionPercentPerSide = commissionPercentPerSide,
                entryThreshold = entryThreshold,
                exitThreshold = exitThreshold,
                accountSizeRub = accountSizeRub,
                capitalUsagePercent = capitalUsagePercent,
                maxLossDdPercent = maxLossDdPercent,
                compoundReturns = compoundReturns,
                excludeRedZone = excludeRedZone,
                onLeverageChange = onLeverageChange,
                onCommissionChange = onCommissionChange,
                onEntryThresholdChange = onEntryThresholdChange,
                onExitThresholdChange = onExitThresholdChange,
                onAccountSizeChange = onAccountSizeChange,
                onCapitalUsageChange = onCapitalUsageChange,
                onMaxLossDdPercentChange = onMaxLossDdPercentChange,
                onCompoundReturnsChange = onCompoundReturnsChange,
                onExcludeRedZoneChange = onExcludeRedZoneChange,
            )
            val chartMetrics = displayMetrics
            val zChartReady = zScoreCandles.isNotEmpty() && m15ChartPoints.size >= 2
            if (zChartReady) {
                StrategyTestZScoreTradingViewChart(
                    candles = zScoreCandles,
                    chartPoints = m15ChartPoints,
                    pointMarkers = chartPointMarkers,
                    tradeSegments = chartTradeSegments,
                    tradeItems = displayTradeItems,
                    openPosition = if (excludeRedZone) null else metrics?.openPosition,
                    referenceLines = zReferenceLines,
                    initialWindow = zInitialWindow,
                    chartHeightDp = zChartHeightDp,
                    compactPortrait = true,
                )
            } else if (!m15Loading && !simulationComputing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(zChartHeightDp.dp)
                        .background(Color(0xFF171717), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Нет данных для Z-score", color = Color(0xFF757575), fontSize = 10.sp)
                }
            }
            if (chartMetrics != null &&
                chartMetrics.equityCurveRub.isNotEmpty() &&
                chartMetrics.drawdownCurveRub.isNotEmpty()
            ) {
                StrategyTestEquityDrawdownChartCard(
                    labels = chartMetrics.equityCurveLabels,
                    equityRub = chartMetrics.equityCurveRub,
                    drawdownRub = chartMetrics.drawdownCurveRub,
                    chartHeightDp = equityChartHeightDp,
                    compact = true,
                    totalPnlRub = chartMetrics.totalPnlRubApprox,
                    maxDrawdownRub = chartMetrics.maxDrawdownRubApprox,
                    recomputing = simulationComputing,
                )
            } else if (!m15Loading && !simulationComputing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(equityChartHeightDp.dp)
                        .background(Color(0xFF171717), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Нет данных для Equity", color = Color(0xFF757575), fontSize = 10.sp)
                }
            }
        }
        PortfolioCollapsibleSection(
            title = "Боевой режим симуляции",
            subtitle = battleModeSubtitle,
            defaultExpanded = false,
            compactHeader = true,
        ) {
            StrategyTestProdParityPanel(
                items = parityItems,
                usePortfolioThresholds = usePortfolioThresholds,
                onUsePortfolioThresholdsChange = onUsePortfolioThresholdsChange,
                useLiveZSignals = useLiveZSignals,
                onUseLiveZSignalsChange = onUseLiveZSignalsChange,
                onApplyProdAccountCash = onApplyProdAccountCash,
                onApplyProdReservePercent = onApplyProdReservePercent,
            )
        }
        PortfolioCollapsibleSection(
            title = "Сводка: Итого PnL и просадка",
            subtitle = displayMetrics?.let { m ->
                buildString {
                    append(formatRubSigned(m.totalPnlRubApprox))
                    append(" · просадка ")
                    append(formatRubSigned(-m.maxDrawdownRubApprox))
                    if (excludeRedZone && tradeItems.size > displayTradeItems.size) {
                        append(" · без красной зоны")
                    }
                }
            },
            defaultExpanded = false,
            compactHeader = true,
        ) {
            PortfolioHeroMetricsRow(metrics = displayMetrics)
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
            displayMetrics?.let { m ->
                PortfolioCollapsibleSection(
                    title = "Детальные показатели",
                    subtitle = if (excludeRedZone && tradeItems.size > displayTradeItems.size) {
                        "${displayTradeItems.size} сделок · без красной зоны"
                    } else {
                        "${displayTradeItems.size} сделок"
                    },
                    defaultExpanded = false,
                    compactHeader = true,
                ) {
                    PortfolioMetricGrid(m, showHeroDuplicate = false)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Сделки (${displayTradeItems.size})",
                        color = Color(0xFFE0E0E0),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = onExportCompareCsv,
                        enabled = tradeItems.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF80CBC4)),
                    ) {
                        Text("CSV сравнение", fontSize = 10.sp)
                    }
                }
                if (excludeRedZone && displayTradeItems.size < tradeItems.size) {
                    Text(
                        text = "Скрыто ${tradeItems.size - displayTradeItems.size} в красной зоне.",
                        color = Color(0xFFFFAB91),
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
                if (!dataTailWarning.isNullOrBlank()) {
                    Text(
                        text = dataTailWarning,
                        color = Color(0xFFFFCC80),
                        fontSize = 10.sp,
                        maxLines = 2
                    )
                }
                if (displayTradeItems.isEmpty()) {
                    Text(
                        text = if (excludeRedZone && tradeItems.isNotEmpty()) {
                            "Все сделки в красной зоне."
                        } else {
                            "Нет закрытых сделок."
                        },
                        color = Color(0xFF9E9E9E),
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                } else {
                    StrategyTestTradesTable(
                        tradeItems = displayTradeItems,
                        caption = "",
                        riskAssessments = displayRiskAssessments,
                    )
                }
                displayDurationSummary?.let { summary ->
                    StrategyTestDurationSummarySection(summary = summary)
                }
                monthlyReturnSummary?.let { summary ->
                    StrategyTestMonthlyReturnSection(
                        summary = summary,
                        excludeRedZone = excludeRedZone,
                        tradeItems = tradeItems,
                        tradeRiskAssessments = tradeRiskAssessments,
                    )
                }
                buildStrategyTestTradeRiskSummary(
                    trades = displayTradeItems.map { it.trade },
                    assessments = displayRiskAssessments,
                )?.let { riskSummary ->
                    StrategyTestTradeRiskSection(summary = riskSummary)
                }
            }
        }
        dailyReconciliation?.let { rec ->
            DailyReconciliationSection(rec)
        }
    }
}


@Composable
internal fun StrategyTestMonthlyReturnSection(
    summary: StrategyTestMonthlyReturnSummary,
    excludeRedZone: Boolean = false,
    tradeItems: List<StrategyTestTradeItem> = emptyList(),
    tradeRiskAssessments: List<StrategyTestTradeRiskAssessment> = emptyList(),
) {
    val monthlyBars = remember(summary, excludeRedZone, tradeItems, tradeRiskAssessments) {
        summary.monthlyBarsForDisplay(excludeRedZone, tradeItems, tradeRiskAssessments)
    }
    PortfolioCollapsibleSection(
        title = "Среднемесячная доходность",
        subtitle = formatStrategyTestMonthlyReturnSubtitle(summary, excludeRedZone),
        defaultExpanded = false,
        compactHeader = true,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (monthlyBars.isNotEmpty()) {
                StrategyTestMonthlyReturnBarChartCard(
                    bars = monthlyBars,
                    notionalRub = summary.notionalRub,
                    chartHeightDp = 260,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF252525), RoundedCornerShape(6.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                StrategyTestMonthlyReturnRow(
                    label = "Все сделки",
                    slice = summary.allTrades,
                    valueColor = if (summary.allTrades.totalPnlRub >= 0) Color(0xFF81C784) else Color(0xFFE57373),
                    emphasize = !excludeRedZone,
                )
                StrategyTestMonthlyReturnRow(
                    label = "Без красной зоны",
                    slice = summary.withoutRedZone,
                    excludedCount = summary.redZoneTradeCount,
                    valueColor = if (summary.withoutRedZone.totalPnlRub >= 0) Color(0xFF81C784) else Color(0xFFE57373),
                    emphasize = excludeRedZone && summary.redZoneTradeCount > 0,
                )
            }
        }
    }
}

@Composable
private fun StrategyTestMonthlyReturnRow(
    label: String,
    slice: StrategyTestReturnSlice,
    valueColor: Color,
    excludedCount: Int = 0,
    emphasize: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (emphasize) Color(0xFFFFAB91) else Color(0xFFE0E0E0),
                fontSize = 11.sp,
                fontWeight = if (emphasize) FontWeight.Medium else FontWeight.Normal,
            )
            Text(
                text = buildString {
                    append("n=${slice.tradeCount}")
                    if (excludedCount > 0) append(" · искл. $excludedCount")
                    append(" · ${slice.monthCount} мес.")
                },
                color = Color(0xFF9E9E9E),
                fontSize = 9.sp,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${formatPercentSigned(slice.avgMonthlyReturnPercent)}/мес",
                color = valueColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${formatRubSigned(slice.totalPnlRub)} · ${formatPercentSigned(slice.totalReturnPercent)}",
                color = Color(0xFF9E9E9E),
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
internal fun StrategyTestDurationSummarySection(summary: StrategyTestDurationSummary) {
    val subtitle = buildString {
        append("≤2 сут: ")
        append(String.format(Locale.US, "%.0f", summary.short.winPercent))
        append("% win · ")
        append(formatRubSigned(summary.short.totalPnlRub))
        append(" · >2 сут: ")
        append(String.format(Locale.US, "%.0f", summary.long.winPercent))
        append("% win · ")
        append(formatRubSigned(summary.long.totalPnlRub))
    }
    PortfolioCollapsibleSection(
        title = "Сводка по длительности сделок",
        subtitle = subtitle,
        defaultExpanded = false,
        compactHeader = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF252525), RoundedCornerShape(6.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StrategyTestDurationSummaryRow(
                bucket = summary.short,
                valueColor = Color(0xFF81C784),
            )
            StrategyTestDurationSummaryRow(
                bucket = summary.long,
                valueColor = Color(0xFFE57373),
            )
            StrategyTestDurationSummaryRow(
                bucket = summary.closedSecondDay,
                valueColor = Color(0xFFFFCC80),
            )
            if (summary.detailBuckets.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Детализация",
                    color = Color(0xFF9E9E9E),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
                summary.detailBuckets.forEach { bucket ->
                    StrategyTestDurationSummaryRow(bucket = bucket)
                }
            }
        }
    }
}

@Composable
internal fun StrategyTestTradeRiskSection(summary: StrategyTestTradeRiskSummary) {
    PortfolioCollapsibleSection(
        title = "Факторы риска сделок",
        subtitle = formatStrategyTestTradeRiskSummarySubtitle(summary),
        defaultExpanded = false,
        compactHeader = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF252525), RoundedCornerShape(6.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StrategyTestTradeRiskSummaryRow(
                label = "С флагами риска",
                count = summary.flaggedCount,
                lossCount = summary.flaggedLossCount,
                winCount = summary.flaggedWinCount,
                lossRate = summary.flaggedLossRate,
            )
            StrategyTestTradeRiskSummaryRow(
                label = "Умеренный",
                count = summary.elevatedCount,
            )
            StrategyTestTradeRiskSummaryRow(
                label = "Высокий",
                count = summary.highCount,
            )
            StrategyTestTradeRiskSummaryRow(
                label = "Критический",
                count = summary.criticalCount,
                emphasize = true,
            )
        }
    }
}

@Composable
private fun StrategyTestTradeRiskSummaryRow(
    label: String,
    count: Int,
    lossCount: Int? = null,
    winCount: Int? = null,
    lossRate: Double? = null,
    emphasize: Boolean = false,
) {
    val color = when {
        emphasize && count > 0 -> Color(0xFFE57373)
        count > 0 -> Color(0xFFFFCC80)
        else -> Color(0xFF757575)
    }
    Text(
        text = buildString {
            append(label)
            append(": ")
            append(count)
            if (lossCount != null && winCount != null && lossRate != null && count > 0) {
                append(" · убытков ")
                append(lossCount)
                append(" · win ")
                append(winCount)
                append(" · ")
                append(String.format(Locale.US, "%.0f", lossRate))
                append("% loss")
            }
        },
        color = color,
        fontSize = 10.sp,
        maxLines = 2,
    )
}

@Composable
private fun StrategyTestDurationSummaryRow(
    bucket: StrategyTestDurationBucket,
    valueColor: Color? = null,
) {
    val pnlColor = valueColor ?: rubDeltaColor(bucket.totalPnlRub)
    Text(
        text = buildString {
            append(bucket.title)
            append(" · n=")
            append(bucket.tradeCount)
            append(" · ")
            append(String.format(Locale.US, "%.0f", bucket.winPercent))
            append("% · ")
            append(formatRubSigned(bucket.totalPnlRub))
            append(" · ср. ")
            append(formatRubSigned(bucket.avgPnlRub))
        },
        color = pnlColor,
        fontSize = 10.sp,
        maxLines = 2,
    )
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
internal fun StrategyTestOptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedColor: Color = Color(0xFF1565C0),
    unselectedColor: Color = Color(0xFF252525),
) {
    Box(
        modifier = modifier
            .height(STRATEGY_TEST_MICRO_CONTROL_HEIGHT_DP.dp)
            .background(
                color = if (selected) selectedColor else unselectedColor,
                shape = RoundedCornerShape(7.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Color(0xFF9E9E9E),
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** Все параметры симуляции + переключатели в одной компактной сетке над Equity. */
@Composable
internal fun StrategyTestLiveTuningPanel(
    leverage: Double,
    commissionPercentPerSide: Double,
    entryThreshold: Double,
    exitThreshold: Double,
    accountSizeRub: Double,
    capitalUsagePercent: Double,
    maxLossDdPercent: Double,
    compoundReturns: Boolean,
    excludeRedZone: Boolean,
    onLeverageChange: (Double) -> Unit,
    onCommissionChange: (Double) -> Unit,
    onEntryThresholdChange: (Double) -> Unit,
    onExitThresholdChange: (Double) -> Unit,
    onAccountSizeChange: (Double) -> Unit,
    onCapitalUsageChange: (Double) -> Unit,
    onMaxLossDdPercentChange: (Double) -> Unit,
    onCompoundReturnsChange: (Boolean) -> Unit,
    onExcludeRedZoneChange: (Boolean) -> Unit,
) {
    val ddLabel = remember(maxLossDdPercent) {
        if (maxLossDdPercent <= 0.0) "выкл" else "${"%.0f".format(Locale.US, maxLossDdPercent)}%"
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ParamMicroStepper(
                title = "Плечо",
                valueLabel = "x${String.format(Locale.US, "%.1f", leverage)}",
                onMinus = { onLeverageChange((leverage - 0.5).coerceAtLeast(1.0)) },
                onPlus = { onLeverageChange((leverage + 0.5).coerceAtMost(30.0)) },
                modifier = Modifier.weight(1f),
            )
            ParamMicroStepper(
                title = "Комис.",
                valueLabel = formatStrategyTestCommissionMicro(commissionPercentPerSide),
                onMinus = { onCommissionChange((commissionPercentPerSide - 0.005).coerceAtLeast(0.0)) },
                onPlus = { onCommissionChange((commissionPercentPerSide + 0.005).coerceAtMost(1.0)) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ParamMicroStepper(
                title = "Вход",
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
                modifier = Modifier.weight(1f),
            )
            ParamMicroStepper(
                title = "Выход",
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
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ParamMicroStepper(
                title = "Счёт",
                valueLabel = formatStrategyTestAccountRubMicro(accountSizeRub),
                onMinus = {
                    onAccountSizeChange(
                        (accountSizeRub - 1_000.0).coerceIn(STRATEGY_TEST_ACCOUNT_RUB_MIN, STRATEGY_TEST_ACCOUNT_RUB_MAX)
                    )
                },
                onPlus = {
                    onAccountSizeChange(
                        (accountSizeRub + 1_000.0).coerceIn(STRATEGY_TEST_ACCOUNT_RUB_MIN, STRATEGY_TEST_ACCOUNT_RUB_MAX)
                    )
                },
                modifier = Modifier.weight(1f),
            )
            ParamMicroStepper(
                title = "%кап",
                valueLabel = "${"%.0f".format(Locale.US, capitalUsagePercent)}%",
                onMinus = { onCapitalUsageChange((capitalUsagePercent - 5.0).coerceAtLeast(10.0)) },
                onPlus = { onCapitalUsageChange((capitalUsagePercent + 5.0).coerceAtMost(100.0)) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ParamMicroStepper(
                title = "%DD",
                valueLabel = ddLabel,
                onMinus = { onMaxLossDdPercentChange((maxLossDdPercent - 1.0).coerceAtLeast(0.0)) },
                onPlus = { onMaxLossDdPercentChange((maxLossDdPercent + 1.0).coerceAtMost(50.0)) },
                modifier = Modifier.weight(1f),
            )
            StrategyTestOptionChip(
                label = "Кап",
                selected = compoundReturns,
                onClick = { onCompoundReturnsChange(!compoundReturns) },
                modifier = Modifier.weight(1f),
            )
            StrategyTestOptionChip(
                label = "−КЗ",
                selected = excludeRedZone,
                onClick = { onExcludeRedZoneChange(!excludeRedZone) },
                modifier = Modifier.weight(1f),
                selectedColor = Color(0xFFBF360C),
            )
        }
    }
}

@Composable
internal fun StrategyTestProdParityPanel(
    items: List<StrategyTestProdParityItem>,
    usePortfolioThresholds: Boolean,
    onUsePortfolioThresholdsChange: (Boolean) -> Unit,
    useLiveZSignals: Boolean,
    onUseLiveZSignalsChange: (Boolean) -> Unit,
    onApplyProdAccountCash: (() -> Unit)?,
    onApplyProdReservePercent: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A2332), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { item ->
            Text(
                text = "${if (item.ok) "✓" else "○"} ${item.label}",
                color = if (item.ok) Color(0xFF80CBC4) else Color(0xFFFFCC80),
                fontSize = 9.sp,
                maxLines = 2,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Пороги = Портфель", color = Color(0xFFE0E0E0), fontSize = 10.sp)
            Switch(checked = usePortfolioThresholds, onCheckedChange = onUsePortfolioThresholdsChange)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Z как live (без журнала)", color = Color(0xFFE0E0E0), fontSize = 10.sp)
            Switch(checked = useLiveZSignals, onCheckedChange = onUseLiveZSignalsChange)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            onApplyProdAccountCash?.let { applyCash ->
                OutlinedButton(
                    onClick = applyCash,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF81D4FA)),
                ) {
                    Text("Cash Prod", fontSize = 9.sp, maxLines = 1)
                }
            }
            onApplyProdReservePercent?.let { applyReserve ->
                OutlinedButton(
                    onClick = applyReserve,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF81D4FA)),
                ) {
                    Text("Резерв ${"%.0f".format(Locale.US, PROD_EFFECTIVE_CAPITAL_USAGE_PERCENT)}%", fontSize = 9.sp, maxLines = 1)
                }
            }
        }
    }
}

/** Z-график «Тест страт.» — TradingView (как «Рынок»), маркеры через strategyTestTradeItems. */
@Composable
internal fun StrategyTestZScoreTradingViewChart(
    candles: List<CandlePoint>,
    chartPoints: List<DataPoint>,
    pointMarkers: List<ChartPointMarker>,
    tradeSegments: List<TradingViewTradeSegment>,
    tradeItems: List<StrategyTestTradeItem>,
    openPosition: PortfolioOpenPosition?,
    referenceLines: List<ChartReferenceLine>,
    initialWindow: Pair<Float, Float>,
    chartHeightDp: Int = 320,
    landscapeMinimal: Boolean = false,
    compactPortrait: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (candles.isEmpty()) return
    val title = when {
        landscapeMinimal -> ""
        compactPortrait -> "Z-score · 15м"
        else -> "Z-score · 15м (TradingView · все сделки симуляции)"
    }
    TradingViewZScoreChartCard(
        title = title,
        candles = candles,
        displayPoints = chartPoints,
        chartHeightDp = chartHeightDp,
        referenceLines = referenceLines,
        pointMarkers = pointMarkers,
        tradeSegments = tradeSegments,
        strategyTestTradeItems = tradeItems,
        openPosition = openPosition,
        initialWindowWidth = initialWindow.first,
        initialWindowStart = initialWindow.second,
        landscapeMinimal = landscapeMinimal,
        modifier = modifier,
    )
}
