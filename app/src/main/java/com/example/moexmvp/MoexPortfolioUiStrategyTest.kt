package com.example.moexmvp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
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
    entryThreshold: Double,
    exitThreshold: Double,
    compoundReturns: Boolean,
    onCompoundReturnsChange: (Boolean) -> Unit,
    excludeRedZone: Boolean = false,
    onExcludeRedZoneChange: (Boolean) -> Unit = {},
    onLeverageChange: (Double) -> Unit,
    onCommissionChange: (Double) -> Unit,
    onEntryThresholdChange: (Double) -> Unit,
    onExitThresholdChange: (Double) -> Unit,
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
            text = "Симуляция: одно пересечение Z на бар; после выхода — новый вход только после возврата Z за полосу exit (re-arm).",
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
            val zReferenceLines = remember(chartThresholds) {
                buildZScoreReferenceLines(chartThresholds, desktopStyle = true)
            }
            StrategyTestZScoreTradingViewChart(
                candles = zScoreCandles,
                chartPoints = m15ChartPoints,
                pointMarkers = chartPointMarkers,
                tradeSegments = chartTradeSegments,
                tradeItems = displayTradeItems,
                openPosition = if (excludeRedZone) null else metrics?.openPosition,
                referenceLines = zReferenceLines,
                initialWindow = zInitialWindow,
                chartHeightDp = 320,
            )
            metrics?.let { m ->
                val chartMetrics = displayMetrics ?: m
                if (chartMetrics.equityCurveRub.isNotEmpty() && chartMetrics.drawdownCurveRub.isNotEmpty()) {
                    StrategyTestEquityDrawdownChartCard(
                        labels = chartMetrics.equityCurveLabels,
                        equityRub = chartMetrics.equityCurveRub,
                        drawdownRub = chartMetrics.drawdownCurveRub,
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Без красной зоны",
                    color = if (excludeRedZone) Color(0xFFFFAB91) else Color(0xFFE0E0E0),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Switch(
                    checked = excludeRedZone,
                    onCheckedChange = onExcludeRedZoneChange
                )
            }
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
                    title = "Детальные показатели симуляции",
                    subtitle = if (excludeRedZone && tradeItems.size > displayTradeItems.size) {
                        "без красной зоны · ${displayTradeItems.size} сделок"
                    } else {
                        "таблица метрик"
                    },
                    defaultExpanded = false,
                    compactHeader = true,
                ) {
                    Text(
                        text = m.periodDescription,
                        color = Color(0xFF757575),
                        fontSize = 10.sp
                    )
                    PortfolioMetricGrid(m, showHeroDuplicate = false)
                }
                Text(
                    text = "Сделки симуляции (${displayTradeItems.size}) · ${m.periodDescription}",
                    color = Color(0xFFE0E0E0),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (excludeRedZone && displayTradeItems.size < tradeItems.size) {
                    Text(
                        text = "Скрыто ${tradeItems.size - displayTradeItems.size} сделок в красной зоне (≥4 балла).",
                        color = Color(0xFFFFAB91),
                        fontSize = 10.sp,
                        maxLines = 2,
                    )
                }
                if (!dataTailWarning.isNullOrBlank()) {
                    Text(
                        text = dataTailWarning,
                        color = Color(0xFFFFCC80),
                        fontSize = 10.sp,
                        maxLines = 3
                    )
                }
                if (displayTradeItems.isEmpty()) {
                    Text(
                        text = if (excludeRedZone && tradeItems.isNotEmpty()) {
                            "Все сделки в красной зоне — переключите фильтр или измените пороги."
                        } else {
                            "Нет закрытых сделок в симуляции. Проверьте пороги и нажмите «MOEX заново»."
                        },
                        color = Color(0xFF9E9E9E),
                        fontSize = 11.sp,
                        maxLines = 3
                    )
                } else {
                    StrategyTestTradesTable(
                        tradeItems = displayTradeItems,
                        caption = "Сделок: ${displayTradeItems.size}. Прокрутка вправо — все столбцы.",
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
        defaultExpanded = true,
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
                Text(
                    text = "По месяцу выхода сделки, % от номинала ${"%.0f".format(Locale.US, summary.notionalRub)} ₽.",
                    color = Color(0xFF757575),
                    fontSize = 9.sp,
                    maxLines = 2,
                )
                Text(
                    text = "Красная зона: высокий/критический риск (≥4 балла) — тёмно-красная подсветка строки.",
                    color = Color(0xFF757575),
                    fontSize = 9.sp,
                    maxLines = 2,
                )
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
            Text(
                text = "Корзина · n · win% · сумма · ср.",
                color = Color(0xFF757575),
                fontSize = 9.sp,
            )
            StrategyTestDurationSummaryRow(
                bucket = summary.short,
                valueColor = Color(0xFF81C784),
            )
            StrategyTestDurationSummaryRow(
                bucket = summary.long,
                valueColor = Color(0xFFE57373),
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
            Text(
                text = "Подсветка: ≥3 балла — в основном >2 сут, overnight >50 ₽ (сут+), Z<1 при удержании >6 ч.",
                color = Color(0xFF757575),
                fontSize = 9.sp,
                maxLines = 3,
            )
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
            Text(
                text = "Базовая доля убытков по всем сделкам: ${String.format(Locale.US, "%.0f", summary.baselineLossRate)}%",
                color = Color(0xFF9E9E9E),
                fontSize = 10.sp,
            )
            Text(
                text = "Флаги: >2д · >5д · Ovn50 (>1 сут) · Ovn100 · Z<1 (>6 ч) · 12–14 · 13ч · Пт>2д",
                color = Color(0xFF757575),
                fontSize = 9.sp,
                maxLines = 2,
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
    modifier: Modifier = Modifier,
) {
    if (candles.isEmpty()) return
    TradingViewZScoreChartCard(
        title = if (landscapeMinimal) "" else "Z-score · 15м (TradingView · все сделки симуляции)",
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
