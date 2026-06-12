package com.example.moexmvp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        MoexDiagnostics.log(context, "lifecycle", "boot_completed")
        scheduleAppUpdateChecks(context)
        if (SignalForegroundService.isBackgroundMonitorEnabled(context)) {
            SignalForegroundService.start(context)
        }
    }
}
