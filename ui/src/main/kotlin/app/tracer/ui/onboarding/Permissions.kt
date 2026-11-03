package app.tracer.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** Permission facts shared by onboarding, pre-flight and nav start-destination logic. */
object Permissions {

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun fineLocation(context: Context) = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
    fun backgroundLocation(context: Context) = granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    fun phoneState(context: Context) = granted(context, Manifest.permission.READ_PHONE_STATE)
    fun activityRecognition(context: Context) = granted(context, Manifest.permission.ACTIVITY_RECOGNITION)

    fun nearbyWifi(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, Manifest.permission.NEARBY_WIFI_DEVICES)

    fun notifications(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    fun camera(context: Context) = granted(context, Manifest.permission.CAMERA)

    fun batteryExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Without these a run cannot record meaningfully → onboarding is shown at launch. */
    fun hasRequired(context: Context): Boolean = fineLocation(context) && phoneState(context)

    /** Runtime permission steps that apply on this API level: (granted, total). */
    fun runtimeSummary(context: Context): Pair<Int, Int> {
        val checks = buildList {
            add(fineLocation(context)); add(backgroundLocation(context)); add(phoneState(context)); add(activityRecognition(context))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { add(nearbyWifi(context)); add(notifications(context)) }
            add(batteryExempt(context))
        }
        return checks.count { it } to checks.size
    }
}
