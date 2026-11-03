package app.tracer.di

import android.os.SystemClock
import app.tracer.core.model.Stamp
import app.tracer.core.model.TracerClock
import app.tracer.core.runtime.Collector
import app.tracer.core.runtime.CollectorStatusBoard
import app.tracer.core.runtime.RunController
import app.tracer.core.runtime.RunSignals
import app.tracer.probes.TowerKeyProvider
import app.tracer.run.RunControllerFacade
import app.tracer.ui.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppProvidesModule {

    @Provides
    @Singleton
    fun collectorStatusBoard(): CollectorStatusBoard = CollectorStatusBoard()

    @Provides
    @Singleton
    fun runSignals(): RunSignals = RunSignals()

    @Provides
    @Singleton
    fun tracerClock(): TracerClock = TracerClock { Stamp(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos()) }

    /**
     * OpenCelliD key for :probes. key() must not suspend, so the latest RunSettings.openCellIdKey is
     * cached from the settings Flow by an application-lifetime coroutine.
     */
    @Provides
    @Singleton
    fun towerKey(settings: SettingsRepository): TowerKeyProvider {
        val cached = AtomicReference<String?>(null)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            settings.settings.collect { s -> cached.set(s.openCellIdKey?.takeIf { it.isNotBlank() }) }
        }
        return TowerKeyProvider { cached.get() }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindsModule {

    @Binds
    abstract fun runController(facade: RunControllerFacade): RunController

    /** Declares the set so the graph is valid even when no module contributes a Collector. */
    @Multibinds
    abstract fun collectors(): Set<Collector>
}
