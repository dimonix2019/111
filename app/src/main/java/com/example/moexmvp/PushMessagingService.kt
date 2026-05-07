package com.example.moexmvp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.Locale

internal const val PUSH_CHANNEL_ID = "moex_push_channel"
internal const val PUSH_TOPIC = "moex_updates"
internal const val PUSH_LOG_TAG = "MoexPush"
private const val PUSH_DEDUP_PREFS_NAME = "moex_push_dedup"
private const val PREF_PUSH_LAST_SIGNATURE = "push_last_signature"
private const val PREF_PUSH_LAST_TIMESTAMP_MS = "push_last_timestamp_ms"
private const val PUSH_DEDUP_WINDOW_MS = 10_000L
private val pushDedupLock = Any()

internal fun createPushNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(
        PUSH_CHANNEL_ID,
        "MOEX Push",
        NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
        description = "Push notifications for MOEX updates"
    }
    val manager = context.getSystemService(NotificationManager::class.java)
    manager?.createNotificationChannel(channel)
}

internal fun showPushNotification(
    context: Context,
    title: String,
    body: String,
    notificationId: Int = System.currentTimeMillis().toInt()
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(PUSH_LOG_TAG, "Skipping notification: POST_NOTIFICATIONS is not granted")
            return
        }
    }
    if (shouldSkipDuplicatePush(context, title, body)) {
        Log.d(PUSH_LOG_TAG, "Skipping duplicate notification: $title | $body")
        return
    }

    createPushNotificationChannel(context)
    val intent = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    val pendingIntent = PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(context, PUSH_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(body)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(pendingIntent)
        .build()

    NotificationManagerCompat.from(context).notify(notificationId, notification)
}

private fun shouldSkipDuplicatePush(context: Context, title: String, body: String): Boolean {
    val signature = "$title|$body"
    val nowMs = System.currentTimeMillis()
    synchronized(pushDedupLock) {
        val prefs = context.getSharedPreferences(PUSH_DEDUP_PREFS_NAME, Context.MODE_PRIVATE)
        val lastSignature = prefs.getString(PREF_PUSH_LAST_SIGNATURE, null)
        val lastTimestampMs = prefs.getLong(PREF_PUSH_LAST_TIMESTAMP_MS, 0L)
        val isDuplicate = signature == lastSignature && (nowMs - lastTimestampMs) in 0 until PUSH_DEDUP_WINDOW_MS
        if (!isDuplicate) {
            prefs.edit()
                .putString(PREF_PUSH_LAST_SIGNATURE, signature)
                .putLong(PREF_PUSH_LAST_TIMESTAMP_MS, nowMs)
                .apply()
        }
        return isDuplicate
    }
}

internal fun showSpreadCrossPushNotification(context: Context, spreadPercent: Double) {
    val body = String.format(Locale.US, "Spread crossed above 5%%: %.2f%%", spreadPercent)
    showPushNotification(
        context = context,
        title = "MOEX alert",
        body = body
    )
}

internal fun showZScoreCrossPushNotification(
    context: Context,
    zScore: Double,
    level: Double,
    direction: String
) {
    val body = String.format(
        Locale.US,
        "Z-score crossed %.1f %s: %.2f",
        level,
        direction,
        zScore
    )
    showPushNotification(
        context = context,
        title = "MOEX Z-score alert",
        body = body
    )
}

internal fun showZStrategySignalPushNotification(
    context: Context,
    title: String,
    body: String
) {
    showPushNotification(
        context = context,
        title = title,
        body = body
    )
}

internal fun showDynamicZThresholdsPushNotification(
    context: Context,
    entry: Double,
    exit: Double,
    dateText: String
) {
    val body = String.format(
        Locale.US,
        "Daily Z thresholds %s: entry %.1f / exit %.1f",
        dateText,
        entry,
        exit
    )
    showPushNotification(
        context = context,
        title = "MOEX Z-score setup",
        body = body
    )
}

class PushMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(PUSH_LOG_TAG, "Refreshed FCM token: $token")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title = message.notification?.title ?: "MOEX update"
        val body = message.notification?.body ?: "Новые данные по TATN/TATNP"
        showPushNotification(
            context = this,
            title = title,
            body = body
        )
    }
}
