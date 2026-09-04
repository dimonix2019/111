package com.example.moexmvp

import android.content.Context
import java.util.Locale

internal const val BROKER_ACCOUNT_POLL_MS = 15_000L
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
