package app.tracer.collectors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import app.tracer.core.model.LocationSample
import app.tracer.core.model.Sample
import app.tracer.core.model.Stamp
import app.tracer.core.runtime.Collector
import app.tracer.core.runtime.CollectorContext
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import android.location.LocationRequest as PlatformLocationRequest
import com.google.android.gms.location.LocationListener as FusedLocationListener
import com.google.android.gms.location.LocationRequest as FusedLocationRequest

/**
 * [location] — raw GNSS fixes from LocationManager.GPS_PROVIDER (provider "gps"), plus optional
 * FusedLocationProvider rows (provider "fused") when settings.useFusedComparison is on.
 */
class LocationCollector @Inject constructor(
    @ApplicationContext private val context: Context,
) : Collector {
    override val id = "location"
    // No watchdog: indoors a GPS cold start can take minutes; restarting the request resets the
    // GNSS engine and guarantees no fix (seen in "Office test 1"). Silence here is not a hang.
    override val watchdogMs: Long? = null

    @SuppressLint("MissingPermission")
    override fun samples(ctx: CollectorContext): Flow<Sample> = callbackFlow<Sample> {
        if (!context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            ctx.status.unavailable(id, "Precise location permission is required for GPS logging.")
            awaitClose { }
            return@callbackFlow
        }
        val lm = context.getSystemService(LocationManager::class.java)
        if (lm == null || LocationManager.GPS_PROVIDER !in lm.allProviders) {
            ctx.status.unavailable(id, "This device has no GPS receiver.")
            awaitClose { }
            return@callbackFlow
        }
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            ctx.status.degraded(id, "Location is turned off. Enable Location to record GPS fixes.")
        }

        val settings = ctx.settings.effective()
        val interval = settings.locationIntervalMs.coerceAtLeast(100L)
        val worker = Worker(id)

        val gpsListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val stamp = ctx.clock.now().copy(gpsTimeMs = location.time)
                channel.emitFromCallback(ctx, id, location.toSample(ctx.runId, stamp, "gps", location.time))
            }

            override fun onProviderEnabled(provider: String) = ctx.status.ok(id)

            override fun onProviderDisabled(provider: String) =
                ctx.status.degraded(id, "Location is turned off. Enable Location to record GPS fixes.")
        }
        val request = PlatformLocationRequest.Builder(interval)
            .setQuality(PlatformLocationRequest.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(interval)
            .build()
        frameworkCall({ ctx.status.unavailable(id, "GPS updates refused: ${it.message}") }) {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, request, worker.executor, gpsListener)
        }

        var fused: FusedLocationProviderClient? = null
        val fusedListener = FusedLocationListener { location ->
            channel.emitFromCallback(ctx, id, location.toSample(ctx.runId, ctx.clock.now(), "fused", null))
        }
        if (settings.useFusedComparison) {
            try {
                val client = LocationServices.getFusedLocationProviderClient(context)
                val fusedRequest = FusedLocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
                    .setMinUpdateIntervalMillis(interval)
                    .build()
                client.requestLocationUpdates(fusedRequest, worker.executor, fusedListener)
                    .addOnFailureListener { e -> ctx.status.degraded(id, "Fused location comparison unavailable: ${e.message}") }
                fused = client
            } catch (e: Exception) {
                // Google Play services missing or outdated — GPS rows still flow.
                ctx.status.degraded(id, "Fused location comparison unavailable: ${e.message}")
            }
        }

        awaitClose {
            frameworkCall { lm.removeUpdates(gpsListener) }
            fused?.let { frameworkCall { it.removeLocationUpdates(fusedListener) } }
            worker.quit()
        }
    }.buffer(256)

    private fun Location.toSample(runId: String, stamp: Stamp, provider: String, gpsTime: Long?) = LocationSample(
        runId = runId,
        stamp = stamp,
        lat = latitude,
        lon = longitude,
        altM = if (hasAltitude()) altitude else null,
        hAccM = if (hasAccuracy()) accuracy.toDouble() else null,
        vAccM = if (hasVerticalAccuracy()) verticalAccuracyMeters.toDouble() else null,
        speedMps = if (hasSpeed()) speed.toDouble() else null,
        speedAcc = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond.toDouble() else null,
        courseDeg = if (hasBearing()) bearing.toDouble() else null,
        courseAcc = if (hasBearingAccuracy()) bearingAccuracyDegrees.toDouble() else null,
        gpsTimeMs = gpsTime,
        provider = provider,
    )
}
