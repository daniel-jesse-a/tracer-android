package app.tracer.collectors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import app.tracer.core.model.Sample
import app.tracer.core.model.WifiConnectionSample
import app.tracer.core.model.WifiLinkSample
import app.tracer.core.runtime.Collector
import app.tracer.core.runtime.CollectorContext
import app.tracer.core.util.WifiMath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.addJsonObject
import javax.inject.Inject

/**
 * [wifi] — the current Wi-Fi association (WifiConnectionSample + WifiLinkSample) from a
 * TRANSPORT_WIFI NetworkCallback with FLAG_INCLUDE_LOCATION_INFO. Emits on change, at most 1 Hz;
 * emits one all-null WifiConnectionSample when Wi-Fi is lost.
 */
class WifiCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanCache: WifiScanCache,
) : Collector {
    override val id = "wifi"

    @SuppressLint("MissingPermission")
    override fun samples(ctx: CollectorContext): Flow<Sample> = callbackFlow<Sample> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            ctx.status.unavailable(id, "Connectivity service unavailable.")
            awaitClose { }
            return@callbackFlow
        }
        val hasLocation = context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!hasLocation) {
            ctx.status.degraded(id, "Precise location permission is needed to read the Wi-Fi network name and BSSID.")
        }
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        val worker = Worker(id)

        // All state below is touched only on the worker thread.
        var latest: WifiInfo? = null
        var lastKey: Any? = null
        var lastEmitMs = 0L
        var pending = false
        var lostEmitted = false

        fun channelWidthFor(bssid: String?): String? {
            val hit = scanCache.find(bssid) ?: if (hasLocation && bssid != null) {
                frameworkCall { wifiManager?.scanResults }?.firstOrNull { it.BSSID.equals(bssid, ignoreCase = true) }
            } else {
                null
            }
            return hit?.let { WifiMath.widthOf(it.channelWidth) }
        }

        fun emitNow(info: WifiInfo) {
            lastEmitMs = SystemClock.elapsedRealtime()
            lostEmitted = false
            val stamp = ctx.clock.now()
            val bssid = normalizeBssid(info.bssid)
            val freq = info.frequency.takeIf { it > 0 }
            val conn = WifiConnectionSample(
                runId = ctx.runId,
                stamp = stamp,
                ssid = unquoteSsid(info.ssid),
                bssid = bssid,
                security = securityName(info.currentSecurityType),
                rssiDbm = info.rssi.takeIf { it > -127 && it < 0 }, // -127 = WifiInfo.INVALID_RSSI
                linkSpeedMbps = info.linkSpeed.takeIf { it > 0 },
                frequencyMhz = freq,
                channelWidth = channelWidthFor(bssid),
                standard = WifiMath.standardOf(info.wifiStandard, freq),
            )
            val link = WifiLinkSample(
                runId = ctx.runId,
                stamp = stamp,
                bssid = bssid,
                txLinkMbps = info.txLinkSpeedMbps.takeIf { it > 0 },
                rxLinkMbps = info.rxLinkSpeedMbps.takeIf { it > 0 },
                maxTxMbps = info.maxSupportedTxLinkSpeedMbps.takeIf { it > 0 },
                maxRxMbps = info.maxSupportedRxLinkSpeedMbps.takeIf { it > 0 },
                mloLinksJson = mloLinksJson(info),
            )
            channel.emitFromCallback(ctx, id, conn)
            channel.emitFromCallback(ctx, id, link)
        }

        // Deferred emit of the newest WifiInfo when a change arrives inside the 1 s window.
        val flush = Runnable {
            pending = false
            latest?.let(::emitNow)
        }

        fun onInfo(info: WifiInfo) {
            latest = info
            val key = listOf(
                info.ssid, info.bssid, info.rssi, info.linkSpeed, info.frequency, info.currentSecurityType,
                info.txLinkSpeedMbps, info.rxLinkSpeedMbps, info.wifiStandard,
            )
            if (key == lastKey) return
            lastKey = key
            val sinceMs = SystemClock.elapsedRealtime() - lastEmitMs
            if (sinceMs >= 1_000L) {
                emitNow(info)
            } else if (!pending) {
                pending = true
                worker.handler.postDelayed(flush, 1_000L - sinceMs)
            }
        }

        val callback = object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val info = networkCapabilities.transportInfo as? WifiInfo ?: return
                onInfo(info)
            }

            override fun onLost(network: Network) {
                latest = null
                lastKey = null
                pending = false
                worker.handler.removeCallbacks(flush)
                if (!lostEmitted) {
                    lostEmitted = true
                    channel.emitFromCallback(ctx, id, WifiConnectionSample(runId = ctx.runId, stamp = ctx.clock.now()))
                }
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        frameworkCall({ ctx.status.unavailable(id, "Wi-Fi monitoring refused: ${it.message}") }) {
            cm.registerNetworkCallback(request, callback, worker.handler)
        }

        awaitClose {
            frameworkCall { cm.unregisterNetworkCallback(callback) }
            worker.quit()
        }
    }.buffer(64)

    private fun mloLinksJson(info: WifiInfo): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val links = frameworkCall { info.associatedMloLinks } ?: return null
        if (links.isEmpty()) return null
        return buildJsonArray {
            links.forEach { l ->
                addJsonObject {
                    put("link_id", l.linkId)
                    put("bssid", l.apMacAddress?.toString())
                    put("band", mloBandName(l.band))
                    put("channel", l.channel)
                    put("rssi", l.rssi)
                    put("freq", mloFrequencyMhz(l.band, l.channel))
                    put("state", l.state)
                }
            }
        }.toString()
    }
}
