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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

@Composable
internal fun DailyReconciliationSection(rec: DailyPortfolioReconciliation) {
    val dayStr = rec.day.toString()
    PortfolioCollapsibleSection(
        title = "Сверка за день ($dayStr, МСК)",
        subtitle = "откр. ${dirRuShort(rec.journalPositionAtDayOpen)}/${dirRuShort(rec.simPositionAtDayOpen)} · " +
            "журнал ${rec.journalEnters} вх./${rec.journalExits} вых. · подтв. ${rec.confirmedClosedOnDay} · тест ${rec.simClosedOnDay}",
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
internal fun PortfolioHeroMetricsRow(metrics: PortfolioMetrics?) {
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
internal fun PortfolioHeroHalfCard(
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
internal fun PortfolioMetricGrid(m: PortfolioMetrics, showHeroDuplicate: Boolean = true) {
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
internal fun PortfolioParamsControls(
    leverage: Double,
    commissionPercentPerSide: Double,
    entryThreshold: Double,
    exitThreshold: Double,
    showZThresholdSteppers: Boolean,
    showExitThresholdStepper: Boolean = true,
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
                    modifier = if (showExitThresholdStepper) Modifier.weight(1f) else Modifier.fillMaxWidth()
                )
                if (showExitThresholdStepper) {
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
}
@Composable
internal fun PortfolioTradeRow(index: Int, t: PortfolioClosedTrade, showTradeDuration: Boolean = false) {
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
                    text = "чистый",
                    color = Color(0xFF757575),
                    fontSize = 8.sp
                )
            }
        }
        if (showTradeDuration) {
            val durationLabel = formatSimTradeDurationLabel(t.entryDate, t.exitDate)
            val durationValueColor = when {
                isSimTradeDurationUnderDay(t.entryDate, t.exitDate) -> Color(0xFF81C784)
                isSimTradeDurationOverDay(t.entryDate, t.exitDate) -> Color(0xFFE57373)
                else -> Color(0xFF9E9E9E)
            }
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = Color(0xFF9E9E9E))) {
                        append("Длительность сделки: ")
                    }
                    withStyle(SpanStyle(color = durationValueColor)) {
                        append(durationLabel)
                    }
                },
                fontSize = 10.sp
            )
        }
        Text(
            text = "валовый ${formatRubSigned(t.grossPnlRubApprox)} · комис. ${formatRubExpense(t.commissionRubApprox)} · оверн. ${formatRubExpense(t.overnightRubApprox)}",
            color = grossColor,
            fontSize = 10.sp
        )
        Text(
            "спрэд ${String.format(Locale.US, "%.2f", t.entrySpreadPercent)}% → ${String.format(Locale.US, "%.2f", t.exitSpreadPercent)}% (${String.format(Locale.US, "%+.2f", t.pnlSpreadPoints)} п.п.)",
            color = Color(0xFF9E9E9E),
            fontSize = 10.sp
        )
    }
}

internal fun formatRubSigned(v: Double): String {
    val s = String.format(Locale.US, "%+.0f ₽", v)
    return s
}

internal fun formatRubExpense(v: Double): String =
    String.format(Locale.US, "−%.0f ₽", kotlin.math.abs(v))

internal fun formatPercentSigned(v: Double): String {
    return String.format(Locale.US, "%+.2f%%", v)
}
@Composable
internal fun ParamStepper(
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
internal fun ParamRubInputStepper(
    title: String,
    valueRub: Double,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    minRub: Double = STRATEGY_TEST_ACCOUNT_RUB_MIN,
    maxRub: Double = STRATEGY_TEST_ACCOUNT_RUB_MAX,
    stepRub: Double = 1_000.0,
    containerColor: Color = Color(0xFF1E1E1E),
    titleColor: Color = Color(0xFF9E9E9E),
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var draft by remember { mutableStateOf(formatStrategyTestAccountRubInput(valueRub)) }
    var editing by remember { mutableStateOf(false) }

    LaunchedEffect(valueRub) {
        if (!editing) {
            draft = formatStrategyTestAccountRubInput(valueRub)
        }
    }

    fun dismissEditor() {
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
    }

    fun commitDraft() {
        editing = false
        val parsed = parseStrategyTestAccountRubInput(draft, minRub, maxRub)
        if (parsed != null) {
            draft = formatStrategyTestAccountRubInput(parsed)
            if (parsed != valueRub) {
                onValueChange(parsed)
            }
        } else {
            draft = formatStrategyTestAccountRubInput(valueRub)
        }
        dismissEditor()
    }

    fun applyStep(nextRub: Double) {
        editing = false
        val coerced = nextRub.coerceIn(minRub, maxRub)
        draft = formatStrategyTestAccountRubInput(coerced)
        dismissEditor()
        if (coerced != valueRub) {
            onValueChange(coerced)
        }
    }

    Column(
        modifier
            .background(containerColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = titleColor, fontSize = 10.sp)
        OutlinedTextField(
            value = draft,
            onValueChange = {
                editing = true
                draft = it.filter { ch -> ch.isDigit() }
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focus ->
                    if (focus.isFocused) {
                        editing = true
                    } else if (editing) {
                        commitDraft()
                    }
                },
            singleLine = true,
            suffix = { Text("₽", color = Color(0xFFBDBDBD), fontSize = 12.sp) },
            trailingIcon = {
                IconButton(onClick = { commitDraft() }) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Применить",
                        tint = Color(0xFF81D4FA),
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { commitDraft() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF81D4FA),
                unfocusedBorderColor = Color(0xFF424242),
                cursorColor = Color(0xFF81D4FA),
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                onClick = { applyStep(valueRub - stepRub) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "-", modifier = Modifier.size(20.dp))
            }
            Button(
                onClick = { applyStep(valueRub + stepRub) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "+", modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
internal fun PortfolioStatCard(title: String, value: String) {
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

internal fun rubDeltaColor(v: Double): Color = when {
    v > 0 -> Color(0xFF81C784)
    v < 0 -> Color(0xFFE57373)
    else -> Color(0xFFBDBDBD)
}
