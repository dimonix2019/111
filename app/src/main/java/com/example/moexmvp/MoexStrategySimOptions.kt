package com.example.moexmvp

import java.time.LocalTime

/**
 * Доп. правила симуляции Z-стратегии (бэктест / «Тест страт.»).
 * По умолчанию все выключены — поведение как раньше.
 */
internal data class ZStrategySimOptions(
    /** Добавка номинала при углублении |Z| (0 = без пирамидинга). */
    val pyramidAddNotionalRub: Double = 0.0,
    /** Long: Z ≤ −depth; Short: Z ≥ +depth — одна добавка за круг. */
    val pyramidZDepth: Double = 1.0,
    val skipTatnReportDays: Boolean = false,
    /** Закрыть открытую позицию на баре с временем ≥ cutoff (МСК). */
    val closeBeforeClearingMsk: LocalTime? = null,
    /** В день публикации отчёта Tatneft — принудительный выход. */
    val closeOpenOnReportPublicationDay: Boolean = true,
    /** Slippage в п.п. спреда на входе/выходе (parity с strategy-web). */
    val slippageSpreadPts: Double = 0.0,
    /** Стоп по убытку в п.п. спреда (0 = выкл.). */
    val maxLossSpreadPts: Double = 0.0,
    /** Стоп по убытку в ₽ после комиссии выхода и овернайта (0 = выкл.). */
    val maxLossRub: Double = 0.0,
    /** Не входить, если спред % ниже порога (0 = выкл.). */
    val minSpreadPct: Double = 0.0,
    /** Не входить, если спред % выше порога (0 = выкл.). */
    val maxSpreadPct: Double = 0.0,
    /** Доп. буфер |Z| поверх entry для входа (0 = только пересечение entry). */
    val entryZBuffer: Double = 0.0,
    /** Остановить новые входы при просадке эквити ≥ ₽ (0 = выкл.). */
    val maxDrawdownHaltRub: Double = 0.0,
    /** Остановить новые входы при просадке эквити ≥ % от пика (0 = выкл.). */
    val maxDrawdownHaltPct: Double = 0.0,
    /** Time stop: закрыть позицию после N часов удержания (0 = выкл.). */
    val forcedTimeStopHours: Double = 0.0,
    /** Z-stop: |Z − Z_входа| в сторону от mean-revert > N → закрыть (0 = выкл.). */
    val forcedZStopDeviation: Double = 0.0,
    /** Закрыть, если удержание ≥ N ч и MTM < 0 (0 = выкл.). */
    val forcedHoldHoursIfLosing: Double = 0.0,
    /** К [forcedHoldHoursIfLosing]: только если сделка ни разу не была в плюсе по MTM. */
    val forcedHoldRequireNeverGreen: Boolean = false,
) {
    val hasPyramiding: Boolean get() = pyramidAddNotionalRub > 0.0
    val hasForcedExits: Boolean
        get() = forcedTimeStopHours > 0.0 ||
            forcedZStopDeviation > 0.0 ||
            forcedHoldHoursIfLosing > 0.0
    val hasProtections: Boolean
        get() = slippageSpreadPts > 0.0 ||
            maxLossSpreadPts > 0.0 ||
            maxLossRub > 0.0 ||
            minSpreadPct > 0.0 ||
            maxSpreadPct > 0.0 ||
            entryZBuffer > 0.0 ||
            maxDrawdownHaltRub > 0.0 ||
            maxDrawdownHaltPct > 0.0 ||
            hasForcedExits
}

internal fun describeSimOptions(options: ZStrategySimOptions): String {
    val parts = mutableListOf<String>()
    if (options.hasPyramiding) {
        parts += "пир.+${options.pyramidAddNotionalRub.toInt()}k @ |Z|≥${options.pyramidZDepth}"
    }
    if (options.skipTatnReportDays) {
        parts += "без отчёт. дн. TATN"
    }
    if (options.closeBeforeClearingMsk != null) {
        parts += "выход до ${options.closeBeforeClearingMsk} МСК"
    }
    if (options.slippageSpreadPts > 0.0) {
        parts += "slip ${options.slippageSpreadPts}п"
    }
    if (options.maxLossSpreadPts > 0.0) {
        parts += "SL ${options.maxLossSpreadPts}п"
    }
    if (options.maxLossRub > 0.0) {
        parts += "SL ${options.maxLossRub.toInt()}₽"
    }
    if (options.minSpreadPct > 0.0 || options.maxSpreadPct > 0.0) {
        parts += "спред ${options.minSpreadPct}–${options.maxSpreadPct}%"
    }
    if (options.entryZBuffer > 0.0) {
        parts += "Z+${options.entryZBuffer}"
    }
    if (options.maxDrawdownHaltRub > 0.0) {
        parts += "halt DD ${options.maxDrawdownHaltRub.toInt()}₽"
    }
    if (options.maxDrawdownHaltPct > 0.0) {
        parts += "halt DD ${options.maxDrawdownHaltPct}%"
    }
    if (options.forcedTimeStopHours > 0.0) {
        parts += "T${options.forcedTimeStopHours.toInt()}ч"
    }
    if (options.forcedZStopDeviation > 0.0) {
        parts += "Z±${options.forcedZStopDeviation}"
    }
    if (options.forcedHoldHoursIfLosing > 0.0) {
        parts += if (options.forcedHoldRequireNeverGreen) {
            "H${options.forcedHoldHoursIfLosing.toInt()}ч−&never+"
        } else {
            "H${options.forcedHoldHoursIfLosing.toInt()}ч−"
        }
    }
    return if (parts.isEmpty()) "" else " · " + parts.joinToString(" · ")
}
