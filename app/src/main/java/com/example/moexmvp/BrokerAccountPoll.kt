package com.example.moexmvp

import android.content.Context
import java.util.Locale

internal const val BROKER_ACCOUNT_POLL_MS = 15_000L
/** Опрос вкладки «Сделка» (PnL / портфель), только когда вкладка открыта. */
internal const val TRADE_SCREEN_POLL_MS = 5_000L
/** Потолок одной загрузки вкладки «Сделка», чтобы опрос не залипал на GetOrderBook. */
internal const val TRADE_SCREEN_LOAD_TIMEOUT_MS = 8_000L
private const val BROKER_PUSH_BASE_ID = 43_100
internal const val BROKER_PROFIT_ALERT_PCT_2 = 2.0
internal const val BROKER_PROFIT_ALERT_PCT_3 = 3.0

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
        maybeNotifyTakeProfit(app, token, snap)
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

private suspend fun maybeNotifyTakeProfit(
    app: Context,
    token: String,
    snap: BrokerSpreadPositionSnap,
) {
    if (snap.side == ZStrategyPosition.Flat) return
    val tp = BrokerAccountPrefs.takeProfitPctForOpenOrDefault(app)
    val open = TinkoffSandboxSpreadExecLog.loadRecent(app).firstOrNull { exec ->
        exec.entrySpreadPercent.isFinite() && execMatchesBrokerSide(exec.signalType, snap.side)
    } ?: return
    val entry = open.entrySpreadPercent
    val bookSpread = snap.spreadPercent ?: return
    val leverage = SPREAD_LOT_PROD_DEFAULT_LEVERAGE
    if (!takeProfitSpreadReached(snap.side, entry, bookSpread, tp, leverage)) return
    val quotes = runCatching {
        fetchTinkoffPairQuotesForClose(
            token,
            TINKOFF_MOEX_TATN_FIGI,
            TINKOFF_MOEX_TATNP_FIGI,
            snap.lotsAbs.toDouble().coerceAtLeast(1.0),
        )
    }.getOrNull() ?: return
    val live = spreadPercentFromPairQuotes(quotes) ?: return
    if (!takeProfitSpreadReached(snap.side, entry, live, tp, leverage)) {
        MoexDiagnostics.log(app, "broker_poll", "take_profit live recheck miss entry=$entry live=$live")
        return
    }
    val fp = "${snap.fingerprint}|tp=$tp|entry=$entry"
    if (BrokerAccountPrefs.takeProfitFiredFingerprint(app) == fp) return
    val points = when (snap.side) {
        ZStrategyPosition.Long -> live - entry
        ZStrategyPosition.Short -> entry - live
        ZStrategyPosition.Flat -> return
    }
    val grossPct = leverage * points
    val netPct = grossPct - 2.0 * PROD_COMMISSION_PCT_PER_SIDE * leverage
    val ovnDays = open.entryTimeMsk.takeIf { it.isNotBlank() }?.let { entryLabel ->
        val end = formatPortfolioExecutionTableMsk(System.currentTimeMillis())
        overnightDays(
            portfolioDateLabelFromMskTableTime(entryLabel),
            portfolioDateLabelFromMskTableTime(end),
        )
    } ?: 0L
    val sideRu = when (snap.side) {
        ZStrategyPosition.Long -> "Long"
        ZStrategyPosition.Short -> "Short"
        ZStrategyPosition.Flat -> return
    }
    val ovnNote = if (ovnDays > 0) {
        " Перенос $ovnDays дн. в порог не входит."
    } else {
        ""
    }
    BrokerAccountPrefs.markTakeProfitFired(app, fp)
    showPushNotification(
        app,
        title = "Take profit ${formatTakeProfitPctInput(tp)}%",
        body = String.format(
            Locale.US,
            "%s · вход %.2f%% · сейчас %.2f%% · ход %+.2f п. · до комиссии %+.2f%% депозита · после комиссии %+.2f%%.%s",
            sideRu,
            entry,
            live,
            points,
            grossPct,
            netPct,
            ovnNote,
        ),
        notificationId = BROKER_PUSH_BASE_ID + 5,
        skipDuplicateCheck = true,
        correlationTag = "broker_tp_$fp",
    )
    MoexDiagnostics.log(app, "broker_poll", "take_profit notify tp=$tp entry=$entry live=$live")
}

private fun execMatchesBrokerSide(signal: StrategySignalType, side: ZStrategyPosition): Boolean =
    when (side) {
        ZStrategyPosition.Long -> signal == StrategySignalType.EnterLong
        ZStrategyPosition.Short -> signal == StrategySignalType.EnterShort
        ZStrategyPosition.Flat -> false
    }
