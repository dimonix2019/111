package com.example.moexmvp

import android.app.Application

class MoexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installMoexDiagnosticsCrashHandler(applicationContext)
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
}
