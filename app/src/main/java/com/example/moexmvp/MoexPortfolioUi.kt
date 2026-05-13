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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import java.time.Instant
import java.time.ZoneId
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

/** Подтверждённые сделки: пары вход/выход из журнала сигналов на 15-мин ряду. */
@Composable
internal fun ConfirmedPortfolioTabContent(
    metrics: PortfolioMetrics?,
    portfolioLoading: Boolean,
    portfolioError: String?,
    onRefresh: () -> Unit,
    leverage: Double,
    commissionPercentPerSide: Double,
    onLeverageChange: (Double) -> Unit,
    onCommissionChange: (Double) -> Unit,
    /** Увеличить после успешного «Принять» на песочнице, чтобы подтянуть блок «2 ноги». */
    sandboxSpreadExecReload: Int = 0
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        PortfolioDataRefreshHeader(
            title = "Портфель · подтверждённые",
            portfolioLoading = portfolioLoading,
            onRefresh = onRefresh,
            onMoex15mFullReload = null
        )
        Text(
            text = "${"%.0f".format(Locale.US, metrics?.notionalRub ?: DEFAULT_PORTFOLIO_NOTIONAL_RUB)} ₽ · x${String.format(Locale.US, "%.1f", leverage)} · ${String.format(Locale.US, "%.3f", commissionPercentPerSide)}% / сторона · 15 мин · журнал вход/выход",
            color = Color(0xFF9E9E9E),
            fontSize = 10.sp,
            maxLines = 3
        )
        val context = LocalContext.current
        var sandboxExec by remember { mutableStateOf<SandboxSpreadExecUi?>(null) }
        LaunchedEffect(sandboxSpreadExecReload) {
            sandboxExec = TinkoffSandboxSpreadExecLog.load(context)
        }
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
                text = "Учитываются только закрытые сделки, у которых в журнале есть и вход, и выход.",
                color = Color(0xFF757575),
                fontSize = 10.sp
            )
        }
        PortfolioCollapsibleSection(
            title = "Песочница: последнее «Принять» (2 ноги)",
            defaultExpanded = false
        ) {
            val ex = sandboxExec
            if (ex == null) {
                Text(
                    text = "Пока нет записи последнего исполнения двух биржевых заявок на демо-счёте.",
                    color = Color(0xFF9E9E9E),
                    fontSize = 11.sp
                )
            } else {
                val mskWhen = Instant.ofEpochMilli(ex.timestampMillis)
                    .atZone(ZoneId.of("Europe/Moscow"))
                    .toLocalDateTime()
                    .format(portfolio15mLabelFormatter)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1B2A20), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Последнее «Принять» на песочнице — 2 биржевые заявки (ноги спрэда)",
                        color = Color(0xFFB2DFDB),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    val title = when (ex.signalType) {
                        StrategySignalType.EnterLong -> "LONG спрэд (TATN / TATNP)"
                        StrategySignalType.EnterShort -> "SHORT спрэд (TATNP / TATN)"
                        else -> ""
                    }
                    Text(title, color = Color(0xFFE0E0E0), fontSize = 11.sp)
                    Text(
                        text = "Z = ${String.format(Locale.US, "%.2f", ex.zScore)} · $mskWhen (МСК)",
                        color = Color(0xFF9E9E9E),
                        fontSize = 10.sp
                    )
                    Text(ex.legsRu, color = Color(0xFFC8E6C9), fontSize = 11.sp)
                    Text(
                        text = "Список «Сделки» ниже — по одной карточке на полный круг вход→выход по журналу Z; внутри карточки две связанные ноги.",
                        color = Color(0xFF78909C),
                        fontSize = 9.sp,
                        maxLines = 4
                    )
                }
            }
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
            Text("Нет подтверждённых сделок за период (или нет данных 15м).", color = Color(0xFFBDBDBD), fontSize = 11.sp)
        } else {
            metrics?.let { m ->
                PortfolioCompactHeroInline(m)
                PortfolioCollapsibleSection(
                    title = "Все показатели (сводка и сетка)",
                    subtitle = "${formatRubSigned(m.totalPnlRubApprox)} · просадка ${formatRubSigned(-m.maxDrawdownRubApprox)}",
                    defaultExpanded = false
                ) {
                    Text(
                        text = "Итого = реализованный PnL + нереализованная нога (если по журналу позиция ещё открыта). Обновляется при новых сигналах и «Обновить».",
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
                    text = "Сделки (${m.closedTrades.size}) — связанные ноги LONG + SHORT",
                    color = Color(0xFFE0E0E0),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = "Одна карточка = одна спрэд-позиция по журналу. Две строки — бумаги TATN и TATNP; итог внизу — общий PnL по движению спрэда (не сумма двух отдельных акций).",
                    color = Color(0xFF616161),
                    fontSize = 9.sp,
                    maxLines = 3
                )
                val recent = m.closedTrades.takeLast(25).asReversed()
                if (recent.isEmpty()) {
                    Text("Закрытых с подтверждённым входом и выходом нет.", color = Color(0xFF9E9E9E), fontSize = 11.sp)
                } else {
                    recent.forEachIndexed { index, t ->
                        ConfirmedSpreadTradeCard(index = index + 1, t = t)
                    }
                }
            }
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
    walkForwardBusy: Boolean
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, color = Color(0xFF9E9E9E), fontSize = 10.sp)
        Text(valueLabel, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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

@Composable
private fun ConfirmedSpreadTradeCard(index: Int, t: PortfolioClosedTrade) {
    val spreadTitle = when (t.direction) {
        ZStrategyPosition.Long -> "LONG спрэд TATN / TATNP"
        ZStrategyPosition.Short -> "SHORT спрэд TATNP / TATN"
        ZStrategyPosition.Flat -> "Спрэд"
    }
    val longTicker: String
    val longLabel: String
    val shortTicker: String
    val shortLabel: String
    when (t.direction) {
        ZStrategyPosition.Long -> {
            longTicker = "TATN"
            longLabel = "LONG · покупка базы (нога 1)"
            shortTicker = "TATNP"
            shortLabel = "SHORT · продажа префа (нога 2)"
        }
        ZStrategyPosition.Short -> {
            longTicker = "TATNP"
            longLabel = "LONG · покупка префа (нога 1)"
            shortTicker = "TATN"
            shortLabel = "SHORT · продажа базы (нога 2)"
        }
        ZStrategyPosition.Flat -> {
            longTicker = "—"
            longLabel = "—"
            shortTicker = "—"
            shortLabel = "—"
        }
    }
    val pnlColor = when {
        t.pnlRubApprox > 0 -> Color(0xFF81C784)
        t.pnlRubApprox < 0 -> Color(0xFFE57373)
        else -> Color(0xFFBDBDBD)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF263238), RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Спрэд #$index  $spreadTitle",
                color = Color(0xFFB0BEC5),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${t.entryDate} → ${t.exitDate}",
                color = Color(0xFF78909C),
                fontSize = 10.sp
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1B3D2F), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(longTicker, color = Color(0xFF81C784), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(longLabel, color = Color(0xFFCFD8DC), fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF3D2626), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(shortTicker, color = Color(0xFFFFAB91), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(shortLabel, color = Color(0xFFCFD8DC), fontSize = 10.sp)
            }
        }
        Text(
            text = "⟷  Обе ноги одной позиции; PnL — один на спрэд (дельта спрэда в журнале, с плечом и комиссиями из настроек выше).",
            color = Color(0xFF90A4AE),
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 6.dp),
            maxLines = 3
        )
        Text(
            text = "Спрэд ${String.format(Locale.US, "%.2f", t.entrySpreadPercent)}% → ${String.format(Locale.US, "%.2f", t.exitSpreadPercent)}% (${String.format(Locale.US, "%+.2f", t.pnlSpreadPoints)} п.п.)",
            color = Color(0xFF9E9E9E),
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Общий результат (оценка)",
                color = Color(0xFFE0E0E0),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = formatRubSigned(t.pnlRubApprox),
                color = pnlColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
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
