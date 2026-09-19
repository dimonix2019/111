package com.example.moexmvp

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Application
import android.os.Build

class MoexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MoexAppHolder.attach(applicationContext)
        installMoexDiagnosticsCrashHandler(applicationContext)
        logLatestProcessExit()
        MoexDiagnostics.log(applicationContext, "lifecycle", "application_onCreate")
        scheduleAppUpdateChecks(applicationContext)
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
        MoexDiagnostics.log(
            applicationContext,
            "process_exit",
            "previous reason=${processExitReasonName(latest.reason)}(${latest.reason}) " +
                "status=${latest.status} importance=${latest.importance} " +
                "pss=${latest.pss}KB rss=${latest.rss}KB at=${latest.timestamp}",
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
