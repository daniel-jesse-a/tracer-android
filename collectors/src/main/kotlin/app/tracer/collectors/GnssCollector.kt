package app.tracer.collectors

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.putJsonArray
import android.os.Build
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssClock
import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.LocationManager
import android.os.SystemClock
import app.tracer.core.model.GnssRawSample
import app.tracer.core.model.GnssSatelliteSample
import app.tracer.core.model.Sample
import app.tracer.core.runtime.Collector
import app.tracer.core.runtime.CollectorContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * [gnss] — per-satellite status (≤1 Hz, one shared Stamp per callback) and, when enabled and
 * supported, raw GNSS measurements as compact JSON (≤1 Hz).
 */
class GnssCollector @Inject constructor(
    @ApplicationContext private val context: Context,
) : Collector {
    override val id = "gnss"
    override val watchdogMs: Long = 10_000L

    @SuppressLint("MissingPermission")
    override fun samples(ctx: CollectorContext): Flow<Sample> = callbackFlow<Sample> {
        if (!context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            ctx.status.unavailable(id, "Precise location permission is required for satellite logging.")
            awaitClose { }
            return@callbackFlow
        }
        val lm = context.getSystemService(LocationManager::class.java)
        if (lm == null) {
            ctx.status.unavailable(id, "Location services are not available on this device.")
            awaitClose { }
            return@callbackFlow
        }
        val settings = ctx.settings.effective()
        val worker = Worker(id)
        var lastStatusMs = 0L
        var lastRawMs = 0L

        val statusCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - lastStatusMs < 1_000L) return
                lastStatusMs = nowMs
                val stamp = ctx.clock.now()
                for (i in 0 until status.satelliteCount) {
                    val sample = GnssSatelliteSample(
                        runId = ctx.runId,
                        stamp = stamp,
                        svid = status.getSvid(i),
                        constellation = constellationName(status.getConstellationType(i)),
                        cn0DbHz = status.getCn0DbHz(i).toDouble(),
                        elevation = status.getElevationDegrees(i).toDouble(),
                        azimuth = status.getAzimuthDegrees(i).toDouble(),
                        usedInFix = status.usedInFix(i),
                        carrierFreqHz = if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i).toDouble() else null,
                    )
                    if (!channel.emitFromCallback(ctx, id, sample)) return
                }
            }

            override fun onStopped() {
                ctx.status.degraded(id, "GNSS engine stopped (location turned off?).")
            }

            override fun onStarted() {
                ctx.status.ok(id)
            }
        }
        val statusRegistered = frameworkCall({ ctx.status.unavailable(id, "Satellite status refused: ${it.message}") }) {
            lm.registerGnssStatusCallback(worker.executor, statusCallback)
        } ?: false

        var rawCallback: GnssMeasurementsEvent.Callback? = null
        if (settings.gnssRawMeasurements) {
            val supported = frameworkCall { lm.gnssCapabilities.hasMeasurements() } ?: false
            if (!supported) {
                ctx.status.degraded(id, "Raw GNSS measurements are not supported on this device.")
            } else {
                val cb = object : GnssMeasurementsEvent.Callback() {
                    override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) {
                        val nowMs = SystemClock.elapsedRealtime()
                        if (nowMs - lastRawMs < 1_000L) return
                        lastRawMs = nowMs
                        val measurements = eventArgs.measurements
                        val sample = GnssRawSample(
                            runId = ctx.runId,
                            stamp = ctx.clock.now(),
                            measurementCount = measurements.size,
                            clockJson = clockJson(eventArgs),
                            rawJson = buildJsonArray { measurements.forEach { add(measurementJson(it)) } }.toString(),
                        )
                        channel.emitFromCallback(ctx, id, sample)
                    }

                    // Deprecated from API 33 but still the only status signal on Android 12/12L (minSdk 31).
                    @Deprecated("Deprecated in Java")
                    @Suppress("DEPRECATION")
                    override fun onStatusChanged(status: Int) {
                        when (status) {
                            GnssMeasurementsEvent.Callback.STATUS_NOT_SUPPORTED -> ctx.status.degraded(id, "Raw GNSS measurements are not supported on this device.")
                            GnssMeasurementsEvent.Callback.STATUS_LOCATION_DISABLED -> ctx.status.degraded(id, "Location is turned off; raw GNSS paused.")
                            GnssMeasurementsEvent.Callback.STATUS_NOT_ALLOWED -> ctx.status.degraded(id, "Raw GNSS measurements are not allowed for this app.")
                        }
                    }
                }
                val ok = frameworkCall { lm.registerGnssMeasurementsCallback(worker.executor, cb) } ?: false
                if (ok) rawCallback = cb else ctx.status.degraded(id, "Raw GNSS measurements could not be enabled.")
            }
        }
        if (!statusRegistered) ctx.status.unavailable(id, "Satellite status is unavailable on this device.")

        awaitClose {
            frameworkCall { lm.unregisterGnssStatusCallback(statusCallback) }
            rawCallback?.let { cb -> frameworkCall { lm.unregisterGnssMeasurementsCallback(cb) } }
            worker.quit()
        }
    }.buffer(512)

    @Suppress("DEPRECATION")
    private fun legacyAgc(m: GnssMeasurement): Double? =
        if (m.hasAutomaticGainControlLevelDb()) m.automaticGainControlLevelDb else null

    private fun JsonObjectBuilder.putFinite(key: String, value: Double) {
        if (value.isFinite()) put(key, value)
    }

    private fun clockJson(e: GnssMeasurementsEvent): String = buildJsonObject {
        val c: GnssClock = e.clock
        put("time_ns", c.timeNanos)
        if (c.hasFullBiasNanos()) put("full_bias_ns", c.fullBiasNanos)
        if (c.hasBiasNanos()) putFinite("bias_ns", c.biasNanos)
        if (c.hasBiasUncertaintyNanos()) putFinite("bias_unc_ns", c.biasUncertaintyNanos)
        if (c.hasDriftNanosPerSecond()) putFinite("drift_nsps", c.driftNanosPerSecond)
        if (c.hasLeapSecond()) put("leap_s", c.leapSecond)
        put("hw_discontinuity", c.hardwareClockDiscontinuityCount)
        if (c.hasElapsedRealtimeNanos()) put("elapsed_realtime_ns", c.elapsedRealtimeNanos)
        // API 33+: AGC is reported per constellation/frequency on the event (replaces per-measurement AGC)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            putJsonArray("agc") {
                e.gnssAutomaticGainControls.forEach { a ->
                    addJsonObject {
                        putFinite("level_db", a.levelDb)
                        put("constellation", constellationName(a.constellationType))
                        put("carrier_hz", a.carrierFrequencyHz)
                    }
                }
            }
        }
    }.toString()

    private fun measurementJson(m: GnssMeasurement) = buildJsonObject {
        put("svid", m.svid)
        put("constellation", constellationName(m.constellationType))
        putFinite("cn0", m.cn0DbHz)
        if (m.hasBasebandCn0DbHz()) putFinite("baseband_cn0", m.basebandCn0DbHz)
        put("state", m.state)
        put("rx_sv_time_ns", m.receivedSvTimeNanos)
        put("rx_sv_time_unc_ns", m.receivedSvTimeUncertaintyNanos)
        putFinite("time_offset_ns", m.timeOffsetNanos)
        putFinite("prr_mps", m.pseudorangeRateMetersPerSecond)
        putFinite("prr_unc_mps", m.pseudorangeRateUncertaintyMetersPerSecond)
        put("adr_state", m.accumulatedDeltaRangeState)
        putFinite("adr_m", m.accumulatedDeltaRangeMeters)
        putFinite("adr_unc_m", m.accumulatedDeltaRangeUncertaintyMeters)
        put("multipath", m.multipathIndicator)
        if (m.hasCarrierFrequencyHz()) putFinite("carrier_hz", m.carrierFrequencyHz.toDouble())
        if (m.hasSnrInDb()) putFinite("snr_db", m.snrInDb)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) legacyAgc(m)?.let { putFinite("agc_db", it) }
    }
}
