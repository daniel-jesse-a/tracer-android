package app.tracer.probes.di

import app.tracer.core.runtime.Collector
import app.tracer.core.runtime.NtpClient
import app.tracer.core.runtime.TowerLookup
import app.tracer.probes.CaptiveProbeCollector
import app.tracer.probes.InternalProbeCollector
import app.tracer.probes.LatencyProbeCollector
import app.tracer.probes.OpenCellIdTowerLookup
import app.tracer.probes.PublicIpProbeCollector
import app.tracer.probes.SntpClient
import app.tracer.probes.ThroughputProbeCollector
import app.tracer.probes.TowerKeyProvider
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** The probes' base OkHttpClient: no redirects, no silent retries. Qualified to avoid clashing with other modules' clients. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ProbeHttpClient

/** Hilt bindings for :probes — base client, NtpClient, TowerLookup and the probe collectors. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProbesModule {
    @Binds @IntoSet abstract fun bindLatency(x: LatencyProbeCollector): Collector
    @Binds @IntoSet abstract fun bindThroughput(x: ThroughputProbeCollector): Collector
    @Binds @IntoSet abstract fun bindPublicIp(x: PublicIpProbeCollector): Collector
    @Binds @IntoSet abstract fun bindCaptive(x: CaptiveProbeCollector): Collector
    @Binds @IntoSet abstract fun bindInternal(x: InternalProbeCollector): Collector

    @Binds @Singleton abstract fun bindNtp(impl: SntpClient): NtpClient
    @Binds @Singleton abstract fun bindTowerLookup(impl: OpenCellIdTowerLookup): TowerLookup

    /** :app may provide a TowerKeyProvider (e.g. reading RunSettings.openCellIdKey); absent = offline DB only. */
    @BindsOptionalOf abstract fun optionalTowerKeyProvider(): TowerKeyProvider

    companion object {
        @Provides
        @Singleton
        @ProbeHttpClient
        fun provideProbeHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
