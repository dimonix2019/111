package com.example.moexmvp

import android.app.Application

class MoexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installMoexDiagnosticsCrashHandler(applicationContext)
        MoexDiagnostics.log(applicationContext, "lifecycle", "application_onCreate")
        scheduleAppUpdateChecks(applicationContext)
        scheduleMonitorWatchdog(applicationContext)
        // FGS нельзя стартовать из Application.onCreate (Android 12+ / MIUI) — только из Activity.
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MoexMemoryPressure.onTrimMemory(level)
    }
}
