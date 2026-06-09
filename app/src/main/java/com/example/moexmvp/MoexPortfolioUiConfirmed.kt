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
internal fun ConfirmedPortfolioTabContent(
    confirmedTradeTableRows: List<PortfolioConfirmedTradeTableRow>,
    sandboxSpreadExecutions: List<SandboxSpreadExecUi>,
    portfolioLoading: Boolean,
    portfolioError: String?,
    onRefresh: () -> Unit,
    onMoex15mFullReload: (() -> Unit)? = null,
    lookbackDays: Long,
    onLookbackDaysChange: (Long) -> Unit,
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
    portfolioTestBusy: Boolean,
    onTestSpreadPairLongClick: () -> Unit,
    onTestSpreadPairShortClick: () -> Unit,
    closeAllPortfolioBusy: Boolean,
    onCloseAllTradesClick: () -> Unit,
    onCloseOpenTrade: ((tradeId: String) -> Unit)? = null,
    closingTradeId: String? = null,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        PortfolioDataRefreshHeader(
            title = "Портфель · демо-счёт",
            portfolioLoading = portfolioLoading,
            onRefresh = onRefresh,
            onMoex15mFullReload = onMoex15mFullReload
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Глубина",
                color = Color(0xFF9E9E9E),
                fontSize = 11.sp,
            )
            PortfolioDepthSelector(
                selectedDays = lookbackDays,
                onSelect = onLookbackDaysChange,
                enabled = !portfolioLoading,
            )
            Text(
                text = "Свечной график Z-score · 15м — вкладка «Рынок»",
                color = Color(0xFF9E9E9E),
                fontSize = 11.sp
            )
        }
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
                    text = "Ручной",
                    color = if (!portfolioLedgerIncludeAuto) Color(0xFFF8BBD0) else Color(0xFF9E9E9E),
                    fontSize = 12.sp,
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
                    text = "Авто",
                    color = if (portfolioLedgerIncludeAuto) Color(0xFFF8BBD0) else Color(0xFF9E9E9E),
                    fontSize = 12.sp,
                    fontWeight = if (portfolioLedgerIncludeAuto) FontWeight.SemiBold else FontWeight.Normal
                )
            }
            Text(
                text = if (portfolioLedgerIncludeAuto) {
                    "Авто: сигнал и «Тестовая пара Long/Short» сразу открывают сделку на демо (2 заявки), без «Принять»."
                } else {
                    "Ручной: при сигнале или «Тестовая пара Long/Short» — карточка «Принять» / «Отклонить», затем сделка в открытых."
                },
                color = Color(0xFFCE93D8),
                fontSize = 10.sp,
                lineHeight = 13.sp
            )
            Text(
                text = "Пороги |Z| для сигналов, push, фона и линий на «Рынке»",
                color = Color(0xFFFCE4EC),
                fontSize = 10.sp
            )
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onTestSpreadPairLongClick,
                        enabled = !portfolioTestBusy,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF69F0AE))
                    ) {
                        Text("Тестовая пара Long", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = onTestSpreadPairShortClick,
                        enabled = !portfolioTestBusy,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8A80))
                    ) {
                        Text("Тестовая пара Short", fontSize = 11.sp)
                    }
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
        PortfolioTradesWindowSection(
            openExecutions = sandboxSpreadExecutions,
            closedRows = confirmedTradeTableRows,
            lookbackDays = lookbackDays,
            realTradeEntryThreshold = realTradeEntryThreshold,
            onCloseOpenTrade = onCloseOpenTrade,
            closingTradeId = closingTradeId,
        )
        PortfolioCollapsibleSection(
            title = "Плечо и комиссия",
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
        }
    }
}
