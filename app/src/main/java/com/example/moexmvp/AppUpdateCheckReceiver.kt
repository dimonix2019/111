package com.example.moexmvp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

internal const val ACTION_APP_UPDATE_CHECK = "com.example.moexmvp.action.APP_UPDATE_CHECK"
private const val APP_UPDATE_ALARM_REQUEST_CODE = 12_003
private const val APP_UPDATE_ALARM_LOG_TAG = "MoexAppUpdate"

/** Периодическая проверка обновлений (даже когда UI закрыт). */
internal fun scheduleAppUpdateChecks(context: Context) {
    val app = context.applicationContext
    val alarmManager = app.getSystemService(AlarmManager::class.java) ?: return
    val triggerAt = SystemClock.elapsedRealtime() + APP_UPDATE_CHECK_INTERVAL_MS
    val pending = PendingIntent.getBroadcast(
        app,
        APP_UPDATE_ALARM_REQUEST_CODE,
        Intent(app, AppUpdateCheckReceiver::class.java).setAction(ACTION_APP_UPDATE_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pending
            )
        } else {
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pending
            )
        }
    } catch (e: SecurityException) {
        Log.w(APP_UPDATE_ALARM_LOG_TAG, "scheduleAppUpdateChecks failed", e)
    }
}

class AppUpdateCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_APP_UPDATE_CHECK) return
        val pendingResult = goAsync()
        Thread {
            try {
                checkRemoteAppUpdateAndNotify(context.applicationContext)
            } finally {
                scheduleAppUpdateChecks(context.applicationContext)
                pendingResult.finish()
            }
        }.start()
    }
}
