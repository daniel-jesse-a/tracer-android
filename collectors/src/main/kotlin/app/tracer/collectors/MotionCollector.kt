package app.tracer.collectors

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import app.tracer.core.model.MotionSample
import app.tracer.core.model.Sample
import app.tracer.core.runtime.Collector
import app.tracer.core.runtime.CollectorContext
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

/**
 * [motion] — Activity Recognition transitions (MotionSample with activity, confidence 100) and
 * a once-per-second aggregate of accelerometer deviation from gravity plus barometric pressure
 * and relative altitude (see [MotionAggregator]).
 */
class MotionCollector @Inject constructor(
    @ApplicationContext private val context: Context,
) : Collector {
    override val id = "motion"

    @SuppressLint("MissingPermission")
    override fun samples(ctx: CollectorContext): Flow<Sample> = callbackFlow<Sample> {
        val settings = ctx.settings.effective()
        val worker = Worker(id)
        val sensors = context.getSystemService(SensorManager::class.java)
        val aggregator = MotionAggregator() // worker-thread only

        // ---- Sensors ----
        val hz = settings.motionHz.coerceIn(1, 100)
        val periodUs = 1_000_000 / hz
        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> if (event.values.size >= 3) {
                        aggregator.addAccel(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
                    }
                    Sensor.TYPE_PRESSURE -> if (event.values.isNotEmpty()) aggregator.addPressureHpa(event.values[0].toDouble())
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val pressure = sensors?.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (sensors != null && accel != null) sensors.registerListener(sensorListener, accel, periodUs, worker.handler)
        // Pressure changes slowly; 1 Hz is plenty and saves power.
        if (sensors != null && pressure != null) sensors.registerListener(sensorListener, pressure, 1_000_000, worker.handler)
        if (accel == null && pressure == null) ctx.status.degraded(id, "No accelerometer or barometer available.")

        val tick = object : Runnable {
            override fun run() {
                aggregator.drain()?.let { a ->
                    channel.emitFromCallback(
                        ctx, id,
                        MotionSample(
                            runId = ctx.runId,
                            stamp = ctx.clock.now(),
                            pressureKpa = a.pressureKpa,
                            relAltM = a.relAltM,
                            accelMean = a.accelMean,
                            accelMax = a.accelMax,
                        ),
                    )
                }
                worker.handler.postDelayed(this, 1_000L)
            }
        }
        worker.handler.postDelayed(tick, 1_000L)

        // ---- Activity transitions (needs ACTIVITY_RECOGNITION) ----
        var arClient: ActivityRecognitionClient? = null
        var pendingIntent: PendingIntent? = null
        var receiver: BroadcastReceiver? = null
        if (!context.hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
            ctx.status.degraded(id, "Physical activity permission is missing; logging sensors only.")
        } else {
            val action = "${context.packageName}.TRACER_ACTIVITY_TRANSITION"
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (!ActivityTransitionResult.hasResult(intent)) return
                    val result = ActivityTransitionResult.extractResult(intent) ?: return
                    for (e in result.transitionEvents) {
                        if (e.transitionType != ActivityTransition.ACTIVITY_TRANSITION_ENTER) continue
                        channel.emitFromCallback(
                            ctx, id,
                            MotionSample(
                                runId = ctx.runId,
                                stamp = ctx.clock.now(),
                                activity = activityName(e.activityType),
                                confidence = 100,
                            ),
                        )
                    }
                }
            }
            ContextCompat.registerReceiver(context, r, IntentFilter(action), null, worker.handler, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
            val intent = Intent(action).setPackage(context.packageName)
            val pi = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            pendingIntent = pi
            try {
                val client = ActivityRecognition.getClient(context)
                client.requestActivityTransitionUpdates(transitionRequest(), pi)
                    .addOnFailureListener { e -> ctx.status.degraded(id, "Activity recognition unavailable: ${e.message}") }
                arClient = client
            } catch (e: Exception) {
                ctx.status.degraded(id, "Activity recognition unavailable: ${e.message}")
            }
        }

        awaitClose {
            sensors?.unregisterListener(sensorListener)
            val pi = pendingIntent
            if (pi != null) {
                arClient?.let { client -> frameworkCall { client.removeActivityTransitionUpdates(pi) } }
                pi.cancel()
            }
            receiver?.let { frameworkCall { context.unregisterReceiver(it) } }
            worker.quit()
        }
    }.buffer(64)

    private fun transitionRequest(): ActivityTransitionRequest {
        val types = listOf(
            DetectedActivity.STILL, DetectedActivity.WALKING, DetectedActivity.RUNNING,
            DetectedActivity.ON_BICYCLE, DetectedActivity.IN_VEHICLE,
        )
        val transitions = types.flatMap { type ->
            listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map { t ->
                ActivityTransition.Builder().setActivityType(type).setActivityTransition(t).build()
            }
        }
        return ActivityTransitionRequest(transitions)
    }

    private companion object {
        const val REQUEST_CODE = 0x7ACE
    }
}
