package com.example.moexmvp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.util.Locale

/** Строка таблицы «Сделки за 2 недели» на вкладке «Сделка». */
internal data class TradeTabClosedTrade(
    val id: String,
    val directionLabel: String,
    val entryTimeMsk: String,
    val exitTimeMsk: String,
    val quantityLots: Int,
    val entrySpreadPercent: Double?,
    val exitSpreadPercent: Double?,
    val pnlRub: Double?,
    val sourceLabel: String,
)

internal const val TRADE_TAB_HISTORY_DAYS = 14L

/**
 * Журнал закрытых сделок для вкладки «Сделка».
 * Сначала web-стол (там AUTO + ручные + подхваченные с брокера), иначе локальный журнал приложения.
 */
internal suspend fun loadTradeTabClosedTrades(
    context: Context,
    days: Long = TRADE_TAB_HISTORY_DAYS,
): Pair<List<TradeTabClosedTrade>, String> = withContext(Dispatchers.IO) {
    val cutoffDate = LocalDate.now(moexZoneId).minusDays(days)
    val cutoffLabel = cutoffDate.format(tradeDateFormatter)

    val web = WebDeskApi.fetchClosedTrades(context).getOrNull()
    if (web != null) {
        val rows = web.mapNotNull { t ->
            val exitLabel = t.exitTime?.take(16).orEmpty()
            val entryLabel = t.entryTime?.take(16).orEmpty()
            val datePart = (exitLabel.ifBlank { entryLabel }).take(10)
            if (datePart.isBlank() || datePart < cutoffLabel) return@mapNotNull null
            TradeTabClosedTrade(
                id = "#${t.id}",
                directionLabel = if (t.direction.equals("SHORT", true)) "Short" else "Long",
                entryTimeMsk = entryLabel,
                exitTimeMsk = exitLabel,
                quantityLots = t.quantityLots,
                entrySpreadPercent = t.entrySpread,
                exitSpreadPercent = t.exitSpread,
                pnlRub = t.pnlRub,
                sourceLabel = t.source?.uppercase(Locale.US) ?: "",
            )
        }.sortedByDescending { it.exitTimeMsk.ifBlank { it.entryTimeMsk } }
        return@withContext rows to "web-стол"
    }

    val cutoffMs = cutoffDate.atStartOfDay(moexZoneId).toInstant().toEpochMilli()
    val local = TinkoffClosedSpreadExecLog.loadRecent(context)
        .filter { it.exitTimestampMillis >= cutoffMs || it.closedAtMillis >= cutoffMs }
        .map { r ->
            TradeTabClosedTrade(
                id = r.tradeId,
                directionLabel = if (r.signalType == StrategySignalType.EnterShort) "Short" else "Long",
                entryTimeMsk = r.entryTimeMsk,
                exitTimeMsk = formatPortfolioExecutionTableMsk(r.exitTimestampMillis),
                quantityLots = r.quantityLots,
                entrySpreadPercent = r.entrySpreadPercent.takeIf { it.isFinite() },
                exitSpreadPercent = null,
                pnlRub = r.realizedNetRub ?: (r.longLegYieldRub + r.shortLegYieldRub),
                sourceLabel = if (r.source == PortfolioExecSource.AUTO) "AUTO" else "ручное",
            )
        }
        .sortedByDescending { it.exitTimeMsk }
    local to "локальный журнал"
}

/**
 * Кнопки «Открыть Long/Short» на вкладке «Сделка»: две рыночные заявки на боевой счёт
 * с расчётом лотов как у AUTO, запись в журналы приложения (источник MANUAL).
 * Web-монитор подхватит позицию с брокера своей сверкой.
 */
internal suspend fun runManualSpreadEntryFromTradeTab(
    context: Context,
    signalType: StrategySignalType,
): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        require(
            signalType == StrategySignalType.EnterLong || signalType == StrategySignalType.EnterShort
        ) { "Только EnterLong / EnterShort" }
        val app = context.applicationContext
        val mode = currentExecutionMode(app)
        require(mode == TinkoffExecutionMode.Prod) {
            "Вкладка «Сделка» работает с боевым счётом. Сейчас: ${executionModeLabelRu(mode)}."
        }
        val token = TinkoffSandboxStorage.getActiveToken(app, mode)
            ?: throw IOException("Нет токена (${executionModeLabelRu(mode)}).")
        val accountId = TinkoffSandboxStorage.getActiveAccountId(app, mode)
            ?: throw IOException("Нет счёта (${executionModeLabelRu(mode)}).")

        // Не переворачиваем существующую позицию: вход только из FLAT.
        val portfolio = tinkoffGetPortfolio(mode, token, accountId)
        val broker = detectBrokerSpreadPosition(portfolio)
        require(broker.side == ZStrategyPosition.Flat) {
            "На брокере уже есть позиция (${broker.side}) — сначала закройте её."
        }

        val market = loadCurrentPortfolioMarketSnapshot(app, forTestEntry = true)
        val dir = if (signalType == StrategySignalType.EnterShort) "SHORT" else "LONG"

        val entry = executeSpreadEntryDetailedForConfiguredMode(app, signalType)
        val legs = entry.legs
        val sizing = entry.sizing
        val executedAt = System.currentTimeMillis()

        recordStrategySignalEvent(
            context = app,
            signalType = signalType,
            zScore = market.zScore,
            timestampMillis = market.timestampMillis,
            skipJournalWallDedup = true,
            savePendingVirtualTradeIfEntry = false,
        )
        saveStrategyPosition(
            app,
            if (signalType == StrategySignalType.EnterShort) ZStrategyPosition.Short else ZStrategyPosition.Long,
        )
        appendPortfolioExecutionLedger(
            app,
            barTimestampMillis = market.timestampMillis,
            signalType = signalType,
            source = PortfolioExecSource.MANUAL,
        )
        val execution = TinkoffSandboxSpreadExecLog.recordFromLegs(
            app,
            signalType,
            market.zScore,
            barTimestampMillis = market.timestampMillis,
            executedAtMillis = executedAt,
            entrySpreadPercent = market.entrySpreadPercent,
            source = PortfolioExecSource.MANUAL,
            legs = legs,
            fromTestButton = false,
            quantityLots = sizing.quantityLots,
            executionNotionalRub = sizing.executionNotionalRub,
        )
        execution?.let { opened ->
            TradeExecutionLog.recordSpreadLegFills(
                context = app,
                tradeId = opened.tradeId,
                phase = TradeExecPhase.Entry,
                legs = legs,
                executionMode = mode,
                signalBarMillis = market.timestampMillis,
                zScore = market.zScore,
                refTatnPriceRub = sizing.priceTatN,
                refTatnpPriceRub = sizing.priceTatNp,
                refSpreadPercent = market.entrySpreadPercent,
                source = "manual_entry_trade_tab",
            )
            notifySandboxTradeOpened(
                context = app,
                execution = opened,
                notionalRub = sizing.executionNotionalRub,
                leverage = TinkoffSandboxStorage.getSandboxNotifyLeverage(app),
                portfolioTotalRub = legs.lastOrNull()?.portfolioTotalRub,
                portfolioCashRub = legs.lastOrNull()?.portfolioCashRub,
            )
        }
        "Открыт $dir ${sizing.quantityLots}+${sizing.quantityLots} лот (спред ${formatSpreadPct(market.entrySpreadPercent)})"
    }
}

private fun formatSpreadPct(v: Double): String =
    if (v.isFinite()) String.format(Locale.US, "%.2f%%", v) else "—"
