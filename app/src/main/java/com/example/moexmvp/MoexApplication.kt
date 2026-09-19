package com.example.moexmvp

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import kotlin.math.abs

internal const val PROCESS_EXIT_PACKAGE_REPLACE_WINDOW_MS = 2 * 60_000L

internal fun classifyPreviousProcessExit(
    reason: Int,
    exitTimestampMs: Long,
    packageLastUpdateTimeMs: Long,
): String = when {
    reason == ApplicationExitInfo.REASON_PACKAGE_UPDATED ||
        reason == ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package_replaced"
    reason == ApplicationExitInfo.REASON_OTHER &&
        packageLastUpdateTimeMs > 0L &&
        abs(exitTimestampMs - packageLastUpdateTimeMs) <= PROCESS_EXIT_PACKAGE_REPLACE_WINDOW_MS ->
        "package_replaced_likely"
    else -> "runtime_exit"
}

class MoexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MoexAppHolder.attach(applicationContext)
        installMoexDiagnosticsCrashHandler(applicationContext)
        logLatestProcessExit()
        MoexDiagnostics.log(applicationContext, "lifecycle", "application_onCreate")
        scheduleMonitorWatchdog(applicationContext)
        if (SignalForegroundService.isBackgroundMonitorEnabled(applicationContext)) {
            MoexWatchdog.performMonitorWatchdogCheck(applicationContext, "application_onCreate")
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MoexMemoryPressure.onTrimMemory(level)
    }

    private fun logLatestProcessExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val latest = runCatching {
            getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(packageName, 0, 1)
                ?.firstOrNull()
        }.getOrNull() ?: return
        val packageUpdatedAt = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        val classification = classifyPreviousProcessExit(
            latest.reason,
            latest.timestamp,
            packageUpdatedAt,
        )
        val description = latest.description
            ?.replace(Regex("""[\r\n]+"""), " ")
            ?.take(120)
            .orEmpty()
        MoexDiagnostics.log(
            applicationContext,
            "process_exit",
            "previous class=$classification reason=${processExitReasonName(latest.reason)}(${latest.reason}) " +
                "status=${latest.status} importance=${latest.importance} " +
                "pss=${latest.pss}KB rss=${latest.rss}KB at=${latest.timestamp} " +
                "updatedAt=$packageUpdatedAt description=${description.ifBlank { "—" }}",
            alwaysWriteFile = true,
        )
    }
}

internal fun processExitReasonName(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
    ApplicationExitInfo.REASON_SIGNALED -> "signaled"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
    ApplicationExitInfo.REASON_CRASH -> "crash"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
    ApplicationExitInfo.REASON_ANR -> "anr"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
    ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
    ApplicationExitInfo.REASON_OTHER -> "other"
    ApplicationExitInfo.REASON_FREEZER -> "freezer"
    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package_state_change"
    ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package_updated"
    else -> "unknown"
}
