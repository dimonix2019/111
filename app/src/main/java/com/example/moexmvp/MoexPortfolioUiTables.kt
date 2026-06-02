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

@Composable
internal fun PortfolioTradeTableCell(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFE0E0E0)
) {
    Text(
        text = text,
        color = color,
        fontSize = 9.sp,
        maxLines = 4,
        lineHeight = 11.sp,
        modifier = modifier.padding(horizontal = 3.dp, vertical = 2.dp)
    )
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
    Text(
        text = caption,
        color = Color(0xFF757575),
        fontSize = 9.sp,
        modifier = Modifier.padding(bottom = 4.dp),
        maxLines = 4
    )
    Column(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
            .padding(vertical = 6.dp)
    ) {
        Row(Modifier.padding(horizontal = 2.dp, vertical = 2.dp)) {
            val heads = listOf(
                "ID сделки" to 52.dp,
                "ID сигн." to 72.dp,
                "Бар сигн." to 100.dp,
                "Получен" to 96.dp,
                "Вход" to 100.dp,
                "Выход" to 100.dp,
                "Объём" to 48.dp,
                "Подтв." to 52.dp,
                "Zвх" to 36.dp,
                exitZColumnTitle to 36.dp,
                "Push" to 88.dp,
                "PnL L" to 52.dp,
                "PnL S" to 52.dp,
                "Чистый" to 58.dp,
                "Комис." to 52.dp,
                "Оверн." to 52.dp,
                "№" to 24.dp,
                "Нога" to 36.dp,
                "Тикер" to 40.dp,
                "Сторона" to 72.dp,
                "Заявка" to 120.dp
            )
            heads.forEach { (title, w) ->
                PortfolioTradeTableCell(
                    text = title,
                    modifier = Modifier.widthIn(min = w).width(w),
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
                            text = "${group.tradeId} · ${group.directionLabel} · ${group.entryTimeMsk}",
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
                    Row(Modifier.padding(horizontal = 2.dp, vertical = 1.dp)) {
                        if (isFirstOrder) {
                            PortfolioTradeTableCell(group.tradeId, Modifier.widthIn(min = 52.dp).width(52.dp))
                            PortfolioTradeTableCell(
                                group.entrySignalId,
                                Modifier.widthIn(min = 72.dp).width(72.dp),
                                Color(0xFFCE93D8)
                            )
                            PortfolioTradeTableCell(
                                group.entrySignalBarTimeMsk,
                                Modifier.widthIn(min = 100.dp).width(100.dp),
                                Color(0xFFCE93D8)
                            )
                            PortfolioTradeTableCell(
                                group.entrySignalReceivedMsk,
                                Modifier.widthIn(min = 96.dp).width(96.dp),
                                Color(0xFF81D4FA)
                            )
                            PortfolioTradeTableCell(group.entryTimeMsk, Modifier.widthIn(min = 100.dp).width(100.dp))
                            PortfolioTradeTableCell(group.exitTimeMsk, Modifier.widthIn(min = 100.dp).width(100.dp))
                            PortfolioTradeTableCell(group.volumeText, Modifier.widthIn(min = 48.dp).width(48.dp))
                            PortfolioTradeTableCell(group.confirmLabel, Modifier.widthIn(min = 52.dp).width(52.dp))
                            PortfolioTradeTableCell(
                                formatPortfolioTableZ(group.entryZ),
                                Modifier.widthIn(min = 36.dp).width(36.dp)
                            )
                            PortfolioTradeTableCell(
                                formatPortfolioTableZ(group.exitZ),
                                Modifier.widthIn(min = 36.dp).width(36.dp)
                            )
                            PortfolioTradeTableCell(
                                group.notificationIdsText,
                                Modifier.widthIn(min = 88.dp).width(88.dp),
                                Color(0xFFB3E5FC)
                            )
                            PortfolioTradeTableCell(
                                formatPortfolioTableRub(group.legLongPnlSplitRubApprox),
                                Modifier.widthIn(min = 52.dp).width(52.dp),
                                if (group.legLongPnlSplitRubApprox.isNaN()) Color(0xFF757575)
                                else rubDeltaColor(group.legLongPnlSplitRubApprox)
                            )
                            PortfolioTradeTableCell(
                                formatPortfolioTableRub(group.legShortPnlSplitRubApprox),
                                Modifier.widthIn(min = 52.dp).width(52.dp),
                                if (group.legShortPnlSplitRubApprox.isNaN()) Color(0xFF757575)
                                else rubDeltaColor(group.legShortPnlSplitRubApprox)
                            )
                            PortfolioTradeTableCell(
                                formatPortfolioTableRub(group.netPnlRubApprox),
                                Modifier.widthIn(min = 58.dp).width(58.dp),
                                if (group.netPnlRubApprox.isNaN()) Color(0xFF757575)
                                else rubDeltaColor(group.netPnlRubApprox)
                            )
                            PortfolioTradeTableCell(
                                formatPortfolioTableCostRub(group.commissionRubApprox),
                                Modifier.widthIn(min = 52.dp).width(52.dp),
                                Color(0xFFFFAB91)
                            )
                            PortfolioTradeTableCell(
                                formatPortfolioTableCostRub(group.overnightRubApprox),
                                Modifier.widthIn(min = 52.dp).width(52.dp),
                                Color(0xFFFFAB91)
                            )
                        } else {
                            listOf(52, 72, 100, 96, 100, 100, 48, 52, 36, 36, 88, 52, 52, 58, 52, 52).forEach { w ->
                                PortfolioTradeTableCell(
                                    "·",
                                    Modifier.widthIn(min = w.dp).width(w.dp),
                                    Color(0xFF424242)
                                )
                            }
                        }
                        PortfolioTradeTableCell(
                            "${order.orderIndex}",
                            Modifier.widthIn(min = 24.dp).width(24.dp),
                            Color(0xFFCE93D8)
                        )
                        PortfolioTradeTableCell(order.legRole, Modifier.widthIn(min = 36.dp).width(36.dp))
                        PortfolioTradeTableCell(order.ticker, Modifier.widthIn(min = 40.dp).width(40.dp), tickerColor)
                        PortfolioTradeTableCell(order.sideRu, Modifier.widthIn(min = 72.dp).width(72.dp))
                        PortfolioTradeTableCell(order.orderBrief, Modifier.widthIn(min = 120.dp).width(120.dp))
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
    modifier: Modifier = Modifier,
    onCloseOpenTrade: ((tradeId: String) -> Unit)? = null,
    closingTradeId: String? = null,
) {
    val (openBucket, closedBucket) = buildPortfolioTradesBuckets(openExecutions, closedRows)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Сделки за ${PORTFOLIO_TRADES_WINDOW_DAYS} дня (МСК)",
            color = Color(0xFFE0E0E0),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = "Открытые — исполнение на демо; закрытые — журнал вход/выход + демо (не симуляция «Тест страт.»). " +
                "ID сигн. / бар / «Получен» — вход из журнала сигналов (сверка с авто-заявками). " +
                "Если сигнал в журнале есть, а сделки нет — проверьте «Исполнять на демо» и реестр входа (авто). " +
                "Окно: ${PORTFOLIO_TRADES_WINDOW_DAYS} дн. (МСК).",
            color = Color(0xFF9E9E9E),
            fontSize = 10.sp,
            maxLines = 3
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
                    text = "Нет открытых сделок за ${PORTFOLIO_TRADES_WINDOW_DAYS} дня.",
                    color = Color(0xFF9E9E9E),
                    fontSize = 11.sp
                )
            } else {
                PortfolioTradeOrdersGroupedTable(
                    groups = openBucket.groups,
                    caption = "Ордера сгруппированы по сделкам (2 строки). PnL открытых — оценка по Δ спрэда 15м.",
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
                    text = "Нет закрытых сделок за ${PORTFOLIO_TRADES_WINDOW_DAYS} дня.",
                    color = Color(0xFF9E9E9E),
                    fontSize = 11.sp
                )
            } else {
                PortfolioTradeOrdersGroupedTable(
                    groups = closedBucket.groups,
                    caption = "Закрытые сделки: реализованный PnL по паре вход→выход (журнал + демо)."
                )
            }
        }
    }
}
