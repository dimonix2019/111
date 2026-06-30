package com.example.moexmvp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

internal fun formatPortfolioTableZ(z: Double): String =
    if (z.isNaN()) "—" else String.format(Locale.US, "%.2f", z)

internal fun formatPortfolioTableRub(value: Double): String =
    if (value.isNaN()) "—" else formatRubSigned(value)

/** Расход (комиссия, овернайт): в данных хранится модуль, в таблице — со знаком «−». */
internal fun formatPortfolioTableCostRub(value: Double): String =
    if (value.isNaN()) "—" else formatRubSigned(-kotlin.math.abs(value))

/** В таблице портфеля: «2026-06-01 10:15» → «26-06-01 10:15». */
internal fun compactPortfolioTableDateLabel(text: String): String {
    if (text == "—") return text
    return Regex("""20(\d{2}-\d{2}-\d{2}(?: \d{2}:\d{2})?)""").replace(text) { it.groupValues[1] }
}

/** Дата и время на двух строках — столбец уже: «26-06-01\n10:15», «1 long 26-06-01\n10:15». */
internal fun compactPortfolioTableDateTimeTwoLines(text: String): String {
    if (text == "—") return text
    val compact = compactPortfolioTableDateLabel(text)
    val time = Regex("""\d{2}:\d{2}$""").find(compact)?.value ?: return compact
    val datePart = compact.removeSuffix(time).trimEnd()
    return if (datePart.isEmpty()) time else "$datePart\n$time"
}

private data class PortfolioTradeGroupColumn(
    val title: String,
    val widthDp: Int,
)

@Composable
internal fun PortfolioTradeTableCell(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFE0E0E0)
) {
    Text(
        text = text,
        color = color,
        fontSize = 8.sp,
        maxLines = 3,
        lineHeight = 10.sp,
        modifier = modifier.padding(horizontal = 1.dp, vertical = 1.dp)
    )
}

@Composable
private fun PortfolioTradeGroupSpacerCells(widths: List<Int>) {
    widths.forEach { w ->
        PortfolioTradeTableCell(
            "·",
            Modifier.widthIn(min = w.dp).width(w.dp),
            Color(0xFF424242)
        )
    }
}

@Composable
private fun PortfolioOpenTradeRiskIconCell(
    group: PortfolioTradeGroupRow,
    assessment: StrategyTestTradeRiskAssessment?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .widthIn(min = OPEN_TRADE_RISK_ICON_COL_WIDTH_DP.dp)
            .width(OPEN_TRADE_RISK_ICON_COL_WIDTH_DP.dp)
            .padding(horizontal = 1.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        val flagged = assessment?.takeIf { strategyTestTradeRiskIsFlagged(it) && group.isOpen }
        if (flagged != null) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Сделка под риском",
                tint = strategyTestTradeRiskLevelColor(flagged.level),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
internal fun PortfolioTradeOrdersGroupedTable(
    groups: List<PortfolioTradeGroupRow>,
    caption: String,
    exitZColumnTitle: String = "Zвых",
    onCloseOpenTrade: ((tradeId: String) -> Unit)? = null,
    closingTradeId: String? = null,
    riskAssessments: List<StrategyTestTradeRiskAssessment> = emptyList(),
    showRiskScoreBreakdown: Boolean = false,
    showLeadingOpenRiskIcon: Boolean = false,
) {
    if (groups.isEmpty()) return
    val context = LocalContext.current
    val scroll = rememberScrollState()
    if (caption.isNotBlank()) {
        Text(
            text = caption,
            color = Color(0xFF757575),
            fontSize = 9.sp,
            modifier = Modifier.padding(bottom = 4.dp),
            maxLines = 4
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
            .padding(vertical = 6.dp)
    ) {
        val riskBreakdownCols = if (showRiskScoreBreakdown) {
            portfolioTradeRiskBreakdownColumnTitles().map { PortfolioTradeGroupColumn(it, 22) }
        } else {
            emptyList()
        }
        val groupCols = buildList {
            if (showLeadingOpenRiskIcon) {
                add(PortfolioTradeGroupColumn("", OPEN_TRADE_RISK_ICON_COL_WIDTH_DP))
            }
            addAll(
                listOf(
                    PortfolioTradeGroupColumn("ID", 40),
                    PortfolioTradeGroupColumn("Сигн.", 46),
                    PortfolioTradeGroupColumn("Бар", 44),
                    PortfolioTradeGroupColumn("Получ.", 44),
                    PortfolioTradeGroupColumn("Вход", 44),
                    PortfolioTradeGroupColumn("Выход", 44),
                    PortfolioTradeGroupColumn("Подтв.", 40),
                    PortfolioTradeGroupColumn("Zвх", 30),
                    PortfolioTradeGroupColumn(exitZColumnTitle, 30),
                    PortfolioTradeGroupColumn("PnL L", 44),
                    PortfolioTradeGroupColumn("PnL S", 44),
                    PortfolioTradeGroupColumn("Чист.", 48),
                    PortfolioTradeGroupColumn("Ком.", 42),
                    PortfolioTradeGroupColumn("Овн.", 42),
                ),
            )
            addAll(riskBreakdownCols)
            add(PortfolioTradeGroupColumn("Риск", if (showRiskScoreBreakdown) 28 else 72))
        }
        val orderCols = listOf(
            PortfolioTradeGroupColumn("№", 18),
            PortfolioTradeGroupColumn("Нога", 28),
            PortfolioTradeGroupColumn("Тик.", 32),
            PortfolioTradeGroupColumn("Стор.", 50),
        )
        val groupWidths = groupCols.map { it.widthDp }
        Row(Modifier.padding(horizontal = 1.dp, vertical = 1.dp)) {
            (groupCols + orderCols).forEach { col ->
                PortfolioTradeTableCell(
                    text = col.title,
                    modifier = Modifier.widthIn(min = col.widthDp.dp).width(col.widthDp.dp),
                    color = Color(0xFF90A4AE)
                )
            }
        }
        groups.take(40).forEachIndexed { groupIndex, group ->
            val risk = riskAssessments.getOrNull(groupIndex)
            val rowBackground = if (risk != null && strategyTestTradeRiskIsFlagged(risk)) {
                strategyTestTradeRiskRowColor(risk.level)
            } else {
                Color(0xFF242424)
            }
            Column(
                Modifier
                    .padding(vertical = 2.dp)
                    .background(rowBackground, RoundedCornerShape(4.dp))
                    .padding(vertical = 2.dp)
            ) {
                if (group.isOpen && onCloseOpenTrade != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${group.tradeDisplayId} · ${compactPortfolioTableDateTimeTwoLines(group.entryTimeMsk)}",
                            color = Color(0xFFB3E5FC),
                            fontSize = 10.sp,
                            maxLines = 3,
                            lineHeight = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = { onCloseOpenTrade(group.tradeId) },
                            enabled = closingTradeId != group.tradeId,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFFFAB91)
                            )
                        ) {
                            Text(
                                text = if (closingTradeId == group.tradeId) "Закрытие…" else "Закрыть",
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                group.orders.forEachIndexed { orderIdx, order ->
                    val isFirstOrder = orderIdx == 0
                    val tickerColor = when (order.legRole) {
                        "Long" -> Color(0xFF81C784)
                        "Short" -> Color(0xFFFFAB91)
                        else -> Color(0xFFE0E0E0)
                    }
                    Row(Modifier.padding(horizontal = 1.dp, vertical = 1.dp)) {
                        if (isFirstOrder) {
                            val assessment = risk ?: StrategyTestTradeRiskAssessment(
                                flags = emptyList(),
                                level = StrategyTestTradeRiskLevel.None,
                                score = 0,
                                entryZ = null,
                                breakdown = TradeRiskScoreBreakdown(),
                            )
                            val riskColor = if (strategyTestTradeRiskIsFlagged(assessment)) {
                                strategyTestTradeRiskLevelColor(assessment.level)
                            } else {
                                Color(0xFF757575)
                            }
                            val riskText = if (showRiskScoreBreakdown) {
                                formatPortfolioTradeRiskTotalScore(assessment)
                            } else {
                                formatStrategyTestTradeRiskFlags(assessment)
                            }
                            val breakdownCells = if (showRiskScoreBreakdown) {
                                portfolioTradeRiskBreakdownPointValues(assessment).map { points ->
                                    formatTradeRiskScorePoints(points) to
                                        if (points > 0) Color(0xFFFFCC80) else Color(0xFF757575)
                                }
                            } else {
                                emptyList()
                            }
                            val cells = listOf(
                                group.tradeDisplayId to Color(0xFFE0E0E0),
                                compactPortfolioTableDateTimeTwoLines(group.entrySignalId) to Color(0xFFCE93D8),
                                compactPortfolioTableDateTimeTwoLines(group.entrySignalBarTimeMsk) to Color(0xFFCE93D8),
                                compactPortfolioTableDateTimeTwoLines(group.entrySignalReceivedMsk) to Color(0xFF81D4FA),
                                compactPortfolioTableDateTimeTwoLines(group.entryTimeMsk) to Color(0xFFE0E0E0),
                                compactPortfolioTableDateTimeTwoLines(group.exitTimeMsk) to Color(0xFFE0E0E0),
                                group.confirmLabel to Color(0xFFE0E0E0),
                                formatPortfolioTableZ(group.entryZ) to Color(0xFFE0E0E0),
                                formatPortfolioTableZ(group.exitZ) to Color(0xFFE0E0E0),
                                formatPortfolioTableRub(group.legLongPnlSplitRubApprox) to
                                    if (group.legLongPnlSplitRubApprox.isNaN()) Color(0xFF757575)
                                    else rubDeltaColor(group.legLongPnlSplitRubApprox),
                                formatPortfolioTableRub(group.legShortPnlSplitRubApprox) to
                                    if (group.legShortPnlSplitRubApprox.isNaN()) Color(0xFF757575)
                                    else rubDeltaColor(group.legShortPnlSplitRubApprox),
                                formatPortfolioTableRub(group.netPnlRubApprox) to
                                    if (group.netPnlRubApprox.isNaN()) Color(0xFF757575)
                                    else rubDeltaColor(group.netPnlRubApprox),
                                formatPortfolioTableCostRub(group.commissionRubApprox) to Color(0xFFFFAB91),
                                formatPortfolioTableCostRub(group.overnightRubApprox) to Color(0xFFFFAB91),
                            ) + breakdownCells + listOf(
                                riskText to riskColor,
                            )
                            var cellIndex = 0
                            if (showLeadingOpenRiskIcon) {
                                PortfolioOpenTradeRiskIconCell(
                                    group = group,
                                    assessment = risk,
                                    modifier = Modifier.widthIn(min = groupWidths[cellIndex].dp)
                                        .width(groupWidths[cellIndex].dp),
                                )
                                cellIndex += 1
                            }
                            cells.forEachIndexed { index, (text, color) ->
                                val w = groupWidths[cellIndex + index]
                                PortfolioTradeTableCell(
                                    text,
                                    Modifier.widthIn(min = w.dp).width(w.dp),
                                    color
                                )
                            }
                        } else {
                            PortfolioTradeGroupSpacerCells(groupWidths)
                        }
                        orderCols.forEachIndexed { index, col ->
                            val text = when (index) {
                                0 -> "${order.orderIndex}"
                                1 -> order.legRole
                                2 -> order.ticker
                                else -> order.sideRu
                            }
                            val color = when (index) {
                                0 -> Color(0xFFCE93D8)
                                2 -> tickerColor
                                else -> Color(0xFFE0E0E0)
                            }
                            PortfolioTradeTableCell(
                                text,
                                Modifier.widthIn(min = col.widthDp.dp).width(col.widthDp.dp),
                                color
                            )
                        }
                    }
                }
                val execFills = remember(group.tradeId) {
                    TradeExecutionLog.fillsForTrade(context, group.tradeId)
                }
                if (execFills.isNotEmpty()) {
                    Text(
                        text = execFills.joinToString(" · ") { fill ->
                            "${fill.phase.name} ${fill.ticker}: ${TradeExecutionLog.formatLegFillSummary(fill)}"
                        },
                        color = Color(0xFF80CBC4),
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        maxLines = 4,
                    )
                }
            }
            Spacer(
                Modifier
                    .padding(vertical = 4.dp)
                    .height(1.dp)
                    .fillMaxWidth()
                    .background(Color(0xFF303030))
            )
        }
    }
}

@Composable
internal fun PortfolioConfirmedTradesTable(rows: List<PortfolioConfirmedTradeTableRow>) {
    PortfolioTradeOrdersGroupedTable(
        groups = rows.map { it.toTradeGroup() },
        caption = "Сделок: ${rows.size}. У каждой сделки (пары) — 2 строки ордеров. Прокрутка вправо — все столбцы."
    )
}

private data class StrategyTestTradeColumn(
    val key: StrategyTestTradesTableColumn,
    val title: String,
    val widthDp: Int,
)

@Composable
private fun StrategyTestTableColumnFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFF1565C0) else Color(0xFF424242),
            contentColor = Color.White,
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(label, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
internal fun StrategyTestTradesTable(
    tradeItems: List<StrategyTestTradeItem>,
    caption: String,
    maxRows: Int = 120,
    riskAssessments: List<StrategyTestTradeRiskAssessment> = emptyList(),
) {
    if (tradeItems.isEmpty()) return
    val context = LocalContext.current.applicationContext
    var visibleColumns by remember {
        mutableStateOf(loadStrategyTestTradesTableVisibleColumns(context))
    }
    fun updateVisibleColumns(next: Set<StrategyTestTradesTableColumn>) {
        visibleColumns = next
        saveStrategyTestTradesTableVisibleColumns(context, next)
    }
    val scroll = rememberScrollState()
    val filterScroll = rememberScrollState()
    if (caption.isNotBlank()) {
        Text(
            text = caption,
            color = Color(0xFF757575),
            fontSize = 9.sp,
            modifier = Modifier.padding(bottom = 4.dp),
            maxLines = 4
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Столбцы таблицы",
                color = Color(0xFF9E9E9E),
                fontSize = 10.sp,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(filterScroll),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                StrategyTestTableColumnFilterChip(
                    label = "Все",
                    selected = visibleColumns.size == StrategyTestTradesTableColumn.entries.size,
                ) {
                    updateVisibleColumns(StrategyTestTradesTableColumn.defaultVisible)
                }
                StrategyTestTradesTableColumn.entries.forEach { column ->
                    StrategyTestTableColumnFilterChip(
                        label = column.title,
                        selected = column in visibleColumns,
                    ) {
                        updateVisibleColumns(
                            if (column in visibleColumns) {
                                val next = visibleColumns - column
                                if (next.isEmpty()) visibleColumns else next
                            } else {
                                visibleColumns + column
                            },
                        )
                    }
                }
            }
        }
        val cols = StrategyTestTradesTableColumn.entries
            .filter { it in visibleColumns }
            .map { StrategyTestTradeColumn(key = it, title = it.title, widthDp = it.widthDp) }
        Column(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll)
                .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                .padding(vertical = 6.dp)
        ) {
            Row(Modifier.padding(horizontal = 1.dp, vertical = 1.dp)) {
                cols.forEach { col ->
                    PortfolioTradeTableCell(
                        text = col.title,
                        modifier = Modifier.widthIn(min = col.widthDp.dp).width(col.widthDp.dp),
                        color = Color(0xFF90A4AE)
                    )
                }
            }
            tradeItems.take(maxRows).forEachIndexed { index, item ->
                val t = item.trade
                val risk = riskAssessments.getOrNull(index)
                val rowBackground = if (risk != null && strategyTestTradeRiskIsFlagged(risk)) {
                    strategyTestTradeRiskRowColor(risk.level)
                } else {
                    Color(0xFF242424)
                }
                val dir = when (t.direction) {
                    ZStrategyPosition.Long -> "LONG"
                    ZStrategyPosition.Short -> "SHORT"
                    ZStrategyPosition.Flat -> "—"
                }
                val durationLabel = formatSimTradeDurationLabel(t.entryDate, t.exitDate)
                val durationColor = when (simTradeDurationTone(t.entryDate, t.exitDate)) {
                    SimTradeDurationTone.Short -> Color(0xFF81C784)
                    SimTradeDurationTone.Long -> Color(0xFFE57373)
                    SimTradeDurationTone.Neutral -> Color(0xFF9E9E9E)
                }
                val netColor = rubDeltaColor(t.pnlRubApprox)
                val grossColor = rubDeltaColor(t.grossPnlRubApprox)
                Row(
                    Modifier
                        .padding(horizontal = 1.dp, vertical = 1.dp)
                        .background(rowBackground, RoundedCornerShape(4.dp))
                ) {
                    cols.forEach { col ->
                        val (text, color) = when (col.key) {
                            StrategyTestTradesTableColumn.Index -> "${index + 1}" to Color(0xFFE0E0E0)
                            StrategyTestTradesTableColumn.Direction -> dir to when (t.direction) {
                                ZStrategyPosition.Long -> Color(0xFF81C784)
                                ZStrategyPosition.Short -> Color(0xFFFFAB91)
                                ZStrategyPosition.Flat -> Color(0xFFE0E0E0)
                            }
                            StrategyTestTradesTableColumn.Entry ->
                                compactPortfolioTableDateTimeTwoLines(t.entryDate) to Color(0xFFE0E0E0)
                            StrategyTestTradesTableColumn.Exit ->
                                compactPortfolioTableDateTimeTwoLines(t.exitDate) to Color(0xFFE0E0E0)
                            StrategyTestTradesTableColumn.Duration -> durationLabel to durationColor
                            StrategyTestTradesTableColumn.SpreadEntry ->
                                String.format(Locale.US, "%.2f", t.entrySpreadPercent) to Color(0xFF9E9E9E)
                            StrategyTestTradesTableColumn.SpreadExit ->
                                String.format(Locale.US, "%.2f", t.exitSpreadPercent) to Color(0xFF9E9E9E)
                            StrategyTestTradesTableColumn.SpreadDelta ->
                                String.format(Locale.US, "%+.2f", t.pnlSpreadPoints) to grossColor
                            StrategyTestTradesTableColumn.Gross ->
                                formatPortfolioTableRub(t.grossPnlRubApprox) to grossColor
                            StrategyTestTradesTableColumn.Commission ->
                                formatPortfolioTableCostRub(t.commissionRubApprox) to Color(0xFFFFAB91)
                            StrategyTestTradesTableColumn.Overnight ->
                                formatPortfolioTableCostRub(t.overnightRubApprox) to Color(0xFFFFAB91)
                            StrategyTestTradesTableColumn.Net ->
                                formatPortfolioTableRub(t.pnlRubApprox) to netColor
                            StrategyTestTradesTableColumn.Risk -> {
                                val assessment = risk ?: StrategyTestTradeRiskAssessment(
                                    flags = emptyList(),
                                    level = StrategyTestTradeRiskLevel.None,
                                    score = 0,
                                    entryZ = null,
                                )
                                formatStrategyTestTradeRiskFlags(assessment) to
                                    if (strategyTestTradeRiskIsFlagged(assessment)) {
                                        strategyTestTradeRiskLevelColor(assessment.level)
                                    } else {
                                        Color(0xFF757575)
                                    }
                            }
                        }
                        PortfolioTradeTableCell(
                            text = text,
                            modifier = Modifier.widthIn(min = col.widthDp.dp).width(col.widthDp.dp),
                            color = color
                        )
                    }
                }
            }
        }
    }
}

/** Портфель: сделки по журналу; вход в метриках — только при записи в реестре исполнений под выбранным режимом. */
@Composable
internal fun PortfolioTradesBucketHeader(
    bucket: PortfolioTradesBucketUi,
    modifier: Modifier = Modifier
) {
    val pnlColor = when {
        bucket.totalPnlRub > 0 -> Color(0xFF81C784)
        bucket.totalPnlRub < 0 -> Color(0xFFE57373)
        else -> Color(0xFFBDBDBD)
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (bucket.isOpenTrades) {
                if (bucket.tradeCount == 0) "Открытая · нет" else "Открытая сделка"
            } else {
                "${bucket.title} · ${bucket.tradeCount} сделок"
            },
            color = Color(0xFFE0E0E0),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = buildString {
                append("PnL ${formatRubSigned(bucket.totalPnlRub)}")
                bucket.pnlSourceLabel?.let { append(" · $it") }
            },
            color = pnlColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PortfolioOpenTradeDetailLine(
    label: String,
    value: String,
    valueColor: Color = Color(0xFFE0E0E0),
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color(0xFF9E9E9E),
            fontSize = 11.sp,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            color = valueColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun PortfolioOpenTradePnlForecastPanel(
    forecast: OpenTradePnlForecast,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF263238), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Прогноз PnL по Z (оценка)",
            color = Color(0xFF80DEEA),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = buildString {
                append("При μ≈")
                append(String.format(Locale.US, "%.2f", forecast.spreadMeanPercent))
                append("%, σ≈")
                append(formatOpenTradePnlForecastSigma(forecast.spreadSigmaPercent))
                append(" п.п., номинал ≈")
                append(String.format(Locale.US, "%.0f", forecast.notionalRub))
                append(" ₽")
                if (forecast.calibratedFromBroker) {
                    append(" · сценарии от факта Tinkoff (вход→сейчас)")
                } else {
                    append(" · оценка по Δспред MOEX")
                }
            },
            color = Color(0xFF90A4AE),
            fontSize = 9.sp,
            lineHeight = 11.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Сценарий", color = Color(0xFF78909C), fontSize = 9.sp)
            Text("Z → net ₽", color = Color(0xFF78909C), fontSize = 9.sp)
        }
        forecast.rows.forEach { row ->
            val labelColor = when {
                row.isBrokerFact -> Color(0xFF81C784)
                row.isExitLevel -> Color(0xFFFFCC80)
                row.isCurrentLevel -> Color(0xFF80CBC4)
                else -> Color(0xFFB0BEC5)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.label,
                        color = labelColor,
                        fontSize = 10.sp,
                        fontWeight = if (row.isExitLevel || row.isCurrentLevel) {
                            FontWeight.Medium
                        } else {
                            FontWeight.Normal
                        },
                    )
                    Text(
                        text = buildString {
                            append(formatPortfolioTableZ(row.zTarget))
                            append(" · ")
                            append(formatOpenTradePnlForecastSpreadDelta(row.spreadDeltaPts))
                        },
                        color = Color(0xFF607D8B),
                        fontSize = 9.sp,
                    )
                }
                Text(
                    text = formatPortfolioTableRub(row.netRubApprox),
                    color = rubDeltaColor(row.netRubApprox),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Text(
            text = "«Сейчас» = PnL счёта Tinkoff. Прочие строки — экстраполяция по ΔZ от входа; MOEX Z и цены fill могут расходиться.",
            color = Color(0xFF546E7A),
            fontSize = 8.sp,
            lineHeight = 10.sp,
        )
    }
}

@Composable
internal fun PortfolioOpenTradeDetailPanel(
    group: PortfolioTradeGroupRow,
    pnlSourceLabel: String?,
    risk: StrategyTestTradeRiskAssessment?,
    onCloseOpenTrade: ((tradeId: String) -> Unit)?,
    closingTradeId: String?,
    forecast: OpenTradePnlForecast? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val longOrder = group.orders.firstOrNull()
    val shortOrder = group.orders.getOrNull(1)
    val execFills = remember(group.tradeId) {
        TradeExecutionLog.fillsForTrade(context, group.tradeId)
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (risk != null && strategyTestTradeRiskIsFlagged(risk)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = strategyTestTradeRiskLevelColor(risk.level),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "Риск: ${formatPortfolioTradeRiskTotalScore(risk)}",
                    color = strategyTestTradeRiskLevelColor(risk.level),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        PortfolioOpenTradeDetailLine("ID", group.tradeDisplayId)
        if (group.entrySignalId != "—") {
            PortfolioOpenTradeDetailLine("Сигнал", group.entrySignalId)
        }
        if (group.entrySignalBarTimeMsk != "—") {
            PortfolioOpenTradeDetailLine("Бар сигнала", group.entrySignalBarTimeMsk)
        }
        if (group.entrySignalReceivedMsk != "—") {
            PortfolioOpenTradeDetailLine("Получен", group.entrySignalReceivedMsk)
        }
        PortfolioOpenTradeDetailLine("Вход", group.entryTimeMsk)
        PortfolioOpenTradeDetailLine("Z вход", formatPortfolioTableZ(group.entryZ))
        PortfolioOpenTradeDetailLine("Z сейчас", formatPortfolioTableZ(group.exitZ))
        if (!group.entrySpreadPercent.isNaN()) {
            PortfolioOpenTradeDetailLine(
                label = "Спрэд вход",
                value = formatPortfolioSpreadPercent(group.entrySpreadPercent),
            )
        }
        if (!group.currentSpreadPercent.isNaN()) {
            PortfolioOpenTradeDetailLine(
                label = "Спрэд сейчас",
                value = formatPortfolioSpreadPercent(group.currentSpreadPercent),
            )
        }
        if (!group.spreadMtmPoints.isNaN()) {
            PortfolioOpenTradeDetailLine(
                label = "Δ спред (PnL)",
                value = formatOpenTradePnlForecastSpreadDelta(group.spreadMtmPoints),
                valueColor = rubDeltaColor(group.spreadMtmPoints),
            )
        }
        PortfolioOpenTradeDetailLine(
            label = "Номинал",
            value = formatPortfolioOpenTradeNotional(group.volumeText, group.executionNotionalRub),
        )
        forecast?.let { fc ->
            PortfolioOpenTradePnlForecastPanel(forecast = fc)
        }
        PortfolioOpenTradeDetailLine("Направление", group.directionLabel)
        PortfolioOpenTradeDetailLine("Объём", group.volumeText)
        PortfolioOpenTradeDetailLine("Подтв.", group.confirmLabel)
        longOrder?.let { leg ->
            PortfolioOpenTradeDetailLine(
                label = "Long · ${leg.ticker}",
                value = leg.sideRu,
                valueColor = Color(0xFF81C784),
            )
        }
        shortOrder?.let { leg ->
            PortfolioOpenTradeDetailLine(
                label = "Short · ${leg.ticker}",
                value = leg.sideRu,
                valueColor = Color(0xFFFFAB91),
            )
        }
        PortfolioOpenTradeDetailLine(
            label = "PnL Long",
            value = formatPortfolioTableRub(group.legLongPnlSplitRubApprox),
            valueColor = if (group.legLongPnlSplitRubApprox.isNaN()) {
                Color(0xFF757575)
            } else {
                rubDeltaColor(group.legLongPnlSplitRubApprox)
            },
        )
        PortfolioOpenTradeDetailLine(
            label = "PnL Short",
            value = formatPortfolioTableRub(group.legShortPnlSplitRubApprox),
            valueColor = if (group.legShortPnlSplitRubApprox.isNaN()) {
                Color(0xFF757575)
            } else {
                rubDeltaColor(group.legShortPnlSplitRubApprox)
            },
        )
        val netLabel = buildString {
            append("PnL net")
            pnlSourceLabel?.let { append(" · $it") }
        }
        PortfolioOpenTradeDetailLine(
            label = netLabel,
            value = formatPortfolioTableRub(group.netPnlRubApprox),
            valueColor = if (group.netPnlRubApprox.isNaN()) {
                Color(0xFF757575)
            } else {
                rubDeltaColor(group.netPnlRubApprox)
            },
        )
        PortfolioOpenTradeDetailLine(
            label = "Комиссия",
            value = formatPortfolioTableCostRub(group.commissionRubApprox),
            valueColor = Color(0xFFFFAB91),
        )
        PortfolioOpenTradeDetailLine(
            label = "Overnight",
            value = formatPortfolioTableCostRub(group.overnightRubApprox),
            valueColor = Color(0xFFFFAB91),
        )
        if (execFills.isNotEmpty()) {
            Text(
                text = execFills.joinToString("\n") { fill ->
                    "${fill.phase.name} ${fill.ticker}: ${TradeExecutionLog.formatLegFillSummary(fill)}"
                },
                color = Color(0xFF80CBC4),
                fontSize = 10.sp,
                lineHeight = 12.sp,
            )
        }
        if (onCloseOpenTrade != null) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = { onCloseOpenTrade(group.tradeId) },
                enabled = closingTradeId != group.tradeId,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFFFAB91),
                ),
            ) {
                Text(
                    text = if (closingTradeId == group.tradeId) "Закрытие…" else "Закрыть сделку",
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
internal fun PortfolioTradesWindowSection(
    openExecutions: List<SandboxSpreadExecUi>,
    closedRows: List<PortfolioConfirmedTradeTableRow>,
    lookbackDays: Long,
    realTradeEntryThreshold: Double,
    realTradeExitThreshold: Double,
    m15Points: List<DataPoint>,
    leverage: Double,
    commissionPercentPerSide: Double,
    executionMode: TinkoffExecutionMode,
    modifier: Modifier = Modifier,
    onCloseOpenTrade: ((tradeId: String) -> Unit)? = null,
    closingTradeId: String? = null,
    brokerClosedPnlSummary: ProdSpreadWindowPnlSummary? = null,
) {
    var closedSourceFilter by remember { mutableStateOf(PortfolioClosedTradesSourceFilter.All) }
    val depthLabel = portfolioLookbackPeriodLabel(lookbackDays)
    val closedPnlOverride = when (closedSourceFilter) {
        PortfolioClosedTradesSourceFilter.All -> brokerClosedPnlSummary?.netRub
        else -> null
    }
    val closedCountOverride = when (closedSourceFilter) {
        PortfolioClosedTradesSourceFilter.All -> brokerClosedPnlSummary?.roundTripCount
        else -> null
    }
    val closedSourceLabel = when (closedSourceFilter) {
        PortfolioClosedTradesSourceFilter.Broker,
        PortfolioClosedTradesSourceFilter.All,
        -> brokerClosedPnlSummary?.let { prodSpreadPnlSourceLabel(it.source) }
        PortfolioClosedTradesSourceFilter.TestOnly -> null
    }
    val openSourceLabel = openPnlBrokerSourceLabel(openExecutions)
    val (openBucket, closedBucket) = buildPortfolioTradesBuckets(
        openExecutions = openExecutions,
        closedRows = closedRows,
        lookbackDays = lookbackDays,
        closedSourceFilter = closedSourceFilter,
        closedPnlOverrideRub = closedPnlOverride,
        closedPnlSourceLabel = closedSourceLabel,
        closedTradeCountOverride = closedCountOverride,
        openPnlSourceLabel = openSourceLabel,
        m15Points = m15Points,
    )
    val openForecast = remember(
        openExecutions,
        m15Points,
        realTradeEntryThreshold,
        realTradeExitThreshold,
        leverage,
        commissionPercentPerSide,
        executionMode,
    ) {
        openExecutions.firstOrNull()?.let { exec ->
            buildOpenTradePnlForecast(
                exec = exec,
                points = m15Points,
                entryThreshold = realTradeEntryThreshold,
                exitThreshold = realTradeExitThreshold,
                leverage = leverage,
                commissionPercentPerSide = commissionPercentPerSide,
                executionMode = executionMode,
            )
        }
    }
    val openRiskAssessments = remember(openBucket.groups, realTradeEntryThreshold) {
        buildPortfolioTradeGroupRiskAssessments(openBucket.groups, realTradeEntryThreshold)
    }
    val closedRiskAssessments = remember(closedBucket.groups, realTradeEntryThreshold) {
        buildPortfolioTradeGroupRiskAssessments(closedBucket.groups, realTradeEntryThreshold)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Сделки · $depthLabel (МСК)",
            color = Color(0xFFE0E0E0),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A237E).copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PortfolioTradesBucketHeader(openBucket)
            if (openBucket.groups.isEmpty()) {
                Text(
                    text = "Нет открытой сделки.",
                    color = Color(0xFF9E9E9E),
                    fontSize = 11.sp
                )
            } else {
                PortfolioOpenTradeDetailPanel(
                    group = openBucket.groups.single(),
                    pnlSourceLabel = openBucket.pnlSourceLabel,
                    risk = openRiskAssessments.firstOrNull(),
                    onCloseOpenTrade = onCloseOpenTrade,
                    closingTradeId = closingTradeId,
                    forecast = openForecast,
                )
            }
        }
        PortfolioCollapsibleSection(
            title = "Закрытые сделки",
            subtitle = buildString {
                append("PnL ${formatRubSigned(closedBucket.totalPnlRub)}")
                closedBucket.pnlSourceLabel?.let { append(" · $it") }
                append(" · ${closedBucket.tradeCount} шт.")
            },
            defaultExpanded = false,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PortfolioTradesSourceFilterChip(
                    label = "Всё",
                    selected = closedSourceFilter == PortfolioClosedTradesSourceFilter.All,
                    onClick = { closedSourceFilter = PortfolioClosedTradesSourceFilter.All },
                )
                PortfolioTradesSourceFilterChip(
                    label = "Брокер",
                    selected = closedSourceFilter == PortfolioClosedTradesSourceFilter.Broker,
                    onClick = { closedSourceFilter = PortfolioClosedTradesSourceFilter.Broker },
                )
                PortfolioTradesSourceFilterChip(
                    label = "только тест",
                    selected = closedSourceFilter == PortfolioClosedTradesSourceFilter.TestOnly,
                    onClick = { closedSourceFilter = PortfolioClosedTradesSourceFilter.TestOnly },
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF263238), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (closedBucket.groups.isEmpty()) {
                    Text(
                        text = when (closedSourceFilter) {
                            PortfolioClosedTradesSourceFilter.Broker ->
                                "Нет сделок брокера за $depthLabel."
                            PortfolioClosedTradesSourceFilter.TestOnly ->
                                "Нет тестовых сделок за $depthLabel."
                            PortfolioClosedTradesSourceFilter.All ->
                                "Нет закрытых сделок за $depthLabel."
                        },
                        color = Color(0xFF9E9E9E),
                        fontSize = 11.sp,
                    )
                } else {
                    PortfolioTradeOrdersGroupedTable(
                        groups = closedBucket.groups,
                        caption = "",
                        riskAssessments = closedRiskAssessments,
                        showRiskScoreBreakdown = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun PortfolioTradesSourceFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFF1565C0) else Color(0xFF424242),
            contentColor = Color.White,
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, fontSize = 11.sp)
    }
}

internal fun strategyTestTradeRiskRowColor(level: StrategyTestTradeRiskLevel): Color = when (level) {
    StrategyTestTradeRiskLevel.None -> Color(0xFF242424)
    StrategyTestTradeRiskLevel.Elevated -> Color(0xFF2A2418)
    StrategyTestTradeRiskLevel.High -> Color(0xFF352020)
    StrategyTestTradeRiskLevel.Critical -> Color(0xFF4A1818)
}

internal fun strategyTestTradeRiskLevelColor(level: StrategyTestTradeRiskLevel): Color = when (level) {
    StrategyTestTradeRiskLevel.None -> Color(0xFF757575)
    StrategyTestTradeRiskLevel.Elevated -> Color(0xFFFFCC80)
    StrategyTestTradeRiskLevel.High -> Color(0xFFFFAB91)
    StrategyTestTradeRiskLevel.Critical -> Color(0xFFE57373)
}
