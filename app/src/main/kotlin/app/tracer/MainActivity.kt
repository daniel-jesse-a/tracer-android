package app.tracer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import app.tracer.run.RunControllerFacade
import app.tracer.ui.nav.TracerNavHost
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import app.tracer.ui.settings.SettingsRepository
import app.tracer.ui.theme.TracerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var runController: RunControllerFacade
    @Inject lateinit var settings: SettingsRepository

    /** Bumped each time the run notification is tapped while the activity is already alive. */
    private var openLiveSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val startOnLive = savedInstanceState == null && intent.getBooleanExtra(EXTRA_OPEN_LIVE, false)
        runController.recoverIfNeeded()
        setContent {
            val mode by settings.themeMode.collectAsState(initial = "dark")
            val dark = when (mode) { "light" -> false; "auto" -> isSystemInDarkTheme(); else -> true }
            TracerTheme(darkTheme = dark) {
                TracerNavHost(startOnLive = startOnLive, openLiveSignal = openLiveSignal)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_LIVE, false)) openLiveSignal++
    }

    companion object {
        const val EXTRA_OPEN_LIVE = "open_live"
    }
}
