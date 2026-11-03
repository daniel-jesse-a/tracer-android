package app.tracer.collectors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.telephony.SubscriptionManager
import app.tracer.core.runtime.DeviceInfoProvider
import app.tracer.core.runtime.SimInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Android implementation of [DeviceInfoProvider] (pre-flight checks, run metadata). */
@Singleton
class DeviceInfoProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanCache: WifiScanCache,
) : DeviceInfoProvider {

    @SuppressLint("MissingPermission")
    override fun activeSims(): List<SimInfo> {
        if (!context.hasPermission(Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val defaultData = SubscriptionManager.getDefaultDataSubscriptionId()
        val subs = frameworkCall { sm.activeSubscriptionInfoList }.orEmpty()
        return subs.map { s ->
            val mcc = s.mccString
            val mnc = s.mncString
            SimInfo(
                subscriptionId = s.subscriptionId,
                slotIndex = s.simSlotIndex,
                carrierName = s.carrierName?.toString(),
                mccMnc = if (mcc != null && mnc != null) mcc + mnc else null,
                isDefaultData = s.subscriptionId == defaultData,
                isEmbedded = s.isEmbedded,
            )
        }
    }

    override fun wifiScanThrottled(): Boolean? = scanCache.throttled

    override fun isIgnoringBatteryOptimizations(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    override fun freeStorageBytes(): Long = try {
        StatFs(context.filesDir.path).availableBytes
    } catch (e: IllegalArgumentException) {
        0L
    }

    override fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    override fun osVersion(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    override fun appVersion(): String = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        "${info.versionName ?: "?"} (${info.longVersionCode})"
    } catch (e: PackageManager.NameNotFoundException) {
        "unknown"
    }
}
