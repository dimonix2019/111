package com.example.moexmvp

import java.time.LocalDate

/**
 * Адаптивные 4 порога по прогнозу режима концентрации Z (10 торг. дней, Markov 1 шаг).
 *
 * NegativeBand / PositiveBand → узкие 0.7/0.5;
 * Neutral / Dispersed → широкие 1.7/1.3.
 *
 * Walk-forward: пороги окна i строятся только по окнам [0..i−1], без look-ahead.
 */
internal data class ZRegimeAdaptiveThresholdConfig(
    val windowTradingDays: Int = 10,
    val minTrainWindows: Int = 6,
    val tightFour: ZStrategyFourThresholds = ZStrategyFourThresholds(0.7, 0.5, 0.7, 0.5),
    val wideFour: ZStrategyFourThresholds = ZStrategyFourThresholds(1.7, 1.3, 1.7, 1.3),
    val warmupFour: ZStrategyFourThresholds = ZStrategyFourThresholds(1.7, 1.3, 1.7, 1.3),
)

internal data class ZRegimeAdaptiveWindowPlan(
    val windowIndex: Int,
    val windowId: String,
    val startDay: LocalDate,
    val endDay: LocalDate,
    val actualRegime: ZConcentrationRegime,
    val predictedRegime: ZConcentrationRegime?,
    val appliedFour: ZStrategyFourThresholds,
)

internal data class ZRegimeAdaptiveSnapshot(
    val config: ZRegimeAdaptiveThresholdConfig,
    val windows: List<ZRegimeWindowStats>,
    val windowPlans: List<ZRegimeAdaptiveWindowPlan>,
    val walkForward: ZRegimeWalkForwardReport,
    val currentWindow: ZRegimeWindowStats?,
    val nextForecast: ZRegimeForecastResult?,
    val liveAppliedFour: ZStrategyFourThresholds,
)

internal fun zRegimeToAdaptiveFour(
    regime: ZConcentrationRegime,
    config: ZRegimeAdaptiveThresholdConfig = ZRegimeAdaptiveThresholdConfig(),
): ZStrategyFourThresholds = when (regime) {
    ZConcentrationRegime.NegativeBand,
    ZConcentrationRegime.PositiveBand,
    -> config.tightFour
    ZConcentrationRegime.Neutral,
    ZConcentrationRegime.Dispersed,
    -> config.wideFour
}

internal fun buildZRegimeAdaptiveWindowPlans(
    points: List<DataPoint>,
    config: ZRegimeAdaptiveThresholdConfig = ZRegimeAdaptiveThresholdConfig(),
): List<ZRegimeAdaptiveWindowPlan>? {
    if (points.isEmpty()) return null
    val windows = buildZRegimeWindows(points, config.windowTradingDays)
    if (windows.isEmpty()) return null
    return windows.mapIndexed { idx, w ->
        val startDay = LocalDate.parse(w.startLabel)
        val endDay = LocalDate.parse(w.endLabel)
        val predicted: ZConcentrationRegime?
        val applied: ZStrategyFourThresholds
        if (idx < config.minTrainWindows) {
            predicted = null
            applied = config.warmupFour
        } else {
            val train = windows.subList(0, idx)
            val matrix = buildZRegimeTransitionMatrix(train)
            val forecast = forecastNextZRegimeFromTransitions(windows[idx - 1].regime, matrix)
            predicted = forecast?.predicted
            applied = if (predicted != null) {
                zRegimeToAdaptiveFour(predicted, config)
            } else {
                config.warmupFour
            }
        }
        ZRegimeAdaptiveWindowPlan(
            windowIndex = idx,
            windowId = w.windowId,
            startDay = startDay,
            endDay = endDay,
            actualRegime = w.regime,
            predictedRegime = predicted,
            appliedFour = applied,
        )
    }
}

internal fun buildDayToZRegimeWindowIndex(
    plans: List<ZRegimeAdaptiveWindowPlan>,
): Map<LocalDate, Int> {
    val out = linkedMapOf<LocalDate, Int>()
    for (plan in plans) {
        var day = plan.startDay
        while (!day.isAfter(plan.endDay)) {
            out[day] = plan.windowIndex
            day = day.plusDays(1)
        }
    }
    return out
}

internal fun buildZRegimeAdaptiveFourThresholdSeries(
    points: List<DataPoint>,
    config: ZRegimeAdaptiveThresholdConfig = ZRegimeAdaptiveThresholdConfig(),
): List<ZStrategyFourThresholds>? {
    val plans = buildZRegimeAdaptiveWindowPlans(points, config) ?: return null
    val dayToWindow = buildDayToZRegimeWindowIndex(plans)
    val fallback = config.warmupFour
    return points.map { p ->
        val day = parseBarLocalDateMsk(p.tradeDate)
        val winIdx = day?.let { dayToWindow[it] }
        if (winIdx == null) fallback else plans[winIdx].appliedFour
    }
}

internal fun resolveZRegimeAdaptiveSnapshot(
    points: List<DataPoint>,
    config: ZRegimeAdaptiveThresholdConfig = ZRegimeAdaptiveThresholdConfig(),
): ZRegimeAdaptiveSnapshot? {
    if (points.size < 40) return null
    val windows = buildZRegimeWindows(points, config.windowTradingDays)
    if (windows.isEmpty()) return null
    val plans = buildZRegimeAdaptiveWindowPlans(points, config) ?: return null
    val walkForward = walkForwardZRegimeForecast(windows, config.minTrainWindows)
    val current = windows.lastOrNull()
    val nextForecast = current?.let { c ->
        val train = windows.dropLast(1)
        if (train.size < 2) null
        else forecastNextZRegimeFromTransitions(c.regime, buildZRegimeTransitionMatrix(train))
    }
    val liveFour = nextForecast?.let { zRegimeToAdaptiveFour(it.predicted, config) }
        ?: plans.lastOrNull()?.appliedFour
        ?: config.warmupFour
    return ZRegimeAdaptiveSnapshot(
        config = config,
        windows = windows,
        windowPlans = plans,
        walkForward = walkForward,
        currentWindow = current,
        nextForecast = nextForecast,
        liveAppliedFour = liveFour,
    )
}

internal fun formatZRegimeAdaptiveSnapshot(snapshot: ZRegimeAdaptiveSnapshot): String = buildString {
    val cfg = snapshot.config
    appendLine("=== Адаптивные пороги по режиму Z ===")
    appendLine(
        "Окно ${cfg.windowTradingDays} торг. дн. · minTrain=${cfg.minTrainWindows} · " +
            "узкие ${cfg.tightFour.entryLong}/${cfg.tightFour.exitLong} · " +
            "широкие ${cfg.wideFour.entryLong}/${cfg.wideFour.exitLong}",
    )
    appendLine()
    appendLine(
        "Walk-forward точность: ${"%.1f".format(snapshot.walkForward.accuracy * 100)}% " +
            "(${snapshot.walkForward.folds.count { it.correct }}/${snapshot.walkForward.folds.size})",
    )
    snapshot.currentWindow?.let { cur ->
        appendLine(
            "Текущее окно ${cur.startLabel}…${cur.endLabel}: ${zRegimeLabelRu(cur.regime)} " +
                "(meanZ=${"%.2f".format(cur.meanZ)})",
        )
    }
    snapshot.nextForecast?.let { fc ->
        appendLine(
            "Прогноз след. окна: ${zRegimeLabelRu(fc.predicted)} (${"%.0f".format(fc.confidence * 100)}%) → " +
                "пороги ${snapshot.liveAppliedFour.entryLong}/${snapshot.liveAppliedFour.exitLong}",
        )
    }
    appendLine()
    appendLine("— План по окнам (прогноз → пороги) —")
    for (plan in snapshot.windowPlans.takeLast(12)) {
        val pred = plan.predictedRegime?.let { zRegimeLabelRu(it) } ?: "warmup"
        val actual = zRegimeLabelRu(plan.actualRegime)
        val four = plan.appliedFour
        appendLine(
            "${plan.windowId} ${plan.startDay}…${plan.endDay}: прогноз=$pred факт=$actual → " +
                "${four.entryLong}/${four.exitLong}",
        )
    }
}
