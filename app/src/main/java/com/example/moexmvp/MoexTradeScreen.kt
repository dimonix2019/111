package com.example.moexmvp

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

/** Снимок открытой spread-сделки TATN/TATNP для вкладки «Сделка». */
internal data class TradeScreenSnapshot(
    val loadedAtMillis: Long,
    val error: String? = null,
    val side: ZStrategyPosition = ZStrategyPosition.Flat,
    val tatnLots: Int = 0,
    val tatnpLots: Int = 0,
    val tatnPriceRub: Double? = null,
    val tatnpPriceRub: Double? = null,
    val tatnAvgPriceRub: Double? = null,
    val tatnpAvgPriceRub: Double? = null,
    val expectedYieldRub: Double? = null,
    val spreadPercentNow: Double? = null,
    val spreadPercentEntry: Double? = null,
    val portfolioTotalRub: Double? = null,
    val cashRub: Double? = null,
    val margin: MarginAttributesSnapshot? = null,
    val notionalRub: Double? = null,
    val depositRub: Double? = null,
    val holdMillis: Long? = null,
    val entryTimeMsk: String? = null,
    val entryTimeSource: SpreadEntryTimeSource? = null,
    val takeProfitForecast: TakeProfitForecast? = null,
    val execSourceLabel: String? = null,
    val quantityLots: Int = 0,
) {
    val isOpen: Boolean
        get() = side == ZStrategyPosition.Long || side == ZStrategyPosition.Short

    val pnlPercentFromDeposit: Double?
        get() {
            val y = expectedYieldRub ?: return null
            val d = depositRub?.takeIf { it > 0 } ?: return null
            return (y / d) * 100.0
        }

    val sideTitleRu: String
        get() = when (side) {
            ZStrategyPosition.Long -> "Long"
            ZStrategyPosition.Short -> "Short"
            ZStrategyPosition.Flat -> "—"
        }
}

internal suspend fun loadTradeScreenSnapshot(context: Context): TradeScreenSnapshot {
    val now = System.currentTimeMillis()
    val mode = currentExecutionMode(context)
    if (mode != TinkoffExecutionMode.Prod) {
        return TradeScreenSnapshot(
            loadedAtMillis = now,
            error = "Вкладка «Сделка» показывает боевой счёт T‑Invest Prod. Сейчас режим: ${executionModeLabelRu(mode)}.",
        )
    }
    val token = TinkoffSandboxStorage.getActiveToken(context, mode)
    val accountId = TinkoffSandboxStorage.getActiveAccountId(context, mode)
    if (token.isNullOrBlank() || accountId.isNullOrBlank()) {
        return TradeScreenSnapshot(
            loadedAtMillis = now,
            error = "Нет токена или счёта — настройте в «Песочница».",
        )
    }
    return runCatching {
        val portfolio = tinkoffGetPortfolio(mode, token, accountId)
        val broker = detectBrokerSpreadPosition(portfolio)
        val avg = parseSpreadLegAveragePrices(portfolio)
        val margin = runCatching { tinkoffGetMarginAttributes(mode, token, accountId) }.getOrNull()
        val cash = parsePortfolioCashRubDouble(portfolio)
        val exec = TinkoffSandboxSpreadExecLog.loadRecent(context)
            .lastOrNull { openExecMatchesBrokerSide(it, broker.side) }
        val entrySpread = when {
            exec != null && exec.entrySpreadPercent.isFinite() && exec.entrySpreadPercent != 0.0 ->
                exec.entrySpreadPercent
            avg.tatnAvgPriceRub != null && avg.tatnpAvgPriceRub != null ->
                spreadPercentFromPrices(avg.tatnAvgPriceRub, avg.tatnpAvgPriceRub)
            else -> null
        }
        val lots = broker.lotsAbs
        val notional = computeSpreadNotionalRub(
            tatnLots = broker.tatnLots,
            tatnpLots = broker.tatnpLots,
            tatnPriceRub = broker.tatnPriceRub,
            tatnpPriceRub = broker.tatnpPriceRub,
        )
        val deposit = BrokerAccountPrefs.equityAtOpenRub(context).takeIf { it > 0 }
            ?: margin?.startingMarginRub?.takeIf { it > 0 }
            ?: exec?.entryPortfolioTotalRub?.takeIf { it > 0 }
        val holdMs = exec?.executedAtMillis?.let { now - it }?.takeIf { it >= 0 }
        val entryResolved = resolveSpreadEntryTimeMsk(
            context = context,
            side = broker.side,
            token = token,
            accountId = accountId,
            exec = exec,
        )
        val entryTimeMsk = entryResolved?.first
        val entrySource = entryResolved?.second
        if (entryTimeMsk != null && entrySource == SpreadEntryTimeSource.ExecLog) {
            BrokerAccountPrefs.saveEntryTimeAtOpen(context, entryTimeMsk)
        }
        val holdFromEntry = entryTimeMsk?.let { parsePortfolioExecutionTableMsk(it) }
            ?.let { now - it }?.takeIf { it >= 0 }
        val tpForecast = computeTakeProfitForecast(
            side = broker.side,
            entrySpreadPercent = entrySpread,
            depositRub = deposit,
            notionalRub = notional,
            lots = lots,
            fillTatnRub = avg.tatnAvgPriceRub,
            fillTatnpRub = avg.tatnpAvgPriceRub,
            entryTimeMsk = entryTimeMsk,
        )
        TradeScreenSnapshot(
            loadedAtMillis = now,
            side = broker.side,
            tatnLots = broker.tatnLots,
            tatnpLots = broker.tatnpLots,
            tatnPriceRub = broker.tatnPriceRub,
            tatnpPriceRub = broker.tatnpPriceRub,
            tatnAvgPriceRub = avg.tatnAvgPriceRub,
            tatnpAvgPriceRub = avg.tatnpAvgPriceRub,
            expectedYieldRub = broker.expectedYieldRub,
            spreadPercentNow = broker.spreadPercent,
            spreadPercentEntry = entrySpread,
            portfolioTotalRub = broker.portfolioTotalRub,
            cashRub = cash,
            margin = margin,
            notionalRub = notional,
            depositRub = deposit,
            holdMillis = holdFromEntry ?: holdMs,
            entryTimeMsk = entryTimeMsk,
            entryTimeSource = entrySource,
            takeProfitForecast = tpForecast,
            execSourceLabel = exec?.let { portfolioExecSourceLabel(it.source) },
            quantityLots = lots,
        )
    }.getOrElse { err ->
        TradeScreenSnapshot(
            loadedAtMillis = now,
            error = err.message ?: "Ошибка загрузки портфеля",
        )
    }
}

private fun openExecMatchesBrokerSide(exec: SandboxSpreadExecUi, side: ZStrategyPosition): Boolean =
    when (side) {
        ZStrategyPosition.Long -> exec.signalType == StrategySignalType.EnterLong
        ZStrategyPosition.Short -> exec.signalType == StrategySignalType.EnterShort
        ZStrategyPosition.Flat -> false
    }

private fun portfolioExecSourceLabel(source: PortfolioExecSource): String = when (source) {
    PortfolioExecSource.AUTO -> "AUTO"
    PortfolioExecSource.MANUAL -> "ручное"
}

private fun spreadPercentFromPrices(longPx: Double, shortPx: Double): Double? {
    if (shortPx <= 0.0) return null
    return (longPx / shortPx - 1.0) * 100.0
}

internal fun computeSpreadNotionalRub(
    tatnLots: Int,
    tatnpLots: Int,
    tatnPriceRub: Double?,
    tatnpPriceRub: Double?,
): Double? {
    val a = tatnPriceRub ?: return null
    val b = tatnpPriceRub ?: return null
    if (a <= 0.0 || b <= 0.0) return null
    return abs(tatnLots) * a + abs(tatnpLots) * b
}

internal suspend fun MoexScreenState.refreshTradeScreenFromBroker() {
    tradeScreenLoading = true
    val snap = withContext(Dispatchers.IO) { loadTradeScreenSnapshot(context) }
    tradeScreenSnapshot = snap
    tradeScreenLoading = false
}

@Composable
internal fun MoexScreenTabTrade(
    screen: MoexScreenState,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val snap = screen.tradeScreenSnapshot
    val loading = screen.tradeScreenLoading

    LaunchedEffect(screen.selectedTab, screen.activityResumed) {
        if (screen.selectedTab != MainTab.Trade || !screen.activityResumed) return@LaunchedEffect
        screen.refreshTradeScreenFromBroker()
        while (screen.activityResumed && screen.selectedTab == MainTab.Trade) {
            delay(BROKER_ACCOUNT_POLL_MS)
            if (!screen.activityResumed || screen.selectedTab != MainTab.Trade) break
            screen.refreshTradeScreenFromBroker()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Сделка · T‑Invest Prod",
                color = Color(0xFFB0BEC5),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Button(
                onClick = { scope.launch { screen.refreshTradeScreenFromBroker() } },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242)),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Text("Обновить", fontSize = 10.sp)
            }
        }

        if (loading && snap == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp),
                color = Color(0xFF1565C0),
            )
            return@Column
        }

        snap?.error?.let { err ->
            TradeInfoCard(title = "Нет данных") {
                Text(err, color = Color(0xFFFFAB91), fontSize = 12.sp)
            }
        }

        if (snap == null) {
            Text("Загрузка…", color = Color(0xFF757575), fontSize = 12.sp)
            return@Column
        }

        if (!snap.isOpen) {
            TradeInfoCard(title = "Позиция") {
                Text("Нет открытой сделки", color = Color(0xFFE0E0E0), fontSize = 14.sp)
                snap.portfolioTotalRub?.let {
                    TradeMetricRow("Средства (портфель)", formatRubPlain(it))
                }
                snap.cashRub?.let {
                    TradeMetricRow("Деньги (₽)", formatRubPlain(it))
                }
            }
            snap.margin?.let { TradeMarginCard(it) }
            TradeFooterNote(snap.loadedAtMillis, loading)
            return@Column
        }

        val sourceSuffix = snap.execSourceLabel?.let { " · $it" }.orEmpty()
        TradeInfoCard(
            title = "${snap.sideTitleRu} ${snap.quantityLots}+${snap.quantityLots} лот$sourceSuffix",
            accent = if (snap.side == ZStrategyPosition.Long) Color(0xFF26A69A) else Color(0xFFEF5350),
        ) {
            snap.entryTimeMsk?.let { TradeMetricRow("Вход", it) }
            TradeMetricRow(
                "Спред",
                formatSpreadPair(snap.spreadPercentEntry, snap.spreadPercentNow),
            )
            TradeMetricRow(
                "TATN / TATNP (сейчас)",
                formatPricePair(snap.tatnPriceRub, snap.tatnpPriceRub),
            )
            if (snap.tatnAvgPriceRub != null || snap.tatnpAvgPriceRub != null) {
                TradeMetricRow(
                    "TATN / TATNP (средняя)",
                    formatPricePair(snap.tatnAvgPriceRub, snap.tatnpAvgPriceRub),
                )
            }
            snap.notionalRub?.let { TradeMetricRow("Номинал", formatRubPlain(it)) }
            snap.depositRub?.let { TradeMetricRow("Вложения (оценка)", formatRubPlain(it)) }
            snap.portfolioTotalRub?.let { TradeMetricRow("Средства (портфель)", formatRubPlain(it)) }
            snap.cashRub?.let { TradeMetricRow("Деньги (₽)", formatRubPlain(it)) }
            snap.expectedYieldRub?.let { pnl ->
                val pct = snap.pnlPercentFromDeposit
                val color = when {
                    pnl > 0 -> Color(0xFF66BB6A)
                    pnl < 0 -> Color(0xFFEF5350)
                    else -> Color(0xFFE0E0E0)
                }
                TradeMetricRow(
                    "PnL (брокер)",
                    buildString {
                        append(formatRubSigned(pnl))
                        pct?.let { append(" (${formatPercentSigned(it)})") }
                    },
                    valueColor = color,
                )
            }
            snap.takeProfitForecast?.let { tp ->
                val color = when {
                    tp.netPnlRub > 0 -> Color(0xFF66BB6A)
                    tp.netPnlRub < 0 -> Color(0xFFEF5350)
                    else -> Color(0xFFE0E0E0)
                }
                Text(
                    text = formatTakeProfitForecastLine(tp),
                    color = color,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                )
            }
            snap.holdMillis?.let { TradeMetricRow("Удержание", formatHoldDuration(it)) }
            TradeMetricRow("Лоты TATN / TATNP", "${snap.tatnLots} / ${snap.tatnpLots}")
        }

        snap.margin?.let { TradeMarginCard(it) }

        TradeInfoCard(title = "Источник данных") {
            Text(
                "Позиции, PnL и цены — GetPortfolio T‑Invest. Маржа — GetMarginAttributes. " +
                    "Время входа — журнал исполнений, лог ног, prefs или GetOperations. " +
                    "Прогноз ТП — локально (parity web close_forecast, ТП ${DEFAULT_TAKE_PROFIT_PCT.toInt()}%). " +
                    "Овернайт-строка, риск и AUTO — только на web-столе.",
                color = Color(0xFF9E9E9E),
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }

        TradeFooterNote(snap.loadedAtMillis, loading)
    }
}

@Composable
private fun TradeFooterNote(loadedAtMillis: Long, loading: Boolean) {
    val label = formatPortfolioExecutionTableMsk(loadedAtMillis)
    Text(
        text = if (loading) "Обновление… · было $label" else "Обновлено $label · опрос раз в мин",
        color = Color(0xFF616161),
        fontSize = 9.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun TradeMarginCard(margin: MarginAttributesSnapshot) {
    val headroom = computeMarginCallHeadroom(margin) ?: return
    val fillColor = when (headroom.zone) {
        MarginCallHeadroomZone.Green -> Color(0xFF66BB6A)
        MarginCallHeadroomZone.Yellow -> Color(0xFFFFCA28)
        MarginCallHeadroomZone.Red -> Color(0xFFEF5350)
    }
    val fillFraction = (headroom.pct / 100.0).coerceIn(0.0, 1.0).toFloat()
    val pctLabel = String.format(Locale.US, "%.1f%%", headroom.pct)
    TradeInfoCard(title = "Запас до маржин-колла") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF0F172A)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fillFraction)
                    .background(fillColor),
            )
            Text(
                text = "до колла: ${formatRubPlain(headroom.freeRub)} ($pctLabel)",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun TradeInfoCard(
    title: String,
    accent: Color = Color(0xFF1565C0),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            text = title,
            color = accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
    }
}

@Composable
private fun TradeMetricRow(
    label: String,
    value: String,
    valueColor: Color = Color(0xFFE0E0E0),
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color(0xFF757575), fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            color = valueColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

private fun formatRubPlain(v: Double): String =
    String.format(Locale.US, "%,.0f ₽", v)

private fun formatPricePair(a: Double?, b: Double?): String {
    val left = a?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
    val right = b?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
    return "$left / $right"
}

private fun formatSpreadPair(entry: Double?, now: Double?): String {
    val e = entry?.let { String.format(Locale.US, "%.2f%%", it) } ?: "—"
    val n = now?.let { String.format(Locale.US, "%.2f%%", it) } ?: "—"
    return "$e → $n"
}

private fun formatHoldDuration(millis: Long): String {
    val hours = millis / 3_600_000.0
    val days = hours / 24.0
    return if (days >= 1.0) {
        String.format(Locale.US, "%.1f ч (%.1f д)", hours, days)
    } else {
        String.format(Locale.US, "%.1f ч", hours)
    }
}
