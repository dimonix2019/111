package com.example.moexmvp

import android.content.ComponentCallbacks2

internal enum class MarketsRefreshPolicy {
    /** Явное действие пользователя на вкладке «Рынок». */
    UserInitiated,
    /** Фоновый опрос (5–15 с). */
    AutoPoll,
}

internal object MoexMemoryPressure {
    @Volatile
    var lastLevel: Int = 0
        private set

    private var trimHandler: ((Int) -> Unit)? = null

    fun registerTrimHandler(handler: (Int) -> Unit) {
        trimHandler = handler
    }

    fun unregisterTrimHandler() {
        trimHandler = null
    }

    fun onTrimMemory(level: Int) {
        lastLevel = level
        trimHandler?.invoke(level)
    }

    fun shouldPauseAutoRefresh(level: Int = lastLevel): Boolean =
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW

    fun autoPollIntervalMs(level: Int = lastLevel): Long = when {
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> 0L
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> 15_000L
        else -> FIXED_REALTIME_INTERVAL_MS
    }
}

internal fun mayRunMarketsRefresh(
    tab: MainTab,
    activityResumed: Boolean,
    realtimeEnabled: Boolean,
    memoryPressureLevel: Int,
    policy: MarketsRefreshPolicy,
): Boolean {
    if (tab != MainTab.Markets) return false
    return when (policy) {
        MarketsRefreshPolicy.UserInitiated -> activityResumed
        MarketsRefreshPolicy.AutoPoll ->
            activityResumed &&
                realtimeEnabled &&
                !MoexMemoryPressure.shouldPauseAutoRefresh(memoryPressureLevel)
    }
}

internal fun MoexScreenState.mayRefreshMarkets(policy: MarketsRefreshPolicy): Boolean =
    mayRunMarketsRefresh(
        tab = selectedTab,
        activityResumed = activityResumed,
        realtimeEnabled = realtimeEnabled,
        memoryPressureLevel = memoryPressureLevel,
        policy = policy,
    )

internal fun MoexScreenState.trimMemoryCaches(level: Int) {
    memoryPressureLevel = level
    if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return

    if (selectedTab != MainTab.StrategyTest) {
        clearStrategyTestSession()
    } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
        strategyTestM15ChartTail = emptyList()
    }
    if (selectedTab != MainTab.Markets && selectedTab != MainTab.Portfolio) {
        if (marketsM15SessionCache.size >= 2) {
            marketsM15Points = emptyList()
        }
    }
    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
        marketsZStrategyTapMetrics = null
        robustCandidate = null
    }
}
