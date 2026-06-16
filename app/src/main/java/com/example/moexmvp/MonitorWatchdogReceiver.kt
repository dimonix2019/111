package com.example.moexmvp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

internal const val ACTION_MONITOR_WATCHDOG = "com.example.moexmvp.action.MONITOR_WATCHDOG"
private const val WATCHDOG_ALARM_REQUEST_CODE = 12_004
private const val WATCHDOG_ALARM_LOG_TAG = "MoexWatchdog"

/** Периодическая проверка пульса SignalForegroundService (даже когда UI закрыт). */
internal object MonitorWatchdogReceiver {
    fun scheduleNext(context: Context) {
        val app = context.applicationContext
        if (!SignalForegroundService.isBackgroundMonitorEnabled(app)) return
        val alarmManager = app.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_ALARM_INTERVAL_MS
        val pending = PendingIntent.getBroadcast(
            app,
            WATCHDOG_ALARM_REQUEST_CODE,
            Intent(app, MonitorWatchdogAlarmReceiver::class.java).setAction(ACTION_MONITOR_WATCHDOG),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pending,
                )
            } else {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pending,
                )
            }
        } catch (e: SecurityException) {
            Log.w(WATCHDOG_ALARM_LOG_TAG, "scheduleNext failed", e)
        }
    }
}

class MonitorWatchdogAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_MONITOR_WATCHDOG) return
        val pendingResult = goAsync()
        Thread {
            try {
                MoexWatchdog.performMonitorWatchdogCheck(context.applicationContext, "alarm")
            } finally {
                MonitorWatchdogReceiver.scheduleNext(context.applicationContext)
                pendingResult.finish()
            }
        }.start()
    }
}
