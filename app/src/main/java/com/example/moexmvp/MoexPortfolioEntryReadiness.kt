package com.example.moexmvp

import android.content.Context
import java.util.Locale
import kotlin.math.abs

internal enum class PortfolioEntryReadinessStatus {
    Ok,
    Blocked,
    Warning,
    Info,
}

internal data class PortfolioEntryReadinessItem(
    val status: PortfolioEntryReadinessStatus,
    val label: String,
    val detail: String? = null,
)

internal data class PortfolioEntryReadinessReport(
    val items: List<PortfolioEntryReadinessItem>,
    val summary: String,
    val primaryBlocker: String?,
)

internal data class PortfolioEntryReadinessInput(
    val points: List<DataPoint>,
    val position: ZStrategyPosition,
    val thresholds: DynamicThresholds,
    val monitorEnabled: Boolean,
    val networkAvailable: Boolean,
    val autoExecute: Boolean,
    val execUiState: SandboxExecUiState,
    val dailySignalLimit: DailySignalLimit,
    val hasPendingVirtualTrade: Boolean,
    /** Последний сигнал на паре prev→last уже в dedup (UI/фон обработали). */
    val lastBarSignalConsumed: Boolean = false,
    val nowMillis: Long = System.currentTimeMillis(),
)

internal fun buildPortfolioEntryReadiness(input: PortfolioEntryReadinessInput): PortfolioEntryReadinessReport {
    val items = mutableListOf<PortfolioEntryReadinessItem>()
    val blockers = mutableListOf<String>()

    fun add(item: PortfolioEntryReadinessItem) {
        items += item
        if (item.status == PortfolioEntryReadinessStatus.Blocked) {
            blockers += item.detail?.let { "${item.label}: $it" } ?: item.label
        }
    }

    add(
        PortfolioEntryReadinessItem(
            status = if (input.monitorEnabled) PortfolioEntryReadinessStatus.Ok else PortfolioEntryReadinessStatus.Warning,
            label = "Фоновый монитор",
            detail = if (input.monitorEnabled) {
                "ON — опрос MOEX каждые ~15 с"
            } else {
                "OFF — сигналы только при открытом приложении (refresh «Портфель» / «Рынок»)"
            },
        )
    )

    add(
        PortfolioEntryReadinessItem(
            status = if (input.networkAvailable) PortfolioEntryReadinessStatus.Ok else PortfolioEntryReadinessStatus.Blocked,
            label = "Интернет",
            detail = if (input.networkAvailable) "Есть маршрут в сеть" else "Нет сети — MOEX недоступен",
        )
    )

    val points = input.points
    val barCount = points.size
    val lastBar = points.lastOrNull()
    val prevBar = points.getOrNull(barCount - 2)
    val stale = points.isEmpty() || portfolio15mSeriesIntradayStale(points, input.nowMillis)
    val lastBarAgeMin = lastBar?.let { (input.nowMillis - it.timestampMillis) / 60_000L }
    val sessionOpen = isMoexMainSessionLikelyOpen(
        java.time.ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(input.nowMillis),
            moexZoneId,
        ),
    )

    add(
        PortfolioEntryReadinessItem(
            status = when {
                points.isEmpty() -> PortfolioEntryReadinessStatus.Blocked
                stale -> PortfolioEntryReadinessStatus.Blocked
                else -> PortfolioEntryReadinessStatus.Ok
            },
            label = "15м MOEX свежие",
            detail = when {
                points.isEmpty() -> "Нет 15м баров — нажмите «Обновить»"
                stale -> {
                    val barLabel = lastBar?.tradeDate ?: "?"
                    if (!sessionOpen) {
                        "Последний бар $barLabel — вне сессии, нужна догрузка"
                    } else {
                        "Последний бар $barLabel (${lastBarAgeMin ?: "?"} мин назад) — нужна догрузка"
                    }
                }
                else -> {
                    val barLabel = lastBar?.tradeDate ?: "?"
                    if (!sessionOpen) {
                        "Последний бар $barLabel (сессия закрыта)"
                    } else {
                        "Последний бар $barLabel (${lastBarAgeMin ?: "?"} мин назад)"
                    }
                }
            },
        )
    )

    val consecutive = prevBar != null && lastBar != null && isConsecutiveM15Bar(prevBar, lastBar)
    add(
        PortfolioEntryReadinessItem(
            status = when {
                prevBar == null || lastBar == null -> PortfolioEntryReadinessStatus.Info
                consecutive -> PortfolioEntryReadinessStatus.Ok
                else -> PortfolioEntryReadinessStatus.Blocked
            },
            label = "Соседние 15м бары (+15 мин)",
            detail = when {
                prevBar == null || lastBar == null -> "Нужно ≥ 2 баров"
                consecutive -> "${prevBar.tradeDate} → ${lastBar.tradeDate}"
                else -> "${prevBar.tradeDate} → ${lastBar.tradeDate} (пропуск — пересечение не считается)"
            },
        )
    )

    val curZ = lastBar?.zScore
    val prevZ = prevBar?.zScore
    val zLabel = curZ?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
    val prevZLabel = prevZ?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
    val entryThr = input.thresholds.entry
    val exitThr = input.thresholds.exit

    when (input.position) {
        ZStrategyPosition.Flat -> {
            add(
                PortfolioEntryReadinessItem(
                    status = PortfolioEntryReadinessStatus.Ok,
                    label = "Позиция Flat — ждём вход",
                    detail = "Long при Z ↓ через −$entryThr; Short при Z ↑ через +$entryThr",
                )
            )
            addCrossingItem(
                add = ::add,
                position = input.position,
                thresholds = input.thresholds,
                prevZ = prevZ,
                curZ = curZ,
                consecutive = consecutive,
                prevZLabel = prevZLabel,
                zLabel = zLabel,
            )
        }

        ZStrategyPosition.Long -> {
            add(
                PortfolioEntryReadinessItem(
                    status = PortfolioEntryReadinessStatus.Info,
                    label = "Позиция Long — ждём выход",
                    detail = "Выход при Z ↑ через −$exitThr (prevZ < −$exitThr → curZ ≥ −$exitThr)",
                )
            )
            addCrossingItem(
                add = ::add,
                position = input.position,
                thresholds = input.thresholds,
                prevZ = prevZ,
                curZ = curZ,
                consecutive = consecutive,
                prevZLabel = prevZLabel,
                zLabel = zLabel,
            )
        }

        ZStrategyPosition.Short -> {
            add(
                PortfolioEntryReadinessItem(
                    status = PortfolioEntryReadinessStatus.Info,
                    label = "Позиция Short — ждём выход",
                    detail = "Выход при Z ↓ через +$exitThr (prevZ > +$exitThr → curZ ≤ +$exitThr)",
                )
            )
            addCrossingItem(
                add = ::add,
                position = input.position,
                thresholds = input.thresholds,
                prevZ = prevZ,
                curZ = curZ,
                consecutive = consecutive,
                prevZLabel = prevZLabel,
                zLabel = zLabel,
            )
        }
    }

    val signalOnLast = if (points.size >= 2 && consecutive) {
        zStrategySignalOnLast15mBar(points, input.position, input.thresholds)
    } else {
        ZStrategySignal.None
    }

    if (signalOnLast != ZStrategySignal.None && lastBar != null) {
        add(
            PortfolioEntryReadinessItem(
                status = if (input.lastBarSignalConsumed) {
                    PortfolioEntryReadinessStatus.Warning
                } else {
                    PortfolioEntryReadinessStatus.Ok
                },
                label = "Пересечение на последней паре",
                detail = buildString {
                    append("${signalOnLast.name}: prevZ=$prevZLabel → Z=$zLabel")
                    if (input.lastBarSignalConsumed) {
                        append(" · уже в журнале/dedup")
                    }
                },
            )
        )
    }

    val pushLimitReached = input.dailySignalLimit.sentCount >= DAILY_SIGNAL_MAX_PER_DAY
    add(
        PortfolioEntryReadinessItem(
            status = if (pushLimitReached) PortfolioEntryReadinessStatus.Warning else PortfolioEntryReadinessStatus.Ok,
            label = "Лимит push за день",
            detail = "${input.dailySignalLimit.sentCount}/$DAILY_SIGNAL_MAX_PER_DAY" +
                if (pushLimitReached) " (push не шлёт, авто-вход всё ещё возможен)" else "",
        )
    )

    if (input.autoExecute) {
        val credOk = input.execUiState == SandboxExecUiState.Ready
        add(
            PortfolioEntryReadinessItem(
                status = if (credOk) PortfolioEntryReadinessStatus.Ok else PortfolioEntryReadinessStatus.Blocked,
                label = "Авто-исполнение",
                detail = when (input.execUiState) {
                    SandboxExecUiState.Ready -> "Токен и счёт заданы — 2 заявки сразу после сигнала"
                    SandboxExecUiState.MissingCredentials -> "Нет токена или счёта — заявки не отправятся"
                    SandboxExecUiState.Off -> "Исполнение выключено"
                },
            )
        )
    } else {
        add(
            PortfolioEntryReadinessItem(
                status = if (input.hasPendingVirtualTrade) {
                    PortfolioEntryReadinessStatus.Warning
                } else {
                    PortfolioEntryReadinessStatus.Info
                },
                label = "Ручной режим («Принять»)",
                detail = if (input.hasPendingVirtualTrade) {
                    "Есть карточка «Принять» — подтвердите или отклоните"
                } else {
                    "При сигнале появится карточка «Принять» / «Отклонить»"
                },
            )
        )
    }

    val summary = formatPortfolioEntryReadinessSummary(
        position = input.position,
        signalOnLast = signalOnLast,
        blockers = blockers,
        curZ = curZ,
        prevZ = prevZ,
        thresholds = input.thresholds,
        consecutive = consecutive,
    )

    return PortfolioEntryReadinessReport(
        items = items,
        summary = summary,
        primaryBlocker = blockers.firstOrNull(),
    )
}

private fun addCrossingItem(
    add: (PortfolioEntryReadinessItem) -> Unit,
    position: ZStrategyPosition,
    thresholds: DynamicThresholds,
    prevZ: Double?,
    curZ: Double?,
    consecutive: Boolean,
    prevZLabel: String,
    zLabel: String,
) {
    if (prevZ == null || curZ == null) {
        add(
            PortfolioEntryReadinessItem(
                status = PortfolioEntryReadinessStatus.Info,
                label = "Z-score",
                detail = "Z=$zLabel (prev недоступен)",
            )
        )
        return
    }

    if (!consecutive) {
        add(
            PortfolioEntryReadinessItem(
                status = PortfolioEntryReadinessStatus.Blocked,
                label = "Пересечение Z",
                detail = "prevZ=$prevZLabel → Z=$zLabel — бары не соседние, сигнал не формируется",
            )
        )
        return
    }

    val signal = determineZStrategySignal(prevZ, curZ, position, thresholds)
    if (signal != ZStrategySignal.None) return

    val detail = describeNoCrossingReason(position, prevZ, curZ, thresholds)
    add(
        PortfolioEntryReadinessItem(
            status = PortfolioEntryReadinessStatus.Blocked,
            label = "Пересечение Z",
            detail = "prevZ=$prevZLabel → Z=$zLabel — $detail",
        )
    )
}

internal fun describeNoCrossingReason(
    position: ZStrategyPosition,
    prevZ: Double,
    curZ: Double,
    thresholds: DynamicThresholds,
): String {
    val entry = thresholds.entry
    val exit = thresholds.exit
    return when (position) {
        ZStrategyPosition.Flat -> {
            val longNeed = -entry
            val shortNeed = entry
            when {
                curZ > longNeed && curZ < shortNeed ->
                    "Z в нейтральной зоне (между −$entry и +$entry)"
                curZ <= longNeed && prevZ <= longNeed -> {
                    val gap = abs(curZ - longNeed)
                    "Z уже ниже порога LONG (≤ −$entry), пересечения не было; до порога с prev ${fmtGap(gap)}"
                }
                curZ >= shortNeed && prevZ >= shortNeed -> {
                    val gap = abs(curZ - shortNeed)
                    "Z уже выше порога SHORT (≥ +$entry), пересечения не было; до порога с prev ${fmtGap(gap)}"
                }
                curZ <= longNeed && prevZ > longNeed ->
                    "ожидалось EnterLong — проверьте соседность баров"
                curZ >= shortNeed && prevZ < shortNeed ->
                    "ожидалось EnterShort — проверьте соседность баров"
                curZ <= longNeed -> {
                    val gap = abs(curZ - longNeed)
                    "до LONG −$entry не хватает ${fmtGap(gap)} (Z=$curZ)"
                }
                curZ >= shortNeed -> {
                    val gap = abs(curZ - shortNeed)
                    "до SHORT +$entry не хватает ${fmtGap(gap)} (Z=$curZ)"
                }
                else -> "нет пересечения порога входа"
            }
        }

        ZStrategyPosition.Long -> {
            val exitLevel = -exit
            when {
                curZ < exitLevel ->
                    "Z=$curZ ещё ниже −$exit (нужен подъём через −$exit для ExitLong)"
                curZ >= exitLevel && prevZ >= exitLevel ->
                    "Z уже выше −$exit, пересечения выхода не было (prevZ=$prevZ)"
                else ->
                    "нет пересечения порога выхода LONG (−$exit)"
            }
        }

        ZStrategyPosition.Short -> {
            val exitLevel = exit
            when {
                curZ > exitLevel ->
                    "Z=$curZ ещё выше +$exit (нужно снижение через +$exit для ExitShort)"
                curZ <= exitLevel && prevZ <= exitLevel ->
                    "Z уже ниже +$exit, пересечения выхода не было (prevZ=$prevZ)"
                else ->
                    "нет пересечения порога выхода SHORT (+$exit)"
            }
        }
    }
}

private fun fmtGap(gap: Double): String =
    String.format(Locale.US, "%.2f", gap)

internal fun formatPortfolioEntryReadinessSummary(
    position: ZStrategyPosition,
    signalOnLast: ZStrategySignal,
    blockers: List<String>,
    curZ: Double?,
    prevZ: Double?,
    thresholds: DynamicThresholds,
    consecutive: Boolean,
): String {
    if (blockers.isNotEmpty()) {
        return blockers.first()
    }
    if (signalOnLast != ZStrategySignal.None) {
        return "На последней паре баров: ${signalOnLast.name}"
    }
    if (curZ != null && prevZ != null && consecutive) {
        return describeNoCrossingReason(position, prevZ, curZ, thresholds)
    }
    return when (position) {
        ZStrategyPosition.Flat -> "Ждём пересечения порога входа ±${thresholds.entry}"
        ZStrategyPosition.Long -> "Ждём пересечения выхода LONG (−${thresholds.exit})"
        ZStrategyPosition.Short -> "Ждём пересечения выхода SHORT (+${thresholds.exit})"
    }
}

internal fun buildPortfolioEntryReadinessFromContext(
    context: Context,
    points: List<DataPoint>,
    position: ZStrategyPosition,
    thresholds: DynamicThresholds,
    autoExecute: Boolean,
    executionMode: TinkoffExecutionMode,
    dailySignalLimit: DailySignalLimit,
    hasPendingVirtualTrade: Boolean,
): PortfolioEntryReadinessReport {
    val prevBar = points.getOrNull(points.size - 2)
    val lastBar = points.lastOrNull()
    val consecutive = prevBar != null && lastBar != null && isConsecutiveM15Bar(prevBar, lastBar)
    val signalOnLast = if (points.size >= 2 && consecutive) {
        zStrategySignalOnLast15mBar(points, position, thresholds)
    } else {
        ZStrategySignal.None
    }
    val consumed = lastBar != null &&
        signalOnLast != ZStrategySignal.None &&
        is15mStrategySignalEdgeConsumed(context, lastBar.timestampMillis, signalOnLast)
    return buildPortfolioEntryReadiness(
        PortfolioEntryReadinessInput(
            points = points,
            position = position,
            thresholds = thresholds,
            monitorEnabled = SignalForegroundService.isBackgroundMonitorEnabled(context),
            networkAvailable = isMoexNetworkAvailable(context),
            autoExecute = autoExecute,
            execUiState = TinkoffSandboxStorage.resolveExecUiState(context, executionMode),
            dailySignalLimit = dailySignalLimit,
            hasPendingVirtualTrade = hasPendingVirtualTrade,
            lastBarSignalConsumed = consumed,
        )
    )
}
