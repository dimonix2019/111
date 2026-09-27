package com.example.moexmvp

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

internal const val BROKER_ACCOUNT_POLL_MS = 15_000L
/** Опрос вкладки «Сделка» (PnL / портфель), только когда вкладка открыта. */
internal const val TRADE_SCREEN_POLL_MS = 5_000L
/** Потолок одной загрузки вкладки «Сделка», чтобы опрос не залипал на GetOrderBook. */
internal const val TRADE_SCREEN_LOAD_TIMEOUT_MS = 8_000L
private const val BROKER_PUSH_BASE_ID = 43_100
internal const val BROKER_PROFIT_ALERT_PCT_2 = 2.0
internal const val BROKER_PROFIT_ALERT_PCT_3 = 3.0
private val takeProfitFlattenMutex = Mutex()

/**
 * Опрос портфеля T‑Invest (~15 с): push при открытии/закрытии пары
 * и при прибыли ≥2% / ≥3% от вложения (по одному разу на открытую позицию).
 */
internal suspend fun pollBrokerAccountAndNotify(context: Context) {
    val app = context.applicationContext
    val mode = TinkoffExecutionMode.Prod
    val token = TinkoffSandboxStorage.getActiveToken(app, mode) ?: return
    val accountId = TinkoffSandboxStorage.getActiveAccountId(app, mode) ?: return

    val portfolio = runCatching { tinkoffGetPortfolio(mode, token, accountId) }.getOrElse {
        MoexDiagnostics.log(app, "broker_poll", "portfolio fail: ${it.message}")
        return
    }
    val snap = detectBrokerSpreadPosition(portfolio)
    val seeded = BrokerAccountPrefs.isSeeded(app)
    val prevSide = BrokerAccountPrefs.lastSide(app)
    val prevFp = BrokerAccountPrefs.lastFingerprint(app)
    val prevYield = BrokerAccountPrefs.lastYieldRub(app)

    if (!seeded) {
        // Первый проход: запомнить состояние, без спама.
        val equity = when (snap.side) {
            ZStrategyPosition.Flat -> 0.0
            else -> snap.portfolioTotalRub?.takeIf { it > 0 }
                ?: DEFAULT_PORTFOLIO_NOTIONAL_RUB
        }
        BrokerAccountPrefs.saveSnap(
            app,
            side = snap.side,
            fingerprint = snap.fingerprint,
            yieldRub = snap.expectedYieldRub,
            equityAtOpen = if (snap.side != ZStrategyPosition.Flat) equity else null,
            clearProfitFlags = false,
        )
        if (snap.side != ZStrategyPosition.Flat && BrokerAccountPrefs.takeProfitPctAtOpen(app) == null) {
            BrokerAccountPrefs.saveTakeProfitForOpen(app, BrokerAccountPrefs.lastTakeProfitPct(app))
        }
        MoexDiagnostics.log(app, "broker_poll", "seeded side=${snap.side} fp=${snap.fingerprint}")
        return
    }

    val opened = prevSide == ZStrategyPosition.Flat &&
        (snap.side == ZStrategyPosition.Long || snap.side == ZStrategyPosition.Short)
    val closed = prevSide != ZStrategyPosition.Flat && snap.side == ZStrategyPosition.Flat
    val sideChanged = prevSide != ZStrategyPosition.Flat &&
        snap.side != ZStrategyPosition.Flat &&
        prevSide != snap.side
    val fpChanged = snap.side != ZStrategyPosition.Flat &&
        prevFp != snap.fingerprint &&
        prevSide != ZStrategyPosition.Flat

    when {
        opened || sideChanged -> {
            val equity = snap.portfolioTotalRub?.takeIf { it > 0 }
                ?: DEFAULT_PORTFOLIO_NOTIONAL_RUB
            val sideRu = when (snap.side) {
                ZStrategyPosition.Long -> "Long"
                ZStrategyPosition.Short -> "Short"
                else -> "?"
            }
            val spreadPart = snap.spreadPercent?.let {
                String.format(Locale.US, " · S=%+.2f%%", it)
            }.orEmpty()
            val pricePart = buildString {
                snap.tatnPriceRub?.let { append(String.format(Locale.US, "TATN %.2f", it)) }
                snap.tatnpPriceRub?.let {
                    if (isNotEmpty()) append(" / ")
                    append(String.format(Locale.US, "TATNP %.2f", it))
                }
            }
            val body = buildString {
                append("$sideRu · ${snap.lotsAbs} лот$spreadPart")
                if (pricePart.isNotBlank()) append(" · $pricePart")
                append(" · ${formatPortfolioExecutionTableMsk(System.currentTimeMillis())}")
            }
            showPushNotification(
                app,
                title = "Брокер: открыта пара",
                body = body,
                notificationId = BROKER_PUSH_BASE_ID + 1,
                skipDuplicateCheck = true,
                correlationTag = "broker_open_${snap.fingerprint}",
            )
            markEntryAlertTradeOpened(app, snap.side)
            BrokerAccountPrefs.saveSnap(
                app,
                side = snap.side,
                fingerprint = snap.fingerprint,
                yieldRub = snap.expectedYieldRub,
                equityAtOpen = equity,
                clearProfitFlags = true,
            )
            if (BrokerAccountPrefs.takeProfitPctAtOpen(app) == null) {
                BrokerAccountPrefs.saveTakeProfitForOpen(app, BrokerAccountPrefs.lastTakeProfitPct(app))
            }
        }
        closed -> {
            val yield = snap.expectedYieldRub ?: prevYield.takeIf { it != 0.0 }
            val sideRu = when (prevSide) {
                ZStrategyPosition.Long -> "Long"
                ZStrategyPosition.Short -> "Short"
                else -> "?"
            }
            val pnlPart = yield?.let {
                String.format(Locale.US, " · PnL %+.0f ₽", it)
            }.orEmpty()
            showPushNotification(
                app,
                title = "Брокер: пара закрыта",
                body = "$sideRu$pnlPart · ${formatPortfolioExecutionTableMsk(System.currentTimeMillis())}",
                notificationId = BROKER_PUSH_BASE_ID + 2,
                skipDuplicateCheck = true,
                correlationTag = "broker_close_$prevFp",
            )
            BrokerAccountPrefs.saveSnap(
                app,
                side = ZStrategyPosition.Flat,
                fingerprint = "FLAT",
                yieldRub = yield,
                clearProfitFlags = true,
            )
        }
        fpChanged -> {
            // Смена лотов / ключа при той же стороне — сброс порогов прибыли.
            val equity = snap.portfolioTotalRub?.takeIf { it > 0 }
                ?: BrokerAccountPrefs.equityAtOpenRub(app).takeIf { it > 0 }
                ?: DEFAULT_PORTFOLIO_NOTIONAL_RUB
            BrokerAccountPrefs.saveSnap(
                app,
                side = snap.side,
                fingerprint = snap.fingerprint,
                yieldRub = snap.expectedYieldRub,
                equityAtOpen = equity,
                clearProfitFlags = true,
            )
        }
        else -> {
            BrokerAccountPrefs.saveSnap(
                app,
                side = snap.side,
                fingerprint = snap.fingerprint,
                yieldRub = snap.expectedYieldRub,
            )
        }
    }

    if (snap.side != ZStrategyPosition.Flat) {
        notifyBrokerProfitThresholds(app, snap)
        maybeFlattenOnTakeProfit(app, snap, portfolio, token, accountId)
    }
}

private fun notifyBrokerProfitThresholds(app: Context, snap: BrokerSpreadPositionSnap) {
    val yield = snap.expectedYieldRub ?: return
    if (!(yield > 0)) return
    val deposit = BrokerAccountPrefs.equityAtOpenRub(app).takeIf { it > 0 }
        ?: DEFAULT_PORTFOLIO_NOTIONAL_RUB
    if (!(deposit > 0)) return
    val pct = (yield / deposit) * 100.0
    val fp = snap.fingerprint
    val sideRu = when (snap.side) {
        ZStrategyPosition.Long -> "Long"
        ZStrategyPosition.Short -> "Short"
        else -> "?"
    }

    fun maybeAlert(threshold: Double, markPct: Int, nid: Int) {
        val already = when (markPct) {
            2 -> BrokerAccountPrefs.profit2Fingerprint(app)
            3 -> BrokerAccountPrefs.profit3Fingerprint(app)
            else -> return
        }
        if (already == fp) return
        if (pct < threshold) return
        val body = String.format(
            Locale.US,
            "%s · %+.0f ₽ (%+.1f%% от вложения %.0f ₽)",
            sideRu,
            yield,
            pct,
            deposit,
        )
        showPushNotification(
            app,
            title = "Брокер: прибыль ≥${threshold.toInt()}%",
            body = body,
            notificationId = BROKER_PUSH_BASE_ID + nid,
            skipDuplicateCheck = true,
            correlationTag = "broker_profit_${threshold.toInt()}_$fp",
        )
        BrokerAccountPrefs.markProfitAlert(app, markPct, fp)
    }

    maybeAlert(BROKER_PROFIT_ALERT_PCT_2, markPct = 2, nid = 3)
    maybeAlert(BROKER_PROFIT_ALERT_PCT_3, markPct = 3, nid = 4)
}

internal fun shouldCheckExecutableTakeProfit(
    grossExpectedYieldRub: Double?,
    depositRub: Double,
    takeProfitPct: Double,
): Boolean = shouldFireTakeProfit(grossExpectedYieldRub, depositRub, takeProfitPct)

internal fun shouldFireExecutableTakeProfit(
    closeNowNetRub: Double?,
    depositRub: Double,
    takeProfitPct: Double,
): Boolean = shouldFireTakeProfit(closeNowNetRub, depositRub, takeProfitPct)

private suspend fun maybeFlattenOnTakeProfit(
    app: Context,
    snap: BrokerSpreadPositionSnap,
    portfolio: JSONObject,
    token: String,
    accountId: String,
) {
    val tp = BrokerAccountPrefs.takeProfitPctForOpenOrDefault(app)
    val deposit = BrokerAccountPrefs.equityAtOpenRub(app).takeIf { it > 0 }
        ?: snap.portfolioTotalRub?.takeIf { it > 0 }
        ?: return
    // GetPortfolio expectedYield — дешёвый gross gate. Стаканы читаем только около цели.
    if (!shouldCheckExecutableTakeProfit(snap.expectedYieldRub, deposit, tp)) return

    takeProfitFlattenMutex.withLock {
        val fp = "${snap.fingerprint}|tp=$tp"
        if (BrokerAccountPrefs.takeProfitFiredFingerprint(app) == fp) return@withLock
        val closeNow = loadTakeProfitCloseNowConfirmation(
            app = app,
            snap = snap,
            portfolio = portfolio,
            token = token,
            accountId = accountId,
            depositRub = deposit,
        ) ?: return@withLock
        if (!shouldFireExecutableTakeProfit(closeNow.netRub, deposit, tp)) return@withLock

        BrokerAccountPrefs.markTakeProfitFired(app, fp)
        val net = closeNow.netRub
        val pct = if (deposit > 0) (net / deposit) * 100.0 else 0.0
        val sideRu = when (snap.side) {
            ZStrategyPosition.Long -> "Long"
            ZStrategyPosition.Short -> "Short"
            else -> "?"
        }
        showPushNotification(
            app,
            title = "Take profit ${formatTakeProfitPctInput(tp)}% — закрываем пару",
            body = String.format(
                Locale.US,
                "%s · %+.0f ₽ (%+.1f%% от вложения %.0f ₽)",
                sideRu,
                net,
                pct,
                deposit,
            ),
            notificationId = BROKER_PUSH_BASE_ID + 5,
            skipDuplicateCheck = true,
            correlationTag = "broker_tp_$fp",
        )
        val result = runEmergencyFlattenFromTradeTab(app)
        result.onSuccess { msg ->
            MoexDiagnostics.log(
                app,
                "broker_poll",
                "take_profit flatten ok tp=$tp net=${String.format(Locale.US, "%.2f", net)} $msg",
            )
        }.onFailure { e ->
            BrokerAccountPrefs.clearTakeProfitFired(app, fp)
            MoexDiagnostics.logError(app, "broker_poll", e, "take_profit flatten tp=$tp")
        }
    }
}

private suspend fun loadTakeProfitCloseNowConfirmation(
    app: Context,
    snap: BrokerSpreadPositionSnap,
    portfolio: JSONObject,
    token: String,
    accountId: String,
    depositRub: Double,
): CloseNowPnl? {
    val avg = parseSpreadLegAveragePrices(portfolio)
    val exec = TinkoffSandboxSpreadExecLog.loadRecent(app)
        .lastOrNull { openExecMatchesBrokerSide(it, snap.side) }
    val entryTimeMsk = resolveSpreadEntryTimeMsk(
        context = app,
        side = snap.side,
        token = token,
        accountId = accountId,
        exec = exec,
    )?.first
    val lotsAbs = maxOf(abs(snap.tatnLots), abs(snap.tatnpLots)).toDouble()
        .coerceAtLeast(1.0)
    val quotes = withTimeoutOrNull(CLOSE_NOW_BOOK_FETCH_MS) {
        fetchTinkoffPairQuotesForClose(
            token = token,
            tatnInstrumentId = TINKOFF_MOEX_TATN_FIGI,
            tatnpInstrumentId = TINKOFF_MOEX_TATNP_FIGI,
            lotsAbs = lotsAbs,
        )
    }
    return computeCloseNowPnl(
        tatnLots = snap.tatnLots,
        tatnpLots = snap.tatnpLots,
        fillTatnRub = avg.tatnAvgPriceRub,
        fillTatnpRub = avg.tatnpAvgPriceRub,
        quotes = quotes,
        fallbackTatnLast = snap.tatnPriceRub,
        fallbackTatnpLast = snap.tatnpPriceRub,
        depositRub = depositRub,
        cashRub = parsePortfolioCashRubDouble(portfolio),
        entryTimeMsk = entryTimeMsk,
    )
}
