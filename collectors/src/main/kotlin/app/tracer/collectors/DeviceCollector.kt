package app.tracer.collectors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import app.tracer.core.model.DeviceSample
import app.tracer.core.model.Sample
import app.tracer.core.model.Stamp
import app.tracer.core.runtime.Collector
import app.tracer.core.runtime.CollectorContext
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

/**
 * [device] — battery level, charging, power-save mode and thermal status; every
 * settings.deviceIntervalMs and whenever any of them changes.
 */
class DeviceCollector @Inject constructor(
    @ApplicationContext private val context: Context,
) : Collector {
    override val id = "device"

    override fun samples(ctx: CollectorContext): Flow<Sample> = callbackFlow<Sample> {
        val settings = ctx.settings.effective()
        val power = context.getSystemService(PowerManager::class.java)
        val worker = Worker(id)
        val zero = Stamp(0, 0)
        var last: DeviceSample? = null // worker-thread only
        var batteryIntent: Intent? = null
        var thermal: Int? = power?.currentThermalStatus

        fun build(): DeviceSample {
            val b = batteryIntent ?: context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            return DeviceSample(
                runId = ctx.runId,
                stamp = ctx.clock.now(),
                battery = if (level >= 0 && scale > 0) level.toDouble() / scale else null,
                charging = if (b == null) null else status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL || plugged != 0,
                lowPower = power?.isPowerSaveMode,
                thermalState = thermal?.let(::thermalName),
            )
        }

        fun emit(force: Boolean) {
            val s = build()
            if (force || last?.copy(stamp = zero) != s.copy(stamp = zero)) {
                last = s
                channel.emitFromCallback(ctx, id, s)
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) batteryIntent = intent
                emit(force = false)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, null, worker.handler, ContextCompat.RECEIVER_NOT_EXPORTED)

        val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
            thermal = status
            emit(force = false)
        }
        power?.let { p -> frameworkCall { p.addThermalStatusListener(worker.executor, thermalListener) } }

        val interval = settings.deviceIntervalMs.coerceAtLeast(1_000L)
        val tick = object : Runnable {
            override fun run() {
                emit(force = true)
                worker.handler.postDelayed(this, interval)
            }
        }
        worker.handler.post(tick)

        awaitClose {
            frameworkCall { context.unregisterReceiver(receiver) }
            power?.let { p -> frameworkCall { p.removeThermalStatusListener(thermalListener) } }
            worker.quit()
        }
    }.buffer(16)
}
