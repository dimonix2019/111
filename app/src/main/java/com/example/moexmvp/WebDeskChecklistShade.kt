package com.example.moexmvp

import android.content.Context
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.max

private const val M1_MS = 60_000L
private const val M15_MS = 15L * 60_000L
private const val TIP1M_SETTLE_MS = 10_000L
private const val BAR_SETTLE_MS = 90_000L
/** Как PHASE_NEAR_Z в trade.js — «подготовка», если до порога ≤ 0.30. */
private const val PHASE_NEAR = 0.30

/** Снимок чеклиста Trade Desk для ongoing-шторки. */
internal data class WebDeskShadeSnapshot(
    /** ↘ / ↗ или пусто. */
    val icon: String,
    /** Чеклист: «Подготовка к закрытию · ждём: edge». */
    val statusLine: String,
    /**
     * Краткая строка шторки: режим / уровни / остаток до входа или выхода.
     * Пример: `Long · S 2,83% · до L вых 4,0: 1,17`
     */
    val briefLine: String,
    val spreadPercent: Double?,
    val position: String,
)

internal fun formatRuSignedNumber(value: Double, digits: Int = 2): String =
    String.format(Locale("ru", "RU"), "%.${digits}f", value)

internal fun formatShadeSpreadMetric(spreadPercent: Double): String =
    "S ${formatRuSignedNumber(spreadPercent)}%"

/** Подпись уровня как на графике: 6,2 / 5,8 / 4 / 3,2. */
internal fun formatShadeLevelLabel(value: Double): String {
    val digits = if (kotlin.math.abs(value % 1.0) < 1e-9) 0 else 1
    return formatRuSignedNumber(value, digits)
}

/** Режим ширины спреда (те же cuts 3.5 / 5.5, что Prod). */
internal fun shadeSpreadRegimeRu(spreadPercent: Double?): String {
    val s = spreadPercent ?: return "—"
    if (!s.isFinite()) return "—"
    return when {
        s < 3.5 -> "узкий"
        s > 5.5 -> "широкий"
        else -> "переход"
    }
}

internal fun cacheEntryAlertLevelsFromDeskRoot(context: Context, root: JSONObject) {
    val settings = root.optJSONObject("settings") ?: JSONObject()
    val sl = root.optJSONObject("spread_levels") ?: JSONObject()
    val levels = sl.optJSONObject("levels")
    val enterW = numOr(settings, "spread_enter_wide", levels, "enter_wide", DEFAULT_SPREAD_ENTER_WIDE)
    val enterN = numOr(settings, "spread_enter_narrow", levels, "enter_narrow", DEFAULT_SPREAD_ENTER_NARROW)
    EntryLevelAlertSettings.cacheEnterLevels(
        context = context,
        longEnterPct = enterN,
        shortEnterPct = enterW,
    )
}

/**
 * Разбор lite `/api/trade/desk` → статус чеклиста + спред (без Z как основной метрики).
 * Логика фаз — как `buildTradePhase` / hint в trade.js.
 */
internal fun buildWebDeskShadeSnapshot(
    root: JSONObject,
    nowMs: Long = System.currentTimeMillis(),
    nowMsk: ZonedDateTime = ZonedDateTime.now(moexZoneId),
): WebDeskShadeSnapshot? {
    val settings = root.optJSONObject("settings") ?: JSONObject()
    val mon = root.optJSONObject("monitor") ?: JSONObject()
    val sl = root.optJSONObject("spread_levels") ?: JSONObject()
    val bars = root.optJSONArray("bars")
    val open = root.optJSONObject("open")
    val broker = root.optJSONObject("broker")
    val bs = root.optJSONObject("broker_spread")

    val pos = root.optString("position", open?.optString("direction").orEmpty())
        .ifBlank { "FLAT" }
        .uppercase(Locale.US)

    val tip1mMode = settings.optString("signal_mode", "tip1m") == "tip1m"
    val spreadOn = when {
        !settings.has("spread_level_mode") || settings.isNull("spread_level_mode") -> true
        settings.optBoolean("spread_level_mode", true) -> true
        else -> settings.optString("spread_level_mode") == "1"
    }
    val autoOn = settings.optBoolean("auto_execute", false)
    val monOn = mon.optBoolean("running", false)

    val levels = sl.optJSONObject("levels")
    val enterW = numOr(settings, "spread_enter_wide", levels, "enter_wide", DEFAULT_SPREAD_ENTER_WIDE)
    val exitW = numOr(settings, "spread_exit_wide", levels, "exit_wide", 5.8)
    val enterNlv = numOr(settings, "spread_enter_narrow", levels, "enter_narrow", DEFAULT_SPREAD_ENTER_NARROW)
    val exitNlv = numOr(settings, "spread_exit_narrow", levels, "exit_narrow", 4.0)
    val entryN = settings.optDouble("entry_z", 1.6).takeIf { settings.has("entry_z") } ?: 1.6
    val exitN = settings.optDouble("exit_z", 1.3).takeIf { settings.has("exit_z") } ?: 1.3

    val last = bars?.optJSONObject((bars.length() - 1).coerceAtLeast(0))?.takeIf { bars.length() > 0 }
    val prev = bars?.optJSONObject((bars.length() - 2).coerceAtLeast(0))?.takeIf { bars.length() >= 2 }
    val lastMs = last?.optLong("timestampMs", 0L) ?: 0L
    val prevMs = prev?.optLong("timestampMs", 0L) ?: 0L
    val consecutive = prevMs > 0L && (lastMs - prevMs == M1_MS || lastMs - prevMs == M15_MS)
    val settled = if (tip1mMode) {
        lastMs > 0L && nowMs >= lastMs + M1_MS + TIP1M_SETTLE_MS
    } else {
        lastMs > 0L && nowMs >= lastMs + M15_MS + BAR_SETTLE_MS
    }
    val settleLeftSec = if (lastMs > 0L) {
        val deadline = if (tip1mMode) lastMs + M1_MS + TIP1M_SETTLE_MS else lastMs + M15_MS + BAR_SETTLE_MS
        max(0L, ((deadline - nowMs + 999L) / 1000L)).toInt()
    } else {
        0
    }

    val curS = barSpread(last) ?: sl.optDouble("spread").takeIf { sl.has("spread") && !sl.isNull("spread") }
    val prevS = barSpread(prev)
    val curZ = barZ(last)
    val prevZ = barZ(prev)
    val barTd = last?.optString("time")?.takeIf { it.isNotBlank() }
        ?: last?.optString("tradeDate")?.takeIf { it.isNotBlank() }
        ?: ""
    val inSessionNow = isTqbrSessionNow(nowMsk)
    val barInSession = isTqbrSessionBarLabel(barTd)
    val sessionOk = inSessionNow && (barTd.isBlank() || barInSession)

    // Lite desk часто без broker — для шторки считаем OK, если ошибки нет.
    val brokerOk = broker == null || broker.optString("error").isNullOrBlank()
    val ghost = open == null && bs != null &&
        !bs.optBoolean("error", false) &&
        bs.optString("direction").isNotBlank()
    val hardOk = monOn && sessionOk && consecutive && brokerOk && !ghost

    var signal = "NONE"
    if (consecutive) {
        signal = if (spreadOn) {
            determineSpreadLevelSignal(prevS, curS, pos, enterW, exitW, enterNlv, exitNlv)
        } else {
            determineZSignal(prevZ, curZ, pos, entryN, exitN)
        }
        if (spreadOn && sl.optBoolean("entry_blocked", false) &&
            (signal == "ENTER_LONG" || signal == "ENTER_SHORT")
        ) {
            signal = "NONE"
        }
    }

    val needLong = if (spreadOn) {
        curS?.let { max(0.0, it - enterNlv) }
    } else {
        curZ?.let { max(0.0, it + entryN) }
    }
    val needShort = if (spreadOn) {
        curS?.let { max(0.0, enterW - it) }
    } else {
        curZ?.let { max(0.0, entryN - it) }
    }
    val needExitLong = if (spreadOn) {
        curS?.let { max(0.0, exitNlv - it) }
    } else {
        curZ?.let { max(0.0, (-exitN) - it) }
    }
    val needExitShort = if (spreadOn) {
        curS?.let { max(0.0, it - exitW) }
    } else {
        curZ?.let { max(0.0, it - exitN) }
    }

    val phase = buildTradePhaseForShade(
        pos = pos,
        curZ = curZ,
        curS = curS,
        entryN = entryN,
        exitN = exitN,
        signal = signal,
        hardOk = hardOk,
        autoOn = autoOn,
        settled = settled,
        tip1mMode = tip1mMode,
        settleLeftSec = settleLeftSec,
        needLong = needLong,
        needShort = needShort,
        needExitLong = needExitLong,
        needExitShort = needExitShort,
        spreadOn = spreadOn,
        enterNarrow = enterNlv,
        enterWide = enterW,
        exitNarrow = exitNlv,
        exitWide = exitW,
    )

    val (icon, status) = shadeStatusFromPhase(
        phase = phase,
        pos = pos,
        needLong = needLong,
        needShort = needShort,
        needExitLong = needExitLong,
        needExitShort = needExitShort,
        spreadOn = spreadOn,
    )
    val brief = buildShadeBriefLine(
        pos = pos,
        curS = curS,
        spreadOn = spreadOn,
        enterW = enterW,
        exitW = exitW,
        enterN = enterNlv,
        exitN = exitNlv,
        needLong = needLong,
        needShort = needShort,
        needExitLong = needExitLong,
        needExitShort = needExitShort,
    )
    return WebDeskShadeSnapshot(
        icon = icon,
        statusLine = status,
        briefLine = brief,
        spreadPercent = curS,
        position = pos,
    )
}

/**
 * Краткая строка шторки: спред + режим + остаток до ближайшего входа (FLAT)
 * или до выхода (Long/Short).
 */
internal fun buildShadeBriefLine(
    pos: String,
    curS: Double?,
    spreadOn: Boolean,
    enterW: Double,
    exitW: Double,
    enterN: Double,
    exitN: Double,
    needLong: Double?,
    needShort: Double?,
    needExitLong: Double?,
    needExitShort: Double?,
): String {
    val sPart = curS?.takeIf { it.isFinite() }?.let { formatShadeSpreadMetric(it) }
    if (!spreadOn) {
        return when (pos) {
            "LONG" -> listOfNotNull("Long", sPart, needExitLong?.let {
                "до выхода ещё +${formatRuSignedNumber(it)} по Z"
            }).joinToString(" · ")
            "SHORT" -> listOfNotNull("Short", sPart, needExitShort?.let {
                "до выхода ещё −${formatRuSignedNumber(it)} по Z"
            }).joinToString(" · ")
            else -> {
                val nearer = when {
                    needLong != null && needShort != null && needLong <= needShort ->
                        "до Long ещё −${formatRuSignedNumber(needLong)} по Z"
                    needShort != null && (needLong == null || needShort < needLong) ->
                        "до Short ещё +${formatRuSignedNumber(needShort)} по Z"
                    needLong != null ->
                        "до Long ещё −${formatRuSignedNumber(needLong)} по Z"
                    else -> null
                }
                listOfNotNull(sPart, nearer).joinToString(" · ").ifBlank { "Ожидание входа" }
            }
        }
    }
    val regime = shadeSpreadRegimeRu(curS)
    return when (pos) {
        "LONG" -> {
            val dist = if (curS != null && curS.isFinite()) {
                (exitN - curS).coerceAtLeast(0.0)
            } else {
                needExitLong?.takeIf { it.isFinite() }
            }
            val bit = dist?.let {
                "до L вых ${formatShadeLevelLabel(exitN)}: ${formatRuSignedNumber(it)}"
            }
            listOfNotNull("Long", sPart, bit).joinToString(" · ")
        }
        "SHORT" -> {
            val dist = if (curS != null && curS.isFinite()) {
                (curS - exitW).coerceAtLeast(0.0)
            } else {
                needExitShort?.takeIf { it.isFinite() }
            }
            val bit = dist?.let {
                "до S вых ${formatShadeLevelLabel(exitW)}: ${formatRuSignedNumber(it)}"
            }
            listOfNotNull("Short", sPart, bit).joinToString(" · ")
        }
        else -> {
            // До ближайшего входа: знак = (уровень − сейчас), как «до L вх 3,2: +0,50».
            val nearer = if (curS != null && curS.isFinite()) {
                val dLong = kotlin.math.abs(enterN - curS)
                val dShort = kotlin.math.abs(enterW - curS)
                if (dLong <= dShort) {
                    "до L вх ${formatShadeLevelLabel(enterN)}: ${formatRuDelta(enterN - curS)}"
                } else {
                    "до S вх ${formatShadeLevelLabel(enterW)}: ${formatRuDelta(enterW - curS)}"
                }
            } else {
                when {
                    needLong != null && needShort != null && needLong <= needShort ->
                        "до L вх ${formatShadeLevelLabel(enterN)}: −${formatRuSignedNumber(needLong)}"
                    needShort != null && (needLong == null || needShort < needLong) ->
                        "до S вх ${formatShadeLevelLabel(enterW)}: +${formatRuSignedNumber(needShort)}"
                    needLong != null ->
                        "до L вх ${formatShadeLevelLabel(enterN)}: −${formatRuSignedNumber(needLong)}"
                    else -> null
                }
            }
            listOfNotNull(sPart, "режим $regime", nearer).joinToString(" · ")
                .ifBlank { "Ожидание входа" }
        }
    }
}

/** Дельта с явным знаком (+/−) для шторки. */
internal fun formatRuDelta(value: Double, digits: Int = 2): String =
    String.format(Locale("ru", "RU"), "%+.${digits}f", value)

private data class ShadePhase(
    val kind: String,
    val side: String? = null,
    val title: String,
    val detail: String? = null,
)

private fun shadeStatusFromPhase(
    phase: ShadePhase,
    pos: String,
    needLong: Double?,
    needShort: Double?,
    needExitLong: Double?,
    needExitShort: Double?,
    spreadOn: Boolean,
): Pair<String, String> {
    when (phase.kind) {
        "ready", "signal", "prep" -> {
            val icon = when (phase.side) {
                "close" -> "↘"
                "open" -> "↗"
                else -> ""
            }
            return icon to phase.title
        }
    }
    return when (pos) {
        "FLAT" -> {
            val nearer = if (needLong != null && needShort != null) {
                if (needLong <= needShort) {
                    "до Long ещё −${formatRuSignedNumber(needLong)}" +
                        if (spreadOn) "" else " по Z"
                } else {
                    "до Short ещё +${formatRuSignedNumber(needShort)}" +
                        if (spreadOn) "" else " по Z"
                }
            } else {
                null
            }
            "" to if (nearer != null) "Ожидание входа · $nearer" else "Ожидание входа"
        }
        "LONG" -> {
            val dist = needExitLong?.takeIf { it.isFinite() }?.let {
                " · до выхода ещё +${formatRuSignedNumber(it)}" + if (spreadOn) "" else " по Z"
            }.orEmpty()
            "" to "В позиции Long$dist"
        }
        else -> {
            val dist = needExitShort?.takeIf { it.isFinite() }?.let {
                " · до выхода ещё −${formatRuSignedNumber(it)}" + if (spreadOn) "" else " по Z"
            }.orEmpty()
            "" to "В позиции Short$dist"
        }
    }
}

private fun buildTradePhaseForShade(
    pos: String,
    curZ: Double?,
    curS: Double?,
    entryN: Double,
    exitN: Double,
    signal: String,
    hardOk: Boolean,
    autoOn: Boolean,
    settled: Boolean,
    tip1mMode: Boolean,
    settleLeftSec: Int,
    needLong: Double?,
    needShort: Double?,
    needExitLong: Double?,
    needExitShort: Double?,
    spreadOn: Boolean = false,
    enterNarrow: Double = DEFAULT_SPREAD_ENTER_NARROW,
    enterWide: Double = DEFAULT_SPREAD_ENTER_WIDE,
    exitNarrow: Double = 4.0,
    exitWide: Double = 5.8,
): ShadePhase {
    val softWait = mutableListOf<String>()
    if (!autoOn) softWait += "авто"
    if (!settled) {
        softWait += when {
            tip1mMode && settleLeftSec > 0 -> "закрытие минутки tip (~${settleLeftSec}с)"
            tip1mMode -> "закрытие минутки tip"
            settleLeftSec > 0 -> "закрытие бара (~${settleLeftSec}с)"
            else -> "закрытие бара"
        }
    }
    fun waitingText(extra: String? = null): String {
        val all = softWait + listOfNotNull(extra?.takeIf { it.isNotBlank() })
        return if (all.isEmpty()) "" else "ждём: ${all.joinToString(", ")}"
    }

    val nearTh = PHASE_NEAR
    val sOk = curS != null && curS.isFinite()

    if (pos == "FLAT") {
        val nearLong = needLong != null && needLong <= nearTh
        val nearShort = needShort != null && needShort <= nearTh
        val atLevel = if (spreadOn) {
            sOk && (curS!! <= enterNarrow || curS >= enterWide)
        } else {
            curZ != null && (curZ <= -entryN || curZ >= entryN)
        }
        val hasEdge = signal.startsWith("ENTER")
        val approach = hasEdge || atLevel || nearLong || nearShort
        if (!hardOk || !approach) {
            return ShadePhase(kind = "idle", title = "Условия входа ещё далеко")
        }
        val sideHint = when {
            hasEdge -> signal
            nearLong || (if (spreadOn) sOk && curS!! <= enterNarrow else curZ != null && curZ <= -entryN) -> "Long"
            nearShort || (if (spreadOn) sOk && curS!! >= enterWide else curZ != null && curZ >= entryN) -> "Short"
            else -> ""
        }
        if (hasEdge && softWait.isEmpty()) {
            return ShadePhase(
                kind = "ready",
                side = "open",
                title = "Готово к AUTO: $signal",
                detail = signal,
            )
        }
        if (hasEdge) {
            val w = waitingText()
            return ShadePhase(
                kind = "signal",
                side = "open",
                title = if (w.isBlank()) signal else "$signal · $w",
                detail = signal,
            )
        }
        val waitEdge = waitingText("edge")
        val title = if (sideHint.isNotBlank()) {
            "Подготовка к открытию ($sideHint) · $waitEdge"
        } else {
            "Подготовка к открытию · $waitEdge"
        }
        return ShadePhase(kind = "prep", side = "open", title = title, detail = sideHint)
    }

    // Long/Short: spread → уровни S%; иначе Z ±exit
    val needExit = if (pos == "LONG") needExitLong else needExitShort
    val atExit = if (spreadOn) {
        if (pos == "LONG") sOk && curS!! >= exitNarrow
        else sOk && curS!! <= exitWide
    } else if (pos == "LONG") {
        curZ != null && curZ >= -exitN
    } else {
        curZ != null && curZ <= exitN
    }
    val nearExit = needExit != null && needExit <= nearTh
    val hasEdge = signal.startsWith("EXIT")
    val approach = hasEdge || atExit || nearExit
    if (!hardOk || !approach) {
        return ShadePhase(kind = "idle", title = "До выхода ещё далеко")
    }
    if (hasEdge && softWait.isEmpty()) {
        return ShadePhase(
            kind = "ready",
            side = "close",
            title = "Готово к AUTO: $signal",
            detail = signal,
        )
    }
    if (hasEdge) {
        val w = waitingText()
        return ShadePhase(
            kind = "signal",
            side = "close",
            title = if (w.isBlank()) signal else "$signal · $w",
            detail = signal,
        )
    }
    return ShadePhase(
        kind = "prep",
        side = "close",
        title = "Подготовка к закрытию · ${waitingText("edge")}",
    )
}

private fun determineSpreadLevelSignal(
    prevS: Double?,
    curS: Double?,
    pos: String,
    enterW: Double,
    exitW: Double,
    enterN: Double,
    exitN: Double,
): String {
    if (prevS == null || curS == null || !prevS.isFinite() || !curS.isFinite()) return "NONE"
    return when (pos) {
        "FLAT" -> when {
            prevS < enterW && curS >= enterW && curS > 5.5 -> "ENTER_SHORT"
            prevS > enterN && curS <= enterN && curS < 3.5 -> "ENTER_LONG"
            else -> "NONE"
        }
        "LONG" -> if (prevS < exitN && curS >= exitN) "EXIT_LONG" else "NONE"
        "SHORT" -> if (prevS > exitW && curS <= exitW) "EXIT_SHORT" else "NONE"
        else -> "NONE"
    }
}

private fun determineZSignal(
    prevZ: Double?,
    curZ: Double?,
    pos: String,
    entry: Double,
    exitZ: Double,
): String {
    if (prevZ == null || curZ == null) return "NONE"
    return when (pos) {
        "FLAT" -> when {
            prevZ > -entry && curZ <= -entry -> "ENTER_LONG"
            prevZ < entry && curZ >= entry -> "ENTER_SHORT"
            else -> "NONE"
        }
        "LONG" -> if (prevZ < -exitZ && curZ >= -exitZ) "EXIT_LONG" else "NONE"
        "SHORT" -> if (prevZ > exitZ && curZ <= exitZ) "EXIT_SHORT" else "NONE"
        else -> "NONE"
    }
}

private fun barSpread(bar: JSONObject?): Double? {
    if (bar == null) return null
    return when {
        bar.has("spread") && !bar.isNull("spread") -> bar.optDouble("spread")
        bar.has("spreadPercent") && !bar.isNull("spreadPercent") -> bar.optDouble("spreadPercent")
        else -> null
    }?.takeIf { it.isFinite() }
}

private fun barZ(bar: JSONObject?): Double? {
    if (bar == null) return null
    return when {
        bar.has("z") && !bar.isNull("z") -> bar.optDouble("z")
        bar.has("zScore") && !bar.isNull("zScore") -> bar.optDouble("zScore")
        else -> null
    }?.takeIf { it.isFinite() }
}

private fun numOr(
    settings: JSONObject,
    settingsKey: String,
    levels: JSONObject?,
    levelsKey: String,
    default: Double,
): Double {
    if (settings.has(settingsKey) && !settings.isNull(settingsKey)) {
        val v = settings.optDouble(settingsKey)
        if (v.isFinite()) return v
    }
    if (levels != null && levels.has(levelsKey) && !levels.isNull(levelsKey)) {
        val v = levels.optDouble(levelsKey)
        if (v.isFinite()) return v
    }
    return default
}

internal fun isTqbrSessionNow(now: ZonedDateTime = ZonedDateTime.now(moexZoneId)): Boolean {
    when (now.dayOfWeek) {
        DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> return false
        else -> Unit
    }
    val mins = now.hour * 60 + now.minute
    return mins >= 7 * 60 && mins < 23 * 60 + 50
}

internal fun isTqbrSessionBarLabel(tradeDate: String): Boolean {
    val s = tradeDate.replace('T', ' ').trim()
    if (s.length < 16) return false
    val m = Regex("""^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})""").find(s) ?: return false
    val (y, mo, d, hh, mm) = m.destructured
    val date = runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull() ?: return false
    when (date.dayOfWeek) {
        DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> return false
        else -> Unit
    }
    val mins = hh.toInt() * 60 + mm.toInt()
    return mins >= 7 * 60 && mins < 23 * 60 + 50
}
