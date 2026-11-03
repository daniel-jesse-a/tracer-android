package app.tracer.run

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.tracer.MainActivity
import app.tracer.R
import app.tracer.core.runtime.LiveSnapshot
import kotlin.math.roundToInt

/** Channels + notification builders for the run service. */
internal class RunNotifications(private val context: Context) {

    private val nm: NotificationManager = requireNotNull(context.getSystemService(NotificationManager::class.java))

    /** Idempotent; called from TracerApp and RunService (HiltTestApplication skips TracerApp). */
    fun ensureChannels() {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RUN, context.getString(R.string.channel_run), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_run_desc)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_alerts_desc)
            },
        )
    }

    fun running(live: LiveSnapshot, paused: Boolean): Notification {
        val dash = context.getString(R.string.notif_dash)
        val rsrp = live.primaryServing?.rsrp?.toString() ?: dash
        val latency = live.latencyMs?.roundToInt()?.toString() ?: dash
        val text = context.getString(
            R.string.notif_text,
            live.transport ?: dash, live.rat ?: dash, rsrp, latency, live.eventCount,
        )
        val elapsed = formatHms(live.elapsedMs)
        val title = if (paused) context.getString(R.string.notif_title_paused, elapsed) else elapsed

        val toggle = if (paused) {
            NotificationCompat.Action(R.drawable.ic_action_play, context.getString(R.string.action_resume), servicePending(RunService.ACTION_RESUME, REQ_RESUME))
        } else {
            NotificationCompat.Action(R.drawable.ic_action_pause, context.getString(R.string.action_pause), servicePending(RunService.ACTION_PAUSE, REQ_PAUSE))
        }
        val end = NotificationCompat.Action(R.drawable.ic_action_stop, context.getString(R.string.action_end), servicePending(RunService.ACTION_STOP, REQ_STOP))

        return NotificationCompat.Builder(context, CHANNEL_RUN)
            .setSmallIcon(R.drawable.ic_stat_tracer)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openLivePending())
            .addAction(toggle)
            .addAction(end)
            .build()
    }

    fun alert(text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_tracer)
            .setContentTitle(context.getString(R.string.alert_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(openLivePending())
            .build()

    /** Posts only when the user granted notifications (API 33+); FGS keeps running either way. */
    fun post(id: Int, notification: Notification) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) nm.notify(id, notification)
    }

    private fun servicePending(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context, requestCode, RunService.intent(context, action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun openLivePending(): PendingIntent =
        PendingIntent.getActivity(
            context, REQ_OPEN,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_LIVE, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val CHANNEL_RUN = "run"
        const val CHANNEL_ALERTS = "alerts"
        const val ID_RUNNING = 1001
        const val ID_ALERT = 1002
        private const val REQ_PAUSE = 1
        private const val REQ_RESUME = 2
        private const val REQ_STOP = 3
        private const val REQ_OPEN = 4

        fun formatHms(ms: Long): String {
            val total = (ms.coerceAtLeast(0) / 1000)
            return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
        }
    }
}
