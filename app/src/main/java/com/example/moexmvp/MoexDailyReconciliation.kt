package com.example.moexmvp

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

private val reconciliationZone: ZoneId = ZoneId.of("Europe/Moscow")

internal fun chartLabelToLocalDate(label: String): LocalDate? {
    val trimmed = label.trim()
    if (trimmed.length >= 10) {
        runCatching { return LocalDate.parse(trimmed.take(10)) }.getOrNull()
    }
    return runCatching {
        java.time.LocalDateTime.parse(trimmed, portfolio15mLabelFormatter).toLocalDate()
    }.getOrNull()
}

internal data class DailyReconciliationRow(
    val headline: String,
    val detail: String,
    val isOk: Boolean
)

internal data class DailyPortfolioReconciliation(
    val day: LocalDate,
    val journalEnters: Int,
    val journalExits: Int,
    val confirmedClosedOnDay: Int,
    val simClosedOnDay: Int,
    val confirmedPnlRubOnDay: Double,
    val simPnlRubOnDay: Double,
    val journalPositionAtDayOpen: ZStrategyPosition,
    val simPositionAtDayOpen: ZStrategyPosition,
    val simSignalsToday: Int,
    val simThresholdsNote: String,
    val rows: List<DailyReconciliationRow>
)

/**
 * Сверка за календарный день (МСК): журнал сигналов ↔ портфель (демо‑исполнения) ↔ симуляция «Тест страт.» на 15м.
 */
internal fun buildDailyPortfolioReconciliation(
    day: LocalDate,
    journalEvents: List<StrategySignalEvent>,
    confirmed: PortfolioMetrics?,
    simulation: PortfolioMetrics?,
    simEntryThreshold: Double,
    simExitThreshold: Double,
    journalPositionAtDayOpen: ZStrategyPosition = ZStrategyPosition.Flat,
    simPositionAtDayOpen: ZStrategyPosition = ZStrategyPosition.Flat,
    simSignalsToday: Int = 0,
    simExitMode: ZStrategyExitMode = ZStrategyExitMode.FixedThreshold,
    simZPeakTrailZ: Double = DEFAULT_STRATEGY_TEST_Z_PEAK_TRAIL
): DailyPortfolioReconciliation {
    val journalToday = journalEvents.filter { eventOnDay(it, day) }
    val enters = journalToday.count {
        it.signalType == StrategySignalType.EnterLong || it.signalType == StrategySignalType.EnterShort
    }
    val exits = journalToday.count {
        it.signalType == StrategySignalType.ExitLong || it.signalType == StrategySignalType.ExitShort
    }

    val confirmedClosed = confirmed?.closedTrades?.filter { tradeClosedOnDay(it, day) }.orEmpty()
    val simClosed = simulation?.closedTrades?.filter { tradeClosedOnDay(it, day) }.orEmpty()

    val confirmedPnl = confirmedClosed.sumOf { it.pnlRubApprox }
    val simPnl = simClosed.sumOf { it.pnlRubApprox }

    val rows = mutableListOf<DailyReconciliationRow>()
    val matchedSim = mutableSetOf<Int>()
    val exitRuleText = when (simExitMode) {
        ZStrategyExitMode.FixedThreshold -> "выход ±${fmt(simExitThreshold)}"
        ZStrategyExitMode.ZPeakTrailing -> "трейл от пика Z ${fmt(simZPeakTrailZ)}"
        ZStrategyExitMode.OppositeExtreme -> "противоп. экстремум ±${fmt(simExitThreshold)}"
    }

    if (journalPositionAtDayOpen != simPositionAtDayOpen) {
        rows += DailyReconciliationRow(
            headline = "Позиция на открытие дня: журнал ${dirRu(journalPositionAtDayOpen)} · sim ${dirRu(simPositionAtDayOpen)}",
            detail = "Sim replay 255д до первого 15м бара $day. Если журнал ≠ sim — расхождение до сегодня (пропущенный сигнал, другие пороги при записи, старый баг batch/consume).",
            isOk = false
        )
    } else if (journalPositionAtDayOpen != ZStrategyPosition.Flat) {
        rows += DailyReconciliationRow(
            headline = "На открытие дня: ${dirRu(journalPositionAtDayOpen)} (журнал = sim)",
            detail = "Carry-in совпадает — сегодняшние сигналы сравниваются с одной стартовой позиции.",
            isOk = true
        )
    }

    val journalSignalsToday = enters + exits
    if (journalSignalsToday != simSignalsToday) {
        rows += DailyReconciliationRow(
            headline = "Сигналов за день: журнал $journalSignalsToday · sim $simSignalsToday",
            detail = "Sim — пересечения Z на 15м барах $day с позицией ${dirRu(simPositionAtDayOpen)} на открытие. " +
                "Журнал — фактические push/записи. Пороги теста: вход ±${fmt(simEntryThreshold)}, $exitRuleText.",
            isOk = false
        )
    } else if (journalSignalsToday > 0) {
        rows += DailyReconciliationRow(
            headline = "Сигналов за день: $journalSignalsToday (журнал = sim)",
            detail = "Пересечения Z на сегодня совпали с журналом.",
            isOk = true
        )
    }

    fun tradeKey(t: PortfolioClosedTrade): String {
        val d = t.direction.name
        val e = chartLabelToLocalDate(t.entryDate)?.toString() ?: t.entryDate
        val x = chartLabelToLocalDate(t.exitDate)?.toString() ?: t.exitDate
        return "$d|$e|$x"
    }

    confirmedClosed.forEach { c ->
        val key = tradeKey(c)
        val simIdx = simClosed.indexOfFirst { tradeKey(it) == key }
        if (simIdx >= 0) {
            matchedSim += simIdx
            val s = simClosed[simIdx]
            val pnlDiff = abs(c.pnlRubApprox - s.pnlRubApprox)
            if (pnlDiff < 1.0) {
                rows += DailyReconciliationRow(
                    headline = "Совпало: ${dirRu(c.direction)} ${c.entryDate} → ${c.exitDate}",
                    detail = "Подтв. и тест: ${formatRub(c.pnlRubApprox)} · спрэд ${formatSpread(c.pnlSpreadPoints)} п.п.",
                    isOk = true
                )
            } else {
                rows += DailyReconciliationRow(
                    headline = "Круг есть в обоих, PnL разный: ${dirRu(c.direction)}",
                    detail = "Подтв. ${formatRub(c.pnlRubApprox)} · тест ${formatRub(s.pnlRubApprox)} · Δ ${formatRub(pnlDiff)}. " +
                        "Часто из‑за разных порогов Z в тесте vs момент сигнала в журнале или разной привязки к 15м бару.",
                    isOk = false
                )
            }
        } else {
            val simSameDir = simClosed.withIndex().filter { (i, t) ->
                i !in matchedSim && t.direction == c.direction &&
                    chartLabelToLocalDate(t.entryDate) == chartLabelToLocalDate(c.entryDate)
            }
            if (simSameDir.isNotEmpty()) {
                val (i, s) = simSameDir.first()
                matchedSim += i
                rows += DailyReconciliationRow(
                    headline = "Подтв. сделка есть, в тесте другой выход: ${dirRu(c.direction)}",
                    detail = "Подтв. выход ${c.exitDate} · тест выход ${s.exitDate}. Тест сейчас: вход ±${fmt(simEntryThreshold)}, $exitRuleText.",
                    isOk = false
                )
            } else {
                rows += DailyReconciliationRow(
                    headline = "Только портфель (симуляция Z): ${dirRu(c.direction)}",
                    detail = "${c.entryDate} → ${c.exitDate}, ${formatRub(c.pnlRubApprox)}. В «Тест страт.» за день такого круга нет — " +
                        "сверьте пороги (розовые vs степперы теста), режим «Фикс./Капитализация» и нажмите «Обновить» на обеих вкладках.",
                    isOk = false
                )
            }
        }
    }

    simClosed.forEachIndexed { i, s ->
        if (i in matchedSim) return@forEachIndexed
        rows += DailyReconciliationRow(
            headline = "Только тест стратегии: ${dirRu(s.direction)}",
            detail = "${s.entryDate} → ${s.exitDate}, ${formatRub(s.pnlRubApprox)}. В журнале/подтв. нет пары — " +
                "сигнал не записан (кэш рынка, лимит push, BG), или другие пороги при срабатывании.",
            isOk = false
        )
    }

    val journalEnterEvents = journalToday.filter {
        it.signalType == StrategySignalType.EnterLong || it.signalType == StrategySignalType.EnterShort
    }
    val journalExitEvents = journalToday.filter {
        it.signalType == StrategySignalType.ExitLong || it.signalType == StrategySignalType.ExitShort
    }

    if (enters > confirmedClosed.size + (confirmed?.openPosition?.let { 1 } ?: 0)) {
        rows += DailyReconciliationRow(
            headline = "В журнале входов больше, чем закрытых кругов за день",
            detail = "Входов $enters, выходов $exits, закрыто подтв. ${confirmedClosed.size}. Возможны незакрытые входы или вход без выхода до конца дня.",
            isOk = false
        )
    }

    journalEnterEvents.forEach { ev ->
        val hasExit = journalExitEvents.any { exitMatchesEnter(ev, it) }
        if (!hasExit) {
            rows += DailyReconciliationRow(
                headline = "Журнал: вход без выхода за день",
                detail = "${ev.signalType.name} Z=${fmt(ev.zScore)} — позиция могла остаться открытой.",
                isOk = false
            )
        }
    }

    confirmed?.openPosition?.let { open ->
        val entryDay = chartLabelToLocalDate(open.entryDate)
        if (entryDay != null && (entryDay == day || entryDay.isBefore(day))) {
            rows += DailyReconciliationRow(
                headline = "Подтв.: открытая позиция ${dirRu(open.direction)}",
                detail = "Вход ${open.entryDate}, нереализ. ${formatRub(open.unrealizedRubApprox)} — в списке закрытых за день не участвует.",
                isOk = false
            )
        }
    }

    simulation?.openPosition?.let { open ->
        val entryDay = chartLabelToLocalDate(open.entryDate)
        if (entryDay != null && (entryDay == day || entryDay.isBefore(day))) {
            rows += DailyReconciliationRow(
                headline = "Тест: открытая позиция ${dirRu(open.direction)}",
                detail = "Вход ${open.entryDate}, нереализ. ${formatRub(open.unrealizedRubApprox)}.",
                isOk = false
            )
        }
    }

    if (rows.isEmpty() && enters == 0 && confirmedClosed.isEmpty() && simClosed.isEmpty()) {
        rows += DailyReconciliationRow(
            headline = "За день нет сигналов и закрытых сделок",
            detail = "Журнал пуст, подтверждённые и тест за $day без закрытий с датой выхода в этот день.",
            isOk = true
        )
    } else if (rows.none { !it.isOk } && confirmedClosed.isNotEmpty() && simClosed.isNotEmpty()) {
        rows.add(
            0,
            DailyReconciliationRow(
                headline = "Сводка: закрытые за день совпадают",
                detail = "Подтв. ${confirmedClosed.size} · тест ${simClosed.size} · PnL подтв. ${formatRub(confirmedPnl)} · тест ${formatRub(simPnl)}",
                isOk = true
            )
        )
    }

    val thresholdsNote =
        "Sim с открытия дня (255д carry-in). Тест: вход ±${fmt(simEntryThreshold)}, $exitRuleText. " +
            "Подтв.: розовые пороги портфеля на том же ряду."

    return DailyPortfolioReconciliation(
        day = day,
        journalEnters = enters,
        journalExits = exits,
        confirmedClosedOnDay = confirmedClosed.size,
        simClosedOnDay = simClosed.size,
        confirmedPnlRubOnDay = confirmedPnl,
        simPnlRubOnDay = simPnl,
        journalPositionAtDayOpen = journalPositionAtDayOpen,
        simPositionAtDayOpen = simPositionAtDayOpen,
        simSignalsToday = simSignalsToday,
        simThresholdsNote = thresholdsNote,
        rows = rows
    )
}

private fun eventOnDay(e: StrategySignalEvent, day: LocalDate): Boolean =
    Instant.ofEpochMilli(e.timestampMillis).atZone(reconciliationZone).toLocalDate() == day

private fun tradeClosedOnDay(t: PortfolioClosedTrade, day: LocalDate): Boolean =
    chartLabelToLocalDate(t.exitDate) == day

private fun exitMatchesEnter(enter: StrategySignalEvent, exit: StrategySignalEvent): Boolean =
    when (enter.signalType) {
        StrategySignalType.EnterLong -> exit.signalType == StrategySignalType.ExitLong
        StrategySignalType.EnterShort -> exit.signalType == StrategySignalType.ExitShort
        else -> false
    }

private fun dirRu(d: ZStrategyPosition): String = when (d) {
    ZStrategyPosition.Long -> "LONG"
    ZStrategyPosition.Short -> "SHORT"
    ZStrategyPosition.Flat -> "FLAT"
}

internal fun dirRuShort(d: ZStrategyPosition): String = when (d) {
    ZStrategyPosition.Long -> "L"
    ZStrategyPosition.Short -> "S"
    ZStrategyPosition.Flat -> "—"
}

private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)

private fun formatRub(v: Double): String = String.format(Locale.US, "%+.0f ₽", v)

private fun formatSpread(v: Double): String = String.format(Locale.US, "%+.2f", v)
