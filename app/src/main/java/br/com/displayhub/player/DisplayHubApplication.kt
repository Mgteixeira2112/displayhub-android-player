package br.com.displayhub.player

import android.app.Application

class DisplayHubApplication : Application() {
    private var runtimeTelemetryReporter: RuntimeTelemetryReporter? = null

    override fun onCreate() {
        super.onCreate()
        runtimeTelemetryReporter = RuntimeTelemetryReporter(this).also { it.start() }
    }

    override fun onTerminate() {
        runtimeTelemetryReporter?.stop()
        runtimeTelemetryReporter = null
        super.onTerminate()
    }
}
