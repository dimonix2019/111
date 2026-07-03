package com.example.moexmvp

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity

/** Полный сенсор: в fullscreen можно повернуть в портрет и выйти обратно на вкладку «Рынок». */
internal fun ComponentActivity.lockLandscapeOrientation() {
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
}

internal fun ComponentActivity.unlockScreenOrientation() {
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}
