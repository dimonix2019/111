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
    val closeOpenOnReportPublicationDay: Boolean = true
) {
    val hasPyramiding: Boolean get() = pyramidAddNotionalRub > 0.0
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
    return if (parts.isEmpty()) "" else " · " + parts.joinToString(" · ")
}
