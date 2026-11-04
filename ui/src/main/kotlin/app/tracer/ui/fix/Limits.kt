package app.tracer.ui.fix

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import app.tracer.ui.onboarding.Permissions

/*
 * Phone limits Tracer can detect, each with a guided fix: why it matters, numbered steps with a
 * live check per step, and an action per step that opens the exact system screen or does the part
 * the app is allowed to do itself (permission prompts, battery exemption dialog, quick panels).
 */

enum class Severity { BLOCKER, IMPORTANT, NOTE }

/** What a step's button does. */
sealed interface FixAct {
    /** Try each intent in order (OEM screens first, generic fallback last). */
    data class Open(val label: String, val intents: (Context) -> List<Intent>) : FixAct
    /** Runtime permission prompt; falls back to the app's settings page when Android won't show it again. */
    data class Request(val label: String, val permissions: List<String>) : FixAct
}

data class FixStep(
    val title: String,
    val detail: String,
    /** null = informational step, never shown as done */
    val done: ((Context) -> Boolean)? = null,
    val act: FixAct? = null,
)

enum class Limit(
    val title: String,
    val short: String,
    val severity: Severity,
    /** Shown automatically the first time it is detected in a session. */
    val autoPopup: Boolean,
) {
    WIFI_SCAN_THROTTLE("Wi-Fi scans are limited", "Android allows 4 scans per 2 min", Severity.IMPORTANT, true),
    LOCATION_OFF("Location is off", "No GPS, no Wi-Fi or cell details", Severity.BLOCKER, true),
    LOCATION_PERMISSION("Location not allowed", "Tracer can't record positions", Severity.BLOCKER, true),
    LOCATION_APPROXIMATE("Location is approximate", "Precise location is needed", Severity.BLOCKER, true),
    BACKGROUND_LOCATION("Location only while open", "Recording stops when the screen locks", Severity.IMPORTANT, true),
    PHONE_PERMISSION("Phone access not allowed", "No mobile signal or cell details", Severity.BLOCKER, true),
    NEARBY_WIFI("Nearby Wi-Fi not allowed", "No Wi-Fi scans or RTT", Severity.IMPORTANT, true),
    BATTERY_OPTIMISED("Battery optimisation is on", "Android may pause the test", Severity.IMPORTANT, true),
    BACKGROUND_RESTRICTED("Battery use is Restricted", "Android stops Tracer in the background", Severity.BLOCKER, true),
    NOTIFICATIONS_OFF("Notifications are off", "No recording notification or alerts", Severity.IMPORTANT, true),
    DATA_SAVER("Data Saver limits Tracer", "Mobile probes may fail in the background", Severity.IMPORTANT, true),
    AIRPLANE_MODE("Airplane mode is on", "No mobile network", Severity.IMPORTANT, true),
    MOBILE_DATA_OFF("Mobile data is off", "Mobile checks and fallback can't run", Severity.NOTE, false),
    WIFI_OFF("Wi-Fi is off", "No Wi-Fi checks or scans", Severity.NOTE, false),
    POWER_SAVER("Battery saver is on", "GPS and scans are slowed down", Severity.NOTE, false),
    ACTIVITY_PERMISSION("Motion not allowed", "Walking/still detection is off", Severity.NOTE, false),
    LOW_STORAGE("Storage is low", "Long tests may stop early", Severity.NOTE, false),
    ;

    val why: String get() = when (this) {
        WIFI_SCAN_THROTTLE -> "Android limits each app to 4 Wi-Fi scans every 2 minutes. With throttling off, Tracer sees every access point around you on every scan, so roams, sticky clients and crowded channels show up properly."
        LOCATION_OFF -> "Android hides GPS, Wi-Fi network names and cell details from every app while Location is off."
        LOCATION_PERMISSION -> "Positions, Wi-Fi names and cell IDs all need location permission."
        LOCATION_APPROXIMATE -> "Approximate location is only accurate to ~2 km and hides Wi-Fi names and BSSIDs."
        BACKGROUND_LOCATION -> "\"Allow all the time\" lets the test keep recording with the screen off or another app open."
        PHONE_PERMISSION -> "Mobile signal (RSRP/SINR), bands and cell changes need phone access."
        NEARBY_WIFI -> "Android 13+ needs Nearby devices permission for Wi-Fi scans and Wi-Fi RTT."
        BATTERY_OPTIMISED -> "Battery optimisation lets Android delay or stop a long test to save power."
        BACKGROUND_RESTRICTED -> "With battery use set to Restricted, Android stops Tracer as soon as it leaves the screen."
        NOTIFICATIONS_OFF -> "The recording notification keeps the test alive and lets you pause or finish it from the lock screen."
        DATA_SAVER -> "Data Saver blocks background mobile data, so mobile checks fail when the screen is off."
        AIRPLANE_MODE -> "Airplane mode turns off the mobile radio, so there is nothing to measure on mobile."
        MOBILE_DATA_OFF -> "Wi-Fi + mobile tests check both networks; with mobile data off only Wi-Fi is tested."
        WIFI_OFF -> "With Wi-Fi off there is no Wi-Fi signal, roaming or scan data."
        POWER_SAVER -> "Battery saver lowers GPS and scan rates and can delay checks."
        ACTIVITY_PERMISSION -> "Lets the report show walking vs still and spot motion-related drops."
        LOW_STORAGE -> "Tracer stops a test when free storage falls below the limit in Settings."
    }

    val steps: List<FixStep>
        @SuppressLint("BatteryLife") get() = when (this) {
        WIFI_SCAN_THROTTLE -> listOf(
            FixStep(
                "Turn on Developer options",
                if (isSamsung) "Settings › About phone › Software information › tap Build number 7 times, then enter your PIN."
                else "Settings › About phone › tap Build number 7 times, then enter your PIN.",
                done = { Checks.developerOptions(it) },
                act = FixAct.Open("Open About phone") { listOf(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) },
            ),
            FixStep(
                "Switch off Wi-Fi scan throttling",
                "In Developer options, scroll to Networking › Wi-Fi scan throttling and switch it off. Tip: tap search and type \"throttl\".",
                done = { !Checks.scanThrottled(it) },
                act = FixAct.Open("Open Developer options") { listOf(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) },
            ),
            FixStep("Come back to Tracer", "Tracer checks the setting itself and confirms here."),
        )
        LOCATION_OFF -> listOf(
            FixStep("Turn on Location", "Use the quick switch, then come back.", done = { Checks.locationOn(it) },
                act = FixAct.Open("Open Location") { listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }),
            FixStep("Keep Wi-Fi scanning on", "Location › Location services › Wi-Fi scanning on — improves indoor positions.",
                done = { Checks.wifiScanningAlways(it) },
                act = FixAct.Open("Open Location services") { listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }),
        )
        LOCATION_PERMISSION, LOCATION_APPROXIMATE -> listOf(
            FixStep("Allow precise location", "Choose \"Precise\" and \"While using the app\" in the prompt.",
                done = { Permissions.fineLocation(it) },
                act = FixAct.Request("Allow location", listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))),
            FixStep("Then allow all the time", "On the next screen pick \"Allow all the time\".",
                done = { Permissions.backgroundLocation(it) },
                act = FixAct.Request("Allow all the time", listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))),
        )
        BACKGROUND_LOCATION -> listOf(
            FixStep("Pick \"Allow all the time\"", "Android opens Tracer's location page. Select Allow all the time and keep Use precise location on.",
                done = { Permissions.backgroundLocation(it) },
                act = FixAct.Request("Open location access", listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))),
        )
        PHONE_PERMISSION -> listOf(
            FixStep("Allow phone access", "Tracer only reads signal and cell information — it never makes calls.",
                done = { Permissions.phoneState(it) }, act = FixAct.Request("Allow phone access", listOf(Manifest.permission.READ_PHONE_STATE))),
        )
        NEARBY_WIFI -> listOf(
            FixStep("Allow Nearby devices", "Used only to scan Wi-Fi access points and measure Wi-Fi distance (RTT).",
                done = { Permissions.nearbyWifi(it) },
                act = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) FixAct.Request("Allow Nearby devices", listOf(Manifest.permission.NEARBY_WIFI_DEVICES)) else null),
        )
        BATTERY_OPTIMISED -> buildList {
            add(FixStep("Allow Tracer to run in the background", "Tap Allow in the Android dialog.",
                done = { Permissions.batteryExempt(it) },
                act = FixAct.Open("Allow") { ctx ->
                    listOf(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    )
                }))
            if (isSamsung) add(samsungNeverSleeping)
        }
        BACKGROUND_RESTRICTED -> buildList {
            add(FixStep("Set battery use to Unrestricted", "App info › Battery (App battery usage) › Unrestricted.",
                done = { !Checks.backgroundRestricted(it) }, act = appInfo("Open App info")))
            if (isSamsung) add(samsungNeverSleeping)
        }
        NOTIFICATIONS_OFF -> listOf(
            FixStep("Allow notifications", "Keeps the recording notification on the lock screen.",
                done = { Checks.notifications(it) },
                act = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) FixAct.Request("Allow notifications", listOf(Manifest.permission.POST_NOTIFICATIONS))
                else FixAct.Open("Open notification settings") { ctx -> listOf(notificationSettings(ctx)) }),
        )
        DATA_SAVER -> listOf(
            FixStep("Allow unrestricted data", "Switch on \"Unrestricted data\" (Allow data usage while Data Saver is on) for Tracer.",
                done = { !Checks.dataSaverLimits(it) },
                act = FixAct.Open("Open data usage") { ctx ->
                    listOf(Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, Uri.parse("package:${ctx.packageName}")), appInfoIntent(ctx))
                }),
        )
        AIRPLANE_MODE -> listOf(
            FixStep("Turn off Airplane mode", "Use the switch in the panel, then come back.", done = { !Checks.airplane(it) },
                act = FixAct.Open("Open connectivity") { listOf(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) }),
        )
        MOBILE_DATA_OFF -> listOf(
            FixStep("Turn on mobile data", "Use the Mobile data switch in the panel.", done = { Checks.mobileData(it) != false },
                act = FixAct.Open("Open connectivity") { listOf(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)) }),
        )
        WIFI_OFF -> listOf(
            FixStep("Turn on Wi-Fi", "Use the Wi-Fi switch in the panel and join the network you are testing.", done = { Checks.wifiOn(it) },
                act = FixAct.Open("Open Wi-Fi") { listOf(Intent(Settings.Panel.ACTION_WIFI), Intent(Settings.ACTION_WIFI_SETTINGS)) }),
        )
        POWER_SAVER -> listOf(
            FixStep("Turn off Battery saver", "Battery saver › off for the length of the test.", done = { !Checks.powerSave(it) },
                act = FixAct.Open("Open Battery saver") { listOf(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }),
        )
        ACTIVITY_PERMISSION -> listOf(
            FixStep("Allow physical activity", "Lets Tracer tell walking from standing still.", done = { Permissions.activityRecognition(it) },
                act = FixAct.Request("Allow", listOf(Manifest.permission.ACTIVITY_RECOGNITION))),
        )
        LOW_STORAGE -> listOf(
            FixStep("Free up space", "Delete old tests in Tracer (long-press a test) or free space in Storage.", done = { !Checks.lowStorage(it) },
                act = FixAct.Open("Open Storage") { listOf(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }),
        )
    }

    /** Detected right now. */
    fun active(context: Context): Boolean = when (this) {
        WIFI_SCAN_THROTTLE -> Checks.scanThrottled(context)
        LOCATION_OFF -> !Checks.locationOn(context)
        LOCATION_PERMISSION -> !Permissions.fineLocation(context) && !Permissions.granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        LOCATION_APPROXIMATE -> !Permissions.fineLocation(context) && Permissions.granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        BACKGROUND_LOCATION -> Permissions.fineLocation(context) && !Permissions.backgroundLocation(context)
        PHONE_PERMISSION -> !Permissions.phoneState(context)
        NEARBY_WIFI -> !Permissions.nearbyWifi(context)
        BATTERY_OPTIMISED -> !Permissions.batteryExempt(context) && !Checks.backgroundRestricted(context)
        BACKGROUND_RESTRICTED -> Checks.backgroundRestricted(context)
        NOTIFICATIONS_OFF -> !Checks.notifications(context)
        DATA_SAVER -> Checks.dataSaverLimits(context)
        AIRPLANE_MODE -> Checks.airplane(context)
        MOBILE_DATA_OFF -> !Checks.airplane(context) && Checks.mobileData(context) == false
        WIFI_OFF -> !Checks.wifiOn(context)
        POWER_SAVER -> Checks.powerSave(context)
        ACTIVITY_PERMISSION -> !Permissions.activityRecognition(context)
        LOW_STORAGE -> Checks.lowStorage(context)
    }

    companion object {
        val isSamsung: Boolean get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

        fun detect(context: Context): List<Limit> =
            entries.filter { runCatching { it.active(context) }.getOrDefault(false) }.sortedBy { it.severity.ordinal }

        private val samsungNeverSleeping = FixStep(
            "Samsung: add Tracer to Never sleeping apps",
            "Settings › Battery › Background usage limits › Never sleeping apps › + › Tracer. One UI otherwise puts unused apps to sleep.",
            act = FixAct.Open("Open Battery") { ctx ->
                listOf(
                    Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
                    Intent().setComponent(ComponentName("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity")),
                    Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
                    appInfoIntent(ctx),
                )
            },
        )

        private fun appInfo(label: String) = FixAct.Open(label) { ctx -> listOf(appInfoIntent(ctx)) }
        fun appInfoIntent(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        fun notificationSettings(ctx: Context) = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)

        /** Maps a collector banner's fix to the guide that covers it. */
        fun forCollectorFix(fix: app.tracer.core.runtime.FixAction, context: Context): Limit? = when (fix) {
            app.tracer.core.runtime.FixAction.DEVELOPER_OPTIONS -> WIFI_SCAN_THROTTLE
            app.tracer.core.runtime.FixAction.LOCATION_SETTINGS -> LOCATION_OFF
            app.tracer.core.runtime.FixAction.WIFI_SETTINGS -> WIFI_OFF
            app.tracer.core.runtime.FixAction.APP_PERMISSIONS -> detect(context).firstOrNull {
                it in setOf(LOCATION_PERMISSION, LOCATION_APPROXIMATE, BACKGROUND_LOCATION, PHONE_PERMISSION, NEARBY_WIFI, ACTIVITY_PERMISSION)
            }
            app.tracer.core.runtime.FixAction.TRACER_SETTINGS -> null
        }
    }
}

/** Opens the first intent that resolves; true when something opened. */
fun launchFirst(context: Context, intents: List<Intent>): Boolean {
    for (i in intents) {
        try {
            context.startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    return false
}

/** System state probes. All are cheap and safe to call every second. */
@SuppressLint("MissingPermission") // ACCESS_WIFI_STATE / ACCESS_NETWORK_STATE are declared in :app
object Checks {
    fun developerOptions(c: Context): Boolean =
        Settings.Global.getInt(c.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    /** WifiManager.isScanThrottleEnabled (API 30+); developer options off ⇒ throttled by default. */
    fun scanThrottled(c: Context): Boolean {
        val wm = c.applicationContext.getSystemService(WifiManager::class.java) ?: return false
        return runCatching { wm.isScanThrottleEnabled }.getOrDefault(!developerOptions(c))
    }

    fun locationOn(c: Context): Boolean = c.getSystemService(LocationManager::class.java)?.isLocationEnabled ?: true

    fun wifiScanningAlways(c: Context): Boolean = Settings.Global.getInt(c.contentResolver, "wifi_scan_always_enabled", 1) == 1

    fun backgroundRestricted(c: Context): Boolean = c.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true

    fun notifications(c: Context): Boolean =
        Permissions.notifications(c) && c.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false

    fun dataSaverLimits(c: Context): Boolean =
        c.getSystemService(ConnectivityManager::class.java)?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED

    fun airplane(c: Context): Boolean = Settings.Global.getInt(c.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    /** null = unknown (no permission / no SIM). */
    fun mobileData(c: Context): Boolean? = runCatching {
        val tm = c.getSystemService(TelephonyManager::class.java) ?: return null
        if (tm.simState != TelephonyManager.SIM_STATE_READY) return null
        tm.isDataEnabled
    }.getOrNull()

    fun wifiOn(c: Context): Boolean = c.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled ?: true

    fun powerSave(c: Context): Boolean = c.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

    fun lowStorage(c: Context, minMb: Long = 500): Boolean = c.filesDir.usableSpace < minMb * 1_048_576L
}
