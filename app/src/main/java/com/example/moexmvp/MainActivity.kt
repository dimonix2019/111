package com.example.moexmvp

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermissionRequester = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(PUSH_LOG_TAG, "POST_NOTIFICATIONS granted=$granted")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MoexDiagnostics.log(
            applicationContext,
            "lifecycle",
            "activity_onCreate version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        )
        MoexDiagnostics.logMemory(applicationContext, "activity_onCreate")
        createPushNotificationChannel(this)
        requestPushPermissionIfNeeded()
        initPushMessaging()
        scheduleAppUpdateChecks(this)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { checkRemoteAppUpdateAndNotify(this@MainActivity) }
        }
        if (SignalForegroundService.isBackgroundMonitorEnabled(this)) {
            SignalForegroundService.start(this)
            MoexWatchdog.performMonitorWatchdogCheck(this, "activity_onCreate")
        }
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF2196F3),
                    secondary = Color(0xFF64B5F6),
                    tertiary = Color(0xFF90CAF9)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MoexScreen()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        MoexDiagnostics.log(applicationContext, "lifecycle", "activity_onStart")
        MoexWatchdog.recordUiPing(applicationContext)
        MoexWatchdog.performMonitorWatchdogCheck(applicationContext, "activity_onStart")
    }

    override fun onStop() {
        MoexDiagnostics.log(applicationContext, "lifecycle", "activity_onStop")
        super.onStop()
    }

    override fun onDestroy() {
        MoexDiagnostics.log(applicationContext, "lifecycle", "activity_onDestroy")
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MoexMemoryPressure.onTrimMemory(level)
        MoexDiagnostics.log(applicationContext, "lifecycle", "onTrimMemory level=$level")
        MoexDiagnostics.logMemory(applicationContext, "onTrimMemory")
    }

    private fun requestPushPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionRequester.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun initPushMessaging() {
        val firebaseApp = FirebaseApp.initializeApp(this)
        if (firebaseApp == null) {
            Log.w(PUSH_LOG_TAG, "Firebase is not configured. Add google-services.json to enable push.")
            return
        }
        FirebaseMessaging.getInstance().subscribeToTopic(PUSH_TOPIC).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Log.d(PUSH_LOG_TAG, "Subscribed to topic: $PUSH_TOPIC")
            } else {
                Log.e(PUSH_LOG_TAG, "Failed to subscribe to topic: $PUSH_TOPIC", task.exception)
            }
        }
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                Log.d(PUSH_LOG_TAG, "FCM token: $token")
            }
            .addOnFailureListener { error ->
                Log.e(PUSH_LOG_TAG, "Failed to get FCM token", error)
            }
    }
}

internal fun installMoexDiagnosticsCrashHandler(appContext: android.content.Context) {
    val prior = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching { MoexDiagnostics.logUncaught(appContext, thread, throwable) }
        prior?.uncaughtException(thread, throwable)
    }
}
