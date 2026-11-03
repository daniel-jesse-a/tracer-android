package app.tracer.ui.preflight

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import app.tracer.ui.onboarding.Permissions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Snapshot of a brief GNSS listen, independent of RunService. */
data class GnssState(
    val permission: Boolean,
    val providerEnabled: Boolean,
    val visible: Int = 0,
    val used: Int = 0,
    val hAccM: Float? = null,
    /** SystemClock.elapsedRealtimeNanos of the last GPS fix */
    val lastFixMonoNs: Long? = null,
    /** GNSS time − device wall clock at the last fix, ms */
    val gpsOffsetMs: Long? = null,
) {
    fun fixAgeMs(nowMonoNs: Long = SystemClock.elapsedRealtimeNanos()): Long? = lastFixMonoNs?.let { (nowMonoNs - it) / 1_000_000 }
    val hasFix: Boolean get() = (fixAgeMs() ?: Long.MAX_VALUE) < 5_000
}

/** Cold flow: collecting registers a GNSS status callback + 1 Hz GPS updates; cancelling removes both. */
@SuppressLint("MissingPermission") // checked via Permissions.fineLocation before registering
fun gnssStateFlow(context: Context): Flow<GnssState> = callbackFlow {
    val lm = context.getSystemService(LocationManager::class.java)
    if (lm == null || !Permissions.fineLocation(context)) {
        trySend(GnssState(permission = lm != null && Permissions.fineLocation(context), providerEnabled = false))
        awaitClose { }
        return@callbackFlow
    }
    var state = GnssState(permission = true, providerEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
    trySend(state)
    val executor = ContextCompat.getMainExecutor(context)
    val status = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(s: GnssStatus) {
            var used = 0
            for (i in 0 until s.satelliteCount) if (s.usedInFix(i)) used++
            state = state.copy(visible = s.satelliteCount, used = used)
            trySend(state)
        }
        override fun onStopped() {
            state = state.copy(visible = 0, used = 0)
            trySend(state)
        }
    }
    val listener = LocationListener { loc ->
        val ageMs = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000
        state = state.copy(
            providerEnabled = true,
            hAccM = if (loc.hasAccuracy()) loc.accuracy else null,
            lastFixMonoNs = loc.elapsedRealtimeNanos,
            gpsOffsetMs = loc.time - (System.currentTimeMillis() - ageMs),
        )
        trySend(state)
    }
    try {
        lm.registerGnssStatusCallback(executor, status)
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, executor, listener)
    } catch (e: Exception) { // SecurityException (revoked mid-way) / IllegalArgumentException (no GPS provider)
        Log.w("GnssProbe", "GNSS listen failed", e)
        trySend(state.copy(providerEnabled = false))
    }
    awaitClose {
        lm.unregisterGnssStatusCallback(status)
        lm.removeUpdates(listener)
    }
}
