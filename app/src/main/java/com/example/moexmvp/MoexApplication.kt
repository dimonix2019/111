package com.example.moexmvp

import android.app.Application

class MoexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installMoexDiagnosticsCrashHandler(applicationContext)
        MoexDiagnostics.log(applicationContext, "lifecycle", "application_onCreate")
    }
}
