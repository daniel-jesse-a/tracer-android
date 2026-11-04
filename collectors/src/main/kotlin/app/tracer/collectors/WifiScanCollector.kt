package app.tracer.collectors

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.tracer.core.model.Sample
import app.tracer.core.model.WifiScanSample
import app.tracer.core.runtime.Collector
import app.tracer.core.runtime.FixAction
import app.tracer.core.runtime.CollectorContext
import app.tracer.core.util.WifiMath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * [wifi_scan] — periodic WifiManager.startScan(); every fresh result set (including scans
 * triggered by other apps) becomes WifiScanSample rows sharing one Stamp, scanId = stamp.tUtcMs.
 * features = 11k/11v/11r from the AP's information elements (see [scanFeatures]).
 * Throttling: WifiManager.isScanThrottleEnabled (API 30+) is read at start; when on, the collector
 * is degraded immediately with [FixAction.DEVELOPER_OPTIONS] ([THROTTLE_SETTING_MESSAGE]) and stays
 * so for the run. Independently, two consecutive failed scans mark the collector degraded and set
 * [WifiScanCache.throttled].
 */
class WifiScanCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanCache: WifiScanCache,
) : Collector {
    override val id = "wifi_scan"

    @SuppressLint("MissingPermission")
    override fun samples(ctx: CollectorContext): Flow<Sample> = callbackFlow<Sample> {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (!context.hasAllPermissions(*needed.toTypedArray())) {
            ctx.status.unavailable(id, "Nearby Wi-Fi devices and precise location permissions are required for Wi-Fi scans.", FixAction.APP_PERMISSIONS)
            awaitClose { }
            return@callbackFlow
        }
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        if (wifi == null) {
            ctx.status.unavailable(id, "This device has no Wi-Fi.")
            awaitClose { }
            return@callbackFlow
        }
        val settings = ctx.settings.effective()
        val worker = Worker(id)
        val failures = AtomicInteger(0)
        val awaitingResult = AtomicBoolean(false)
        // Developer option "Wi-Fi scan throttling" (on by default): 4 scans / 2 min for foreground apps.
        val throttleSetting = frameworkCall { wifi.isScanThrottleEnabled } == true
        if (throttleSetting) {
            scanCache.throttled = true
            ctx.status.degraded(id, THROTTLE_SETTING_MESSAGE, FixAction.DEVELOPER_OPTIONS)
        }

        fun onFailure() {
            if (failures.incrementAndGet() >= 2) {
                scanCache.throttled = true
                ctx.status.degraded(id, if (throttleSetting) THROTTLE_SETTING_MESSAGE else THROTTLED_MESSAGE, FixAction.DEVELOPER_OPTIONS)
            }
        }

        fun onSuccess() {
            failures.set(0)
            // With the throttle setting on, a successful scan does not clear the banner: later ones will be refused.
            if (throttleSetting) return
            if (scanCache.throttled == true) ctx.status.ok(id)
            scanCache.throttled = false
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                if (!updated) {
                    if (awaitingResult.getAndSet(false)) onFailure()
                    return
                }
                awaitingResult.set(false)
                onSuccess()
                val results: List<ScanResult> = frameworkCall { wifi.scanResults }.orEmpty()
                val stamp = ctx.clock.now()
                scanCache.update(results, stamp.tMonoNs)
                for (r in results) {
                    val bssid = r.BSSID ?: continue
                    val sample = WifiScanSample(
                        runId = ctx.runId,
                        stamp = stamp,
                        scanId = stamp.tUtcMs,
                        ssid = ssidOf(r),
                        bssid = bssid.lowercase(),
                        rssiDbm = r.level,
                        frequencyMhz = r.frequency,
                        channelWidth = WifiMath.widthOf(r.channelWidth),
                        standard = WifiMath.standardOf(r.wifiStandard, r.frequency),
                        capabilities = r.capabilities,
                        features = featuresOf(r),
                    )
                    if (!channel.emitFromCallback(ctx, id, sample)) break
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            null,
            worker.handler,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        launch {
            while (isActive) {
                ctx.signals.awaitResumed()
                @Suppress("DEPRECATION")
                val started = frameworkCall { wifi.startScan() } ?: false
                if (started) awaitingResult.set(true) else onFailure()
                delay(settings.wifiScanIntervalMs.coerceAtLeast(5_000L))
            }
        }

        awaitClose {
            frameworkCall { context.unregisterReceiver(receiver) }
            worker.quit()
        }
    }.buffer(512)

    /** 11k/11v/11r from the AP's IEs (ScanResult.getInformationElements, API 30+) and AKMs. */
    private fun featuresOf(r: ScanResult): String? {
        val elements = frameworkCall { r.informationElements }.orEmpty()
            .filter { it.id == ElementId.RM_ENABLED_CAPABILITIES || it.id == ElementId.EXTENDED_CAPABILITIES || it.id == ElementId.MOBILITY_DOMAIN }
            .map { ie ->
                // duplicate(): the framework's buffer is shared — never consume its position.
                val buf = ie.bytes.duplicate()
                val octets = ByteArray(buf.remaining())
                buf.get(octets)
                ie.id to octets
            }
        return scanFeatures(elements, r.capabilities)
    }

    private fun ssidOf(r: ScanResult): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            unquoteSsid(r.wifiSsid?.toString())
        } else {
            @Suppress("DEPRECATION")
            unquoteSsid(r.SSID)
        }

    companion object {
        const val THROTTLE_SETTING_MESSAGE =
            "Wi-Fi scans are limited by Android. Turn off Developer options › Wi-Fi scan throttling."
        const val THROTTLED_MESSAGE =
            "Wi-Fi scans are being throttled. Turn off Developer options › Wi-Fi scan throttling for 30 s scans."
    }
}
