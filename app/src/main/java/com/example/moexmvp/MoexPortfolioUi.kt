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

@Composable
internal fun MainTabSelector(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        MainTab.entries.forEach { tab ->
            val isSel = tab == selected
            val icon = when (tab) {
                MainTab.Markets -> Icons.AutoMirrored.Filled.ShowChart
                MainTab.Portfolio -> Icons.Filled.Savings
                MainTab.StrategyTest -> Icons.Filled.AutoGraph
                MainTab.Journal -> Icons.AutoMirrored.Filled.FormatListBulleted
                MainTab.Sandbox -> Icons.Filled.AccountBalance
                MainTab.About -> Icons.Filled.Info
            }
            Button(
                onClick = { onSelect(tab) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSel) Color(0xFF1565C0) else Color(0xFF424242),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        tab.label,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun PortfolioPresetSection(
    presets: List<PortfolioPreset>,
    onApply: (PortfolioPreset) -> Unit,
    onDelete: (String) -> Unit,
    onSave: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Пресеты",
            color = Color.White,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            presets.forEach { p ->
                OutlinedButton(
                    onClick = { onApply(p) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(p.name, fontSize = 10.sp, maxLines = 1, color = Color(0xFFB3E5FC))
                }
                TextButton(onClick = { onDelete(p.id) }, contentPadding = PaddingValues(4.dp)) {
                    Text("×", color = Color(0xFFEF9A9A), fontSize = 16.sp)
                }
            }
            OutlinedButton(
                onClick = {
                    name = ""
                    showDialog = true
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("+ сохранить", fontSize = 10.sp, color = Color(0xFFE0E0E0))
            }
        }
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Имя пресета", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Название") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSave(name)
                        showDialog = false
                    }
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Отмена")
                }
            },
            containerColor = Color(0xFF263238)
        )
    }
}

@Composable
private fun PortfolioDataRefreshHeader(
    title: String,
    portfolioLoading: Boolean,
    onRefresh: () -> Unit,
    onMoex15mFullReload: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (portfolioLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Color(0xFF64B5F6),
                    strokeWidth = 2.dp
                )
            }
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Обновить", fontSize = 11.sp)
                }
            }
            if (onMoex15mFullReload != null) {
                OutlinedButton(
                    onClick = onMoex15mFullReload,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFB3E5FC))
                        Spacer(Modifier.width(4.dp))
                        Text("MOEX заново", fontSize = 10.sp, color = Color(0xFFB3E5FC))
                    }
                }
            }
        }
    }
}

@Composable
private fun PortfolioCollapsibleSection(
    title: String,
    subtitle: String? = null,
    defaultExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .background(Color(0xFF2C2C2C), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color(0xFFE0E0E0),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                subtitle?.let {
                    Text(
                        text = it,
                        color = Color(0xFF757575),
                        fontSize = 10.sp,
                        maxLines = 2
                    )
                }
            }
            Text(
                text = if (expanded) "▼" else "▶",
                color = Color(0xFF9E9E9E),
                fontSize = 12.sp
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun PortfolioCompactHeroInline(metrics: PortfolioMetrics) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val pnlColor = when {
            metrics.totalPnlRubApprox > 0 -> Color(0xFF81C784)
            metrics.totalPnlRubApprox < 0 -> Color(0xFFE57373)
            else -> Color(0xFFBDBDBD)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text("Итого PnL", color = Color(0xFF90A4AE), fontSize = 10.sp)
            Text(
                text = "${formatRubSigned(metrics.totalPnlRubApprox)}  ${formatPercentSigned(metrics.totalReturnPercent)}",
                color = pnlColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text("Макс. просадка", color = Color(0xFF90A4AE), fontSize = 10.sp)
            Text(
                text = "${formatRubSigned(-metrics.maxDrawdownRubApprox)}  ${String.format(Locale.US, "%.1f%%", metrics.maxDrawdownPercent)}",
                color = Color(0xFFFFB74D),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

private fun formatPortfolioTableZ(z: Double): String =
    if (z.isNaN()) "—" else String.format(Locale.US, "%.2f", z)

private fun formatPortfolioTableRub(value: Double): String =
    if (value.isNaN()) "—" else formatRubSigned(value)

@Composable
private fun PortfolioTradeTableCell(
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
private fun PortfolioTradeOrdersGroupedTable(
    groups: List<PortfolioTradeGroupRow>,
    caption: String,
    exitZColumnTitle: String = "Zвых"
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
                "Тип" to 36.dp,
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
                            PortfolioTradeTableCell(group.directionLabel, Modifier.widthIn(min = 36.dp).width(36.dp))
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
                        } else {
                            listOf(52, 36, 100, 100, 48, 52, 36, 36, 88, 52, 52, 58).forEach { w ->
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
private fun PortfolioConfirmedTradesTable(rows: List<PortfolioConfirmedTradeTableRow>) {
    PortfolioTradeOrdersGroupedTable(
        groups = rows.map { it.toTradeGroup() },
        caption = "Сделок: ${rows.size}. У каждой сделки (пары) — 2 строки ордеров. Прокрутка вправо — все столбцы."
    )
}

/** Портфель: сделки по журналу; вход в метриках — только при записи в реестре исполнений под выбранным режимом. */
@Composable
internal fun PortfolioSandboxOrdersSection(
    executions: List<SandboxSpreadExecUi>,
    modifier: Modifier = Modifier
) {
    val openGroups = executions.asReversed().map { it.toTradeGroup() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1A237E).copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Сделки на демо — открытые (${openGroups.size})",
            color = Color(0xFFBBDEFB),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (openGroups.isEmpty()) {
            Text(
                text = "Пока нет сделок на демо. Тестовый сигнал / пара или «Принять» при включённом исполнении.",
                color = Color(0xFF9E9E9E),
                fontSize = 11.sp
            )
        } else {
            PortfolioTradeOrdersGroupedTable(
                groups = openGroups,
                caption = "Ордера по сделкам (2 строки на пару). Вход — время исполнения на демо; Zвх — на входе; Z сейч. и PnL — оценка по последнему 15м бару (обновите портфель).",
                exitZColumnTitle = "Z сейч."
            )
        }
    }
}

@Composable
internal fun ConfirmedPortfolioTabContent(
    metrics: PortfolioMetrics?,
    confirmedTradeTableRows: List<PortfolioConfirmedTradeTableRow>,
    sandboxSpreadExecutions: List<SandboxSpreadExecUi>,
    portfolioLoading: Boolean,
    portfolioError: String?,
    onRefresh: () -> Unit,
    leverage: Double,
    commissionPercentPerSide: Double,
    onLeverageChange: (Double) -> Unit,
    onCommissionChange: (Double) -> Unit,
    realTradeEntryThreshold: Double,
    realTradeExitThreshold: Double,
    onRealTradeEntryChange: (Double) -> Unit,
    onRealTradeExitChange: (Double) -> Unit,
    portfolioLedgerIncludeAuto: Boolean,
    onPortfolioLedgerIncludeAutoChange: (Boolean) -> Unit,
    executeSignalsOnSandbox: Boolean,
    onExecuteSignalsOnSandboxChange: (Boolean) -> Unit,
    sandboxSpreadAutoExecute: Boolean,
    onSandboxSpreadAutoExecuteChange: (Boolean) -> Unit,
    portfolioTestBusy: Boolean,
    onTestSignalLong: () -> Unit,
    onTestSignalShort: () -> Unit,
    onTestSpreadPairClick: () -> Unit,
    closeAllPortfolioBusy: Boolean,
    onCloseAllTradesClick: () -> Unit,
    dailyReconciliation: DailyPortfolioReconciliation? = null
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        PortfolioDataRefreshHeader(
            title = "Портфель · демо-счёт",
            portfolioLoading = portfolioLoading,
            onRefresh = onRefresh,
            onMoex15mFullReload = null
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onCloseAllTradesClick,
                enabled = !closeAllPortfolioBusy,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFAB91))
            ) {
                Text(
                    if (closeAllPortfolioBusy) "Закрытие…" else "Закрыть все сделки",
                    fontSize = 12.sp
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x33F48FB1), RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Исполнять вход по сигналу на демо-счёт (покупка 1 лота + продажа 1 лота по спрэду TATN/TATNP)",
                    color = Color(0xFFFCE4EC),
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                Switch(
                    checked = executeSignalsOnSandbox,
                    onCheckedChange = onExecuteSignalsOnSandboxChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFFF48FB1),
                        checkedTrackColor = Color(0xFF880E4F),
                        uncheckedThumbColor = Color(0xFFB0BEC5),
                        uncheckedTrackColor = Color(0xFF455A64)
                    )
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Сразу отправлять 2 заявки на демо без карточки «Принять»",
                    color = Color(0xFFFCE4EC),
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                Switch(
                    checked = sandboxSpreadAutoExecute,
                    onCheckedChange = onSandboxSpreadAutoExecuteChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFFF48FB1),
                        checkedTrackColor = Color(0xFF880E4F),
                        uncheckedThumbColor = Color(0xFFB0BEC5),
                        uncheckedTrackColor = Color(0xFF455A64)
                    )
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ручное «Принять»",
                    color = if (!portfolioLedgerIncludeAuto) Color(0xFFF8BBD0) else Color(0xFF9E9E9E),
                    fontSize = 11.sp,
                    fontWeight = if (!portfolioLedgerIncludeAuto) FontWeight.SemiBold else FontWeight.Normal
                )
                Switch(
                    checked = portfolioLedgerIncludeAuto,
                    onCheckedChange = onPortfolioLedgerIncludeAutoChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFFF48FB1),
                        checkedTrackColor = Color(0xFF880E4F),
                        uncheckedThumbColor = Color(0xFFB0BEC5),
                        uncheckedTrackColor = Color(0xFF455A64)
                    )
                )
                Text(
                    text = "Авто демо",
                    color = if (portfolioLedgerIncludeAuto) Color(0xFFF8BBD0) else Color(0xFF9E9E9E),
                    fontSize = 11.sp,
                    fontWeight = if (portfolioLedgerIncludeAuto) FontWeight.SemiBold else FontWeight.Normal
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                ParamStepper(
                    title = "Вход |Z|",
                    valueLabel = String.format(Locale.US, "%.2f", realTradeEntryThreshold),
                    onMinus = {
                        onRealTradeEntryChange(
                            (realTradeEntryThreshold - PORTFOLIO_Z_THRESHOLD_STEP).coerceAtLeast(PORTFOLIO_Z_THRESHOLD_MIN)
                        )
                    },
                    onPlus = {
                        onRealTradeEntryChange(
                            (realTradeEntryThreshold + PORTFOLIO_Z_THRESHOLD_STEP).coerceAtMost(PORTFOLIO_Z_THRESHOLD_MAX)
                        )
                    },
                    modifier = Modifier.weight(1f),
                    containerColor = Color(0x22FFFFFF),
                    titleColor = Color(0xFFFCE4EC),
                    valueTextColor = Color(0xFFFFF8F9)
                )
                ParamStepper(
                    title = "Выход |Z|",
                    valueLabel = String.format(Locale.US, "%.2f", realTradeExitThreshold),
                    onMinus = {
                        onRealTradeExitChange(
                            (realTradeExitThreshold - PORTFOLIO_Z_THRESHOLD_STEP).coerceAtLeast(PORTFOLIO_Z_THRESHOLD_MIN)
                        )
                    },
                    onPlus = {
                        onRealTradeExitChange(
                            (realTradeExitThreshold + PORTFOLIO_Z_THRESHOLD_STEP).coerceAtMost(PORTFOLIO_Z_THRESHOLD_MAX)
                        )
                    },
                    modifier = Modifier.weight(1f),
                    containerColor = Color(0x22FFFFFF),
                    titleColor = Color(0xFFFCE4EC),
                    valueTextColor = Color(0xFFFFF8F9)
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onTestSignalLong,
                    enabled = !portfolioTestBusy,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF8BBD0))
                ) {
                    Text("Тестовый сигнал на вход LONG", fontSize = 11.sp, maxLines = 2)
                }
                OutlinedButton(
                    onClick = onTestSignalShort,
                    enabled = !portfolioTestBusy,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF8BBD0))
                ) {
                    Text("Тестовый сигнал на вход SHORT", fontSize = 11.sp, maxLines = 2)
                }
                OutlinedButton(
                    onClick = onTestSpreadPairClick,
                    enabled = !portfolioTestBusy,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB2DFDB))
                ) {
                    Text("Тестовая пара (нога 1 + нога 2)", fontSize = 11.sp, maxLines = 2)
                }
                if (portfolioTestBusy) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color(0xFFF48FB1),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Выполняется…", color = Color(0xFFCE93D8), fontSize = 10.sp)
                    }
                }
            }
        }
        PortfolioSandboxOrdersSection(executions = sandboxSpreadExecutions)
        Text(
            text = "${"%.0f".format(Locale.US, metrics?.notionalRub ?: DEFAULT_PORTFOLIO_NOTIONAL_RUB)} ₽ · x${String.format(Locale.US, "%.1f", leverage)} · ${String.format(Locale.US, "%.3f", commissionPercentPerSide)}% / сторона · оценка по спрэду 15м",
            color = Color(0xFF9E9E9E),
            fontSize = 10.sp,
            maxLines = 3
        )
        PortfolioCollapsibleSection(
            title = "Плечо, комиссия, пояснения",
            defaultExpanded = false
        ) {
            PortfolioParamsControls(
                leverage = leverage,
                commissionPercentPerSide = commissionPercentPerSide,
                entryThreshold = 0.0,
                exitThreshold = 0.0,
                showZThresholdSteppers = false,
                onLeverageChange = onLeverageChange,
                onCommissionChange = onCommissionChange,
                onEntryThresholdChange = {},
                onExitThresholdChange = {}
            )
            Text(
                text = "Метрики ниже — по парам вход→выход из журнала; начало цикла считается только если вход есть в журнале исполнений под выбранным выше режимом (ручное «Принять» или авто журнал демо).",
                color = Color(0xFF757575),
                fontSize = 10.sp
            )
        }

        if (portfolioError != null) {
            Text(portfolioError, color = Color(0xFFEF9A9A), fontSize = 11.sp)
            Button(onClick = onRefresh, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Повторить", fontSize = 11.sp)
                }
            }
        } else if (!portfolioLoading && metrics == null) {
            Text("Нет сделок по данным портфеля (или нет 15м данных).", color = Color(0xFFBDBDBD), fontSize = 11.sp)
        } else {
            metrics?.let { m ->
                PortfolioCompactHeroInline(m)
                PortfolioCollapsibleSection(
                    title = "Все показатели (сводка и сетка)",
                    subtitle = "${formatRubSigned(m.totalPnlRubApprox)} · просадка ${formatRubSigned(-m.maxDrawdownRubApprox)}",
                    defaultExpanded = false
                ) {
                    Text(
                        text = "Итого = реализованный PnL + нереализованная нога (если позиция ещё открыта по журналу).",
                        color = Color(0xFF616161),
                        fontSize = 9.sp,
                        maxLines = 3
                    )
                    PortfolioHeroMetricsRow(metrics = m)
                    Text(
                        text = m.periodDescription,
                        color = Color(0xFF757575),
                        fontSize = 10.sp
                    )
                    PortfolioMetricGrid(m, showHeroDuplicate = false)
                }
                Text(
                    text = "Закрытые сделки (${confirmedTradeTableRows.size})",
                    color = Color(0xFFE0E0E0),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (confirmedTradeTableRows.isEmpty()) {
                    Text("Закрытых сделок за период нет.", color = Color(0xFF9E9E9E), fontSize = 11.sp)
                } else {
                    PortfolioConfirmedTradesTable(confirmedTradeTableRows)
                }
            }
        }
        dailyReconciliation?.let { rec ->
            DailyReconciliationSection(rec)
        }
    }
}

/** Симуляция по порогам |Z| на 15-мин данных. */
@Composable
internal fun StrategyTestTabContent(
    metrics: PortfolioMetrics?,
    portfolioLoading: Boolean,
    portfolioError: String?,
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
    onSavePreset: (String) -> Unit,
    onWalkForward: () -> Unit,
    walkForwardBusy: Boolean,
    dailyReconciliation: DailyPortfolioReconciliation? = null
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        PortfolioDataRefreshHeader(
            title = "Тест стратегии · 15м",
            portfolioLoading = portfolioLoading,
            onRefresh = onRefresh,
            onMoex15mFullReload = onMoex15mFullReload
        )
        Text(
            text = "${"%.0f".format(Locale.US, metrics?.notionalRub ?: DEFAULT_PORTFOLIO_NOTIONAL_RUB)} ₽ · x${String.format(Locale.US, "%.1f", leverage)} · ${String.format(Locale.US, "%.3f", commissionPercentPerSide)}% / сторона · симуляция по Z",
            color = Color(0xFF9E9E9E),
            fontSize = 10.sp,
            maxLines = 2
        )
        Text(
            text = "Пороги ниже задают только симуляцию на этом экране и не влияют на розовые пороги вкладки «Портфель».",
            color = Color(0xFFF48FB1),
            fontSize = 10.sp,
            maxLines = 2
        )
        PortfolioParamsControls(
            leverage = leverage,
            commissionPercentPerSide = commissionPercentPerSide,
            entryThreshold = entryThreshold,
            exitThreshold = exitThreshold,
            showZThresholdSteppers = true,
            onLeverageChange = onLeverageChange,
            onCommissionChange = onCommissionChange,
            onEntryThresholdChange = onEntryThresholdChange,
            onExitThresholdChange = onExitThresholdChange
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Режим симуляции",
                    color = Color(0xFFE0E0E0),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (compoundReturns) {
                        "Капитализация: перед каждой новой сделкой размер позиции пересчитывается от текущего капитала (старт + накопленный PnL в ₽)."
                    } else {
                        "Фиксированный номинал: каждая сделка как при первоначальных 100 тыс. ₽ (классическая симуляция)."
                    },
                    color = Color(0xFF757575),
                    fontSize = 9.sp,
                    maxLines = 3
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (compoundReturns) "Капитализация" else "Фикс.",
                    color = Color(0xFF9FA8DA),
                    fontSize = 10.sp
                )
                Switch(
                    checked = compoundReturns,
                    onCheckedChange = onCompoundReturnsChange
                )
            }
        }
        Text(
            text = "Сделки ниже — результат пересечения порогов на истории (не журнал исполнения).",
            color = Color(0xFF757575),
            fontSize = 10.sp
        )
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
            onSave = onSavePreset
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

        if (portfolioError != null) {
            Text(portfolioError, color = Color(0xFFEF9A9A), fontSize = 11.sp)
            Button(onClick = onRefresh, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Повторить", fontSize = 11.sp)
                }
            }
        } else if (!portfolioLoading && metrics == null) {
            Text("Недостаточно данных для симуляции.", color = Color(0xFFBDBDBD), fontSize = 11.sp)
        } else {
            metrics?.let { m ->
                PortfolioCollapsibleSection(
                    title = "Детальные показатели симуляции",
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
                    text = "Сделки симуляции (${m.closedTrades.size})",
                    color = Color(0xFFE0E0E0),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                val recent = m.closedTrades.takeLast(25).asReversed()
                if (recent.isEmpty()) {
                    Text("Закрытых сделок в симуляции нет.", color = Color(0xFF9E9E9E), fontSize = 11.sp)
                } else {
                    recent.forEachIndexed { index, t ->
                        PortfolioTradeRow(index = index + 1, t = t)
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
internal fun DailyReconciliationSection(rec: DailyPortfolioReconciliation) {
    val dayStr = rec.day.toString()
    PortfolioCollapsibleSection(
        title = "Сверка за день ($dayStr, МСК)",
        subtitle = "журнал ${rec.journalEnters} вх. / ${rec.journalExits} вых. · подтв. ${rec.confirmedClosedOnDay} · тест ${rec.simClosedOnDay}",
        defaultExpanded = true
    ) {
        Text(
            text = rec.simThresholdsNote,
            color = Color(0xFF757575),
            fontSize = 9.sp,
            maxLines = 4
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "PnL закрытых за день",
                color = Color(0xFF9E9E9E),
                fontSize = 10.sp
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "подтв. ${formatRubSigned(rec.confirmedPnlRubOnDay)}",
                    color = Color(0xFFE0E0E0),
                    fontSize = 11.sp
                )
                Text(
                    text = "тест ${formatRubSigned(rec.simPnlRubOnDay)}",
                    color = Color(0xFFE0E0E0),
                    fontSize = 11.sp
                )
            }
        }
        Text(
            text = "Закрытые сделки — с датой выхода в этот день на 15м ряду. Вход без выхода до конца дня попадает в причины ниже.",
            color = Color(0xFF616161),
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        rec.rows.forEach { row ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .background(
                        if (row.isOk) Color(0xFF1B2E1F) else Color(0xFF2E1F1F),
                        RoundedCornerShape(6.dp)
                    )
                    .padding(8.dp)
            ) {
                Text(
                    text = row.headline,
                    color = if (row.isOk) Color(0xFF81C784) else Color(0xFFFFAB91),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = row.detail,
                    color = Color(0xFFB0BEC5),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun PortfolioHeroMetricsRow(metrics: PortfolioMetrics?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PortfolioHeroHalfCard(
            title = "Итого PnL",
            subtitle = "оценка",
            value = metrics?.let {
                "${formatRubSigned(it.totalPnlRubApprox)}\n${formatPercentSigned(it.totalReturnPercent)}"
            } ?: "—",
            valueColor = run {
                val pnl = metrics?.totalPnlRubApprox
                when {
                    pnl == null -> Color(0xFFBDBDBD)
                    pnl > 0 -> Color(0xFF81C784)
                    pnl < 0 -> Color(0xFFE57373)
                    else -> Color(0xFFBDBDBD)
                }
            },
            modifier = Modifier.weight(1f)
        )
        PortfolioHeroHalfCard(
            title = "Макс. просадка",
            subtitle = "по эквити",
            value = metrics?.let { m ->
                "${formatRubSigned(-m.maxDrawdownRubApprox)}\n${String.format(Locale.US, "%.1f%%", m.maxDrawdownPercent)}"
            } ?: "—",
            valueColor = Color(0xFFFFB74D),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PortfolioHeroHalfCard(
    title: String,
    subtitle: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .background(Color(0xFF1A1A1A), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Color(0xFFB0BEC5), fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Color(0xFF616161), fontSize = 9.sp)
        }
        Text(
            text = value,
            color = valueColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun PortfolioMetricGrid(m: PortfolioMetrics, showHeroDuplicate: Boolean = true) {
    val pf = m.profitFactor?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showHeroDuplicate) {
            PortfolioStatCard(
                "Итого PnL (оценка)",
                "${formatRubSigned(m.totalPnlRubApprox)} (${formatPercentSigned(m.totalReturnPercent)})"
            )
        }
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
        if (showHeroDuplicate) {
            PortfolioStatCard(
                "Макс. просадка",
                "${formatRubSigned(-m.maxDrawdownRubApprox)} (~ ${String.format(Locale.US, "%.1f", m.maxDrawdownRubApprox / m.notionalRub * 100.0)}% от капитала, по эквити-пику ${String.format(Locale.US, "%.1f", m.maxDrawdownPercent)}%)"
            )
        }
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
    showZThresholdSteppers: Boolean,
    onLeverageChange: (Double) -> Unit,
    onCommissionChange: (Double) -> Unit,
    onEntryThresholdChange: (Double) -> Unit,
    onExitThresholdChange: (Double) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
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
        if (showZThresholdSteppers) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
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
        }
    }
}

@Composable
private fun ParamStepper(
    title: String,
    valueLabel: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color(0xFF1E1E1E),
    titleColor: Color = Color(0xFF9E9E9E),
    valueTextColor: Color = Color.White
) {
    Column(
        modifier
            .background(containerColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, color = titleColor, fontSize = 10.sp)
        Text(valueLabel, color = valueTextColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = onMinus, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Filled.Remove, contentDescription = "-", modifier = Modifier.size(20.dp))
            }
            Button(onClick = onPlus, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "+", modifier = Modifier.size(20.dp))
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
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Text(title, color = Color(0xFF9E9E9E), fontSize = 10.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun rubDeltaColor(v: Double): Color = when {
    v > 0 -> Color(0xFF81C784)
    v < 0 -> Color(0xFFE57373)
    else -> Color(0xFFBDBDBD)
}

@Composable
private fun PortfolioTradeRow(index: Int, t: PortfolioClosedTrade) {
    val dir = when (t.direction) {
        ZStrategyPosition.Long -> "LONG"
        ZStrategyPosition.Short -> "SHORT"
        ZStrategyPosition.Flat -> "—"
    }
    val pnlColor = rubDeltaColor(t.pnlRubApprox)
    val grossColor = rubDeltaColor(t.grossPnlRubApprox)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF252525), RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                "#$index  $dir ${t.entryDate} → ${t.exitDate}",
                color = Color(0xFFE0E0E0),
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatRubSigned(t.pnlRubApprox),
                    color = pnlColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "валовый ${formatRubSigned(t.grossPnlRubApprox)}",
                    color = grossColor,
                    fontSize = 9.sp
                )
            }
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
