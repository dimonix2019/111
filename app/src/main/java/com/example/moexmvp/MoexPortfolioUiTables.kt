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
internal fun PortfolioTradeOrdersGroupedTable(
    groups: List<PortfolioTradeGroupRow>,
    caption: String,
    exitZColumnTitle: String = "Zвых",
    onCloseOpenTrade: ((tradeId: String) -> Unit)? = null,
    closingTradeId: String? = null,
) {
    if (groups.isEmpty()) return
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
        val groupCols = listOf(
            PortfolioTradeGroupColumn("ID", 40),
            PortfolioTradeGroupColumn("Сигн.", 56),
            PortfolioTradeGroupColumn("Бар", 62),
            PortfolioTradeGroupColumn("Получ.", 58),
            PortfolioTradeGroupColumn("Вход", 62),
            PortfolioTradeGroupColumn("Выход", 62),
            PortfolioTradeGroupColumn("Подтв.", 40),
            PortfolioTradeGroupColumn("Zвх", 30),
            PortfolioTradeGroupColumn(exitZColumnTitle, 30),
            PortfolioTradeGroupColumn("PnL L", 44),
            PortfolioTradeGroupColumn("PnL S", 44),
            PortfolioTradeGroupColumn("Чист.", 48),
            PortfolioTradeGroupColumn("Ком.", 42),
            PortfolioTradeGroupColumn("Овн.", 42),
        )
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
        groups.take(40).forEach { group ->
            Column(
                Modifier
                    .padding(vertical = 2.dp)
                    .background(Color(0xFF242424), RoundedCornerShape(4.dp))
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
                            text = "${group.tradeDisplayId} · ${compactPortfolioTableDateLabel(group.entryTimeMsk)}",
                            color = Color(0xFFB3E5FC),
                            fontSize = 10.sp,
                            maxLines = 2,
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
                            val cells = listOf(
                                group.tradeDisplayId to Color(0xFFE0E0E0),
                                compactPortfolioTableDateLabel(group.entrySignalId) to Color(0xFFCE93D8),
                                compactPortfolioTableDateLabel(group.entrySignalBarTimeMsk) to Color(0xFFCE93D8),
                                compactPortfolioTableDateLabel(group.entrySignalReceivedMsk) to Color(0xFF81D4FA),
                                compactPortfolioTableDateLabel(group.entryTimeMsk) to Color(0xFFE0E0E0),
                                compactPortfolioTableDateLabel(group.exitTimeMsk) to Color(0xFFE0E0E0),
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
                            )
                            cells.forEachIndexed { index, (text, color) ->
                                val w = groupWidths[index]
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
            text = "${bucket.title} · ${bucket.tradeCount} сделок",
            color = Color(0xFFE0E0E0),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "PnL ${formatRubSigned(bucket.totalPnlRub)}",
            color = pnlColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
internal fun PortfolioTradesWindowSection(
    openExecutions: List<SandboxSpreadExecUi>,
    closedRows: List<PortfolioConfirmedTradeTableRow>,
    lookbackDays: Long,
    modifier: Modifier = Modifier,
    onCloseOpenTrade: ((tradeId: String) -> Unit)? = null,
    closingTradeId: String? = null,
) {
    var tradesAutoOnlyFilter by remember { mutableStateOf(false) }
    val depthLabel = portfolioLookbackPeriodLabel(lookbackDays)
    val (openBucket, closedBucket) = buildPortfolioTradesBuckets(
        openExecutions = openExecutions,
        closedRows = closedRows,
        lookbackDays = lookbackDays,
        tradesAutoOnlyFilter = tradesAutoOnlyFilter,
    )
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PortfolioTradesSourceFilterChip(
                label = "Всё",
                selected = !tradesAutoOnlyFilter,
                onClick = { tradesAutoOnlyFilter = false },
            )
            PortfolioTradesSourceFilterChip(
                label = "только Авто",
                selected = tradesAutoOnlyFilter,
                onClick = { tradesAutoOnlyFilter = true },
            )
        }
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
                    text = "Нет открытых сделок за $depthLabel.",
                    color = Color(0xFF9E9E9E),
                    fontSize = 11.sp
                )
            } else {
                PortfolioTradeOrdersGroupedTable(
                    groups = openBucket.groups,
                    caption = "",
                    exitZColumnTitle = "Z сейч.",
                    onCloseOpenTrade = onCloseOpenTrade,
                    closingTradeId = closingTradeId,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF263238), RoundedCornerShape(8.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PortfolioTradesBucketHeader(closedBucket)
            if (closedBucket.groups.isEmpty()) {
                Text(
                    text = "Нет закрытых сделок за $depthLabel.",
                    color = Color(0xFF9E9E9E),
                    fontSize = 11.sp
                )
            } else {
                PortfolioTradeOrdersGroupedTable(
                    groups = closedBucket.groups,
                    caption = "",
                )
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
