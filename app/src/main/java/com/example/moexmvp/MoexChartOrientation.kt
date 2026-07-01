package com.example.moexmvp

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity

internal fun ComponentActivity.lockLandscapeOrientation() {
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
}

internal fun ComponentActivity.unlockScreenOrientation() {
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}
