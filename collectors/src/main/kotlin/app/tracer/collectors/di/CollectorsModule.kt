package app.tracer.collectors.di

import app.tracer.collectors.CellularCollector
import app.tracer.collectors.DeviceCollector
import app.tracer.collectors.DeviceInfoProviderImpl
import app.tracer.collectors.GnssCollector
import app.tracer.collectors.LocationCollector
import app.tracer.collectors.MotionCollector
import app.tracer.collectors.NetworkPathCollector
import app.tracer.collectors.WifiCollector
import app.tracer.collectors.WifiRttCollector
import app.tracer.collectors.WifiScanCollector
import app.tracer.core.runtime.Collector
import app.tracer.core.runtime.DeviceInfoProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/** Contributes every :collectors [Collector] to the Set<Collector> multibinding and binds [DeviceInfoProvider]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class CollectorsModule {
    @Binds @IntoSet abstract fun bindLocation(x: LocationCollector): Collector
    @Binds @IntoSet abstract fun bindGnss(x: GnssCollector): Collector
    @Binds @IntoSet abstract fun bindCellular(x: CellularCollector): Collector
    @Binds @IntoSet abstract fun bindWifi(x: WifiCollector): Collector
    @Binds @IntoSet abstract fun bindWifiScan(x: WifiScanCollector): Collector
    @Binds @IntoSet abstract fun bindWifiRtt(x: WifiRttCollector): Collector
    @Binds @IntoSet abstract fun bindNetworkPath(x: NetworkPathCollector): Collector
    @Binds @IntoSet abstract fun bindMotion(x: MotionCollector): Collector
    @Binds @IntoSet abstract fun bindDevice(x: DeviceCollector): Collector

    @Binds @Singleton abstract fun bindDeviceInfo(impl: DeviceInfoProviderImpl): DeviceInfoProvider
}
