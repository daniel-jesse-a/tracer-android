package app.tracer

import android.app.Application
import app.tracer.run.RunNotifications
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TracerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RunNotifications(this).ensureChannels() // "run" (low) + "alerts" (high)
    }
}
