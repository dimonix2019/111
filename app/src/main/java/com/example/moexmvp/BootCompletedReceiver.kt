package com.example.moexmvp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                MoexDiagnostics.log(context, "lifecycle", "boot_or_update action=${intent.action}")
                scheduleAppUpdateChecks(context)
                scheduleMonitorWatchdog(context)
                if (SignalForegroundService.isBackgroundMonitorEnabled(context)) {
                    SignalForegroundService.start(context)
                    MoexWatchdog.performMonitorWatchdogCheck(context, "boot_or_update")
                }
            }
        }
    }
}
