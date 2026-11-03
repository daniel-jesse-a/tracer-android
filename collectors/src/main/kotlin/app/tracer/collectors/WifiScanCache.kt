package app.tracer.collectors

import android.net.wifi.ScanResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide memory of the latest Wi-Fi scan, shared by the scan, connection and RTT
 * collectors and by [DeviceInfoProviderImpl] (throttling state).
 */
@Singleton
class WifiScanCache @Inject constructor() {
    @Volatile
    var latest: List<ScanResult> = emptyList()
        private set

    @Volatile
    var latestMonoNs: Long = 0L
        private set

    /** null = unknown (no scan attempted yet). */
    @Volatile
    var throttled: Boolean? = null

    fun update(results: List<ScanResult>, monoNs: Long) {
        latest = results
        latestMonoNs = monoNs
    }

    /** ScanResult for [bssid] (case-insensitive) from the latest scan, if any. */
    fun find(bssid: String?): ScanResult? {
        if (bssid == null) return null
        return latest.firstOrNull { it.BSSID.equals(bssid, ignoreCase = true) }
    }
}
