package app.tracer.collectors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import app.tracer.core.model.Sample
import app.tracer.core.model.WifiRttSample
import app.tracer.core.runtime.Collector
import app.tracer.core.runtime.CollectorContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * [wifi_rtt] — every 10 s, 802.11mc (and 802.11az on API 35+) ranging to up to 10 of the
 * strongest responders from the latest scan. Only active when settings.wifiRttEnabled and the
 * device has FEATURE_WIFI_RTT.
 */
class WifiRttCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanCache: WifiScanCache,
) : Collector {
    override val id = "wifi_rtt"

    @SuppressLint("MissingPermission")
    override fun samples(ctx: CollectorContext): Flow<Sample> = callbackFlow<Sample> {
        val settings = ctx.settings.effective()
        if (!settings.wifiRttEnabled) {
            ctx.status.unavailable(id, "Wi-Fi RTT is turned off in settings.")
            awaitClose { }
            return@callbackFlow
        }
        val rtt = context.getSystemService(WifiRttManager::class.java)
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT) || rtt == null) {
            ctx.status.unavailable(id, "This device does not support Wi-Fi RTT.")
            awaitClose { }
            return@callbackFlow
        }
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (!context.hasAllPermissions(*needed.toTypedArray())) {
            ctx.status.unavailable(id, "Nearby Wi-Fi devices and precise location permissions are required for Wi-Fi RTT.")
            awaitClose { }
            return@callbackFlow
        }
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val worker = Worker(id)

        val callback = object : RangingResultCallback() {
            override fun onRangingResults(results: MutableList<RangingResult>) {
                val stamp = ctx.clock.now()
                for (r in results) {
                    val mac = r.macAddress?.toString() ?: continue
                    val ok = r.status == RangingResult.STATUS_SUCCESS
                    val sample = WifiRttSample(
                        runId = ctx.runId,
                        stamp = stamp,
                        bssid = mac.lowercase(),
                        status = r.status,
                        // These getters throw IllegalStateException unless status == STATUS_SUCCESS.
                        distanceMm = if (ok) r.distanceMm else null,
                        distanceStdDevMm = if (ok) r.distanceStdDevMm else null,
                        rssiDbm = if (ok) r.rssi else null,
                        attempted = if (ok) r.numAttemptedMeasurements else null,
                        successful = if (ok) r.numSuccessfulMeasurements else null,
                    )
                    if (!channel.emitFromCallback(ctx, id, sample)) return
                }
            }

            override fun onRangingFailure(code: Int) {
                ctx.status.degraded(id, "Wi-Fi RTT ranging failed (code $code).")
            }
        }

        launch {
            while (isActive) {
                ctx.signals.awaitResumed()
                if (!rtt.isAvailable) {
                    ctx.status.degraded(id, "Wi-Fi RTT is temporarily unavailable (Wi-Fi or Location off).")
                } else {
                    val candidates = candidates(wifi)
                    if (candidates.isNotEmpty()) {
                        val request = RangingRequest.Builder().addAccessPoints(candidates).build()
                        frameworkCall({ ctx.status.degraded(id, "Wi-Fi RTT request rejected: ${it.message}") }) {
                            rtt.startRanging(request, worker.executor, callback)
                        }
                    }
                }
                delay(10_000L)
            }
        }

        awaitClose { worker.quit() }
    }.buffer(64)

    @SuppressLint("MissingPermission") // permissions verified by the caller
    private fun candidates(wifi: WifiManager?): List<ScanResult> {
        val source = scanCache.latest.ifEmpty { frameworkCall { wifi?.scanResults }.orEmpty() }
        val max = minOf(10, RangingRequest.getMaxPeers())
        return source
            .filter { isResponder(it) }
            .sortedByDescending { it.level }
            .distinctBy { it.BSSID }
            .take(max)
    }

    private fun isResponder(r: ScanResult): Boolean =
        r.is80211mcResponder() ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && r.is80211azNtbResponder())
}
