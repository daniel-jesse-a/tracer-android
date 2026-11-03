package app.tracer.collectors

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.core.content.ContextCompat
import app.tracer.core.model.Sample
import app.tracer.core.runtime.CollectorContext
import app.tracer.core.runtime.RunSignals
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.first
import java.util.concurrent.Executor

/** One background thread per collector; all framework callbacks for that collector land here. */
internal class Worker(name: String) {
    private val thread = HandlerThread("tracer-$name").apply { start() }
    val looper: Looper = thread.looper
    val handler: Handler = Handler(looper)
    val executor: Executor = Executor { r -> handler.post(r) }
    fun quit() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }
}

internal fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

internal fun Context.hasAllPermissions(vararg permissions: String): Boolean = permissions.all { hasPermission(it) }

/** Suspends until the run is not paused. */
internal suspend fun RunSignals.awaitResumed() {
    paused.first { !it }
}

/**
 * Sends [sample] from a framework callback thread (blocking that worker thread for backpressure,
 * never the main thread) and reports it on the status board. Dropped while the run is paused.
 * Returns false if the channel is closed or the run is paused.
 */
internal fun SendChannel<Sample>.emitFromCallback(ctx: CollectorContext, id: String, sample: Sample): Boolean {
    if (ctx.signals.paused.value) return false
    val ok = trySendBlocking(sample).isSuccess
    if (ok) ctx.status.sample(id, sample.stamp.tMonoNs)
    return ok
}

/** Coroutine-side counterpart of [emitFromCallback]. */
internal suspend fun SendChannel<Sample>.emitSample(ctx: CollectorContext, id: String, sample: Sample) {
    if (ctx.signals.paused.value) return
    send(sample)
    ctx.status.sample(id, sample.stamp.tMonoNs)
}

/** Runs [block] and swallows SecurityException / IllegalStateException / IllegalArgumentException from the framework. */
internal inline fun <T> frameworkCall(onError: (Exception) -> Unit = {}, block: () -> T): T? = try {
    block()
} catch (e: SecurityException) {
    onError(e); null
} catch (e: IllegalStateException) {
    onError(e); null
} catch (e: IllegalArgumentException) {
    onError(e); null
}
