package app.tracer.ui.welcome

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import app.tracer.ui.kit.Card
import app.tracer.ui.kit.Pill
import app.tracer.ui.kit.Screen
import app.tracer.ui.kit.tc
import app.tracer.ui.onboarding.Permissions
import app.tracer.ui.settings.SettingsRepository
import app.tracer.ui.theme.TType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WelcomeViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {
    /** Marks the welcome as seen, then [onDone] (after the write, so a relaunch skips Welcome). */
    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { settings.setWelcomed() }
            onDone()
        }
    }
}

private enum class PermKind { LOCATION, PHONE_WIFI, MOTION, NOTIFICATIONS, BATTERY }

private data class PermRow(val kind: PermKind, val title: String, val sub: String, val icon: ImageVector, val granted: Boolean)

private fun permRows(context: Context): List<PermRow> = listOf(
    PermRow(PermKind.LOCATION, "Location, all the time", "Your path on the map", Icons.Outlined.LocationOn,
        Permissions.fineLocation(context) && Permissions.backgroundLocation(context)),
    PermRow(PermKind.PHONE_WIFI, "Phone & Wi-Fi", "Signal and networks", Icons.Outlined.Wifi,
        Permissions.phoneState(context) && Permissions.nearbyWifi(context)),
    PermRow(PermKind.MOTION, "Motion", "Walking, driving or still", Icons.AutoMirrored.Filled.DirectionsWalk,
        Permissions.activityRecognition(context)),
    PermRow(PermKind.NOTIFICATIONS, "Notifications", "Control from lock screen", Icons.Outlined.Notifications,
        Permissions.notifications(context)),
    PermRow(PermKind.BATTERY, "Battery", "Long tests stay running", Icons.Outlined.BatteryFull,
        Permissions.batteryExempt(context)),
)

/**
 * Design frame 0. Each row requests its permission in place; statuses are re-read on every
 * ON_RESUME (the user may come back from system settings). Continue needs precise location and
 * phone state ([Permissions.hasRequired]); the rest are recommended.
 */
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val vm: WelcomeViewModel = hiltViewModel()
    val context = LocalContext.current
    val activity = LocalActivity.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    // permissions asked at least once (to detect "don't ask again" → app settings)
    var asked by rememberSaveable { mutableStateOf(listOf<String>()) }
    val multi = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val single = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    val rows = remember(refresh) { permRows(context) }
    val canContinue = remember(refresh) { Permissions.hasRequired(context) }
    var finishing by remember { mutableStateOf(false) }

    fun ask(perms: List<String>) {
        if (perms.isEmpty()) return
        val blocked = activity != null && perms.all { it in asked } &&
            perms.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        if (blocked) { openAppSettings(context); return }
        asked = (asked + perms).distinct()
        if (perms.size == 1) single.launch(perms[0]) else multi.launch(perms.toTypedArray())
    }

    fun request(kind: PermKind) {
        when (kind) {
            PermKind.LOCATION ->
                if (!Permissions.fineLocation(context)) ask(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                // API 30+: opens the system page with "Allow all the time"
                else ask(listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            PermKind.PHONE_WIFI -> ask(buildList {
                if (!Permissions.phoneState(context)) add(Manifest.permission.READ_PHONE_STATE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Permissions.nearbyWifi(context)) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            })
            PermKind.MOTION -> ask(listOf(Manifest.permission.ACTIVITY_RECOGNITION))
            PermKind.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ask(listOf(Manifest.permission.POST_NOTIFICATIONS))
            PermKind.BATTERY -> openBatteryExemption(context)
        }
    }

    Screen {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(56.dp))
            AppIcon()
            Spacer(Modifier.height(20.dp))
            Text(
                "See exactly where your connection changes.",
                style = TType.largeTitle.copy(fontSize = 34.sp, lineHeight = 38.sp), color = tc.text,
            )
            Spacer(Modifier.height(10.dp))
            Text("Record. Replay. Understand.", style = TType.sub.copy(fontSize = 17.sp), color = tc.text2)
            Spacer(Modifier.height(26.dp))
            Card(padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                rows.forEachIndexed { i, r ->
                    PermissionRow(r) { request(r.kind) }
                    if (i < rows.lastIndex) androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = tc.sep)
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, tint = tc.text3, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Stays on your phone until you share it.", style = TType.sub, color = tc.text3)
            }
            Spacer(Modifier.height(20.dp))
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Pill(
                "Continue",
                onClick = { if (!finishing) { finishing = true; vm.finish(onDone) } },
                modifier = Modifier.fillMaxWidth(),
                enabled = canContinue && !finishing,
            )
            Spacer(Modifier.height(10.dp))
            val allowed = rows.count { it.granted }
            val left = rows.size - allowed
            Text(
                when {
                    !canContinue -> "$allowed of ${rows.size} allowed · Location and Phone are needed"
                    left == 0 -> "All ${rows.size} allowed"
                    else -> "$allowed of ${rows.size} allowed · $left left"
                },
                style = TType.sub, color = tc.text3, textAlign = TextAlign.Center,
            )
        }
    }
}

/** White rounded square with a Wi-Fi-blue → mobile-orange squiggle. */
@Composable
private fun AppIcon() {
    val blue = tc.wifi
    val orange = tc.mobile
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Color.White), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(30.dp, 18.dp)) {
            val w = size.width; val h = size.height
            val stroke = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val first = Path().apply {
                moveTo(0.04f * w, 0.78f * h)
                cubicTo(0.16f * w, 0.30f * h, 0.30f * w, 0.05f * h, 0.42f * w, 0.30f * h)
                cubicTo(0.48f * w, 0.45f * h, 0.52f * w, 0.62f * h, 0.58f * w, 0.66f * h)
            }
            val second = Path().apply {
                moveTo(0.58f * w, 0.66f * h)
                cubicTo(0.68f * w, 0.74f * h, 0.76f * w, 0.18f * h, 0.84f * w, 0.24f * h)
                cubicTo(0.90f * w, 0.28f * h, 0.93f * w, 0.42f * h, 0.96f * w, 0.48f * h)
            }
            drawPath(first, blue, style = stroke)
            drawPath(second, orange, style = stroke)
            drawCircle(orange, radius = 1.3.dp.toPx(), center = Offset(0.58f * w, 0.66f * h))
        }
    }
}

@Composable
private fun PermissionRow(r: PermRow, onAllow: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 66.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tc.card2), contentAlignment = Alignment.Center) {
            Icon(r.icon, null, tint = tc.text, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, style = TType.body.copy(fontWeight = FontWeight.SemiBold), color = tc.text, maxLines = 1)
            Text(r.sub, style = TType.sub, color = tc.text3, maxLines = 1)
        }
        if (r.granted) {
            Icon(Icons.Filled.Check, contentDescription = "Allowed", tint = tc.veryGood, modifier = Modifier.padding(end = 4.dp).size(24.dp))
        } else {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(tc.linkBg).clickable(onClick = onAllow)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text("Allow", style = TType.label.copy(fontSize = 16.sp), color = tc.link) }
        }
    }
}

/** System dialog "Let app always run in background?"; falls back to the global list. */
fun openBatteryExemption(context: Context) {
    @Suppress("BatteryLife") // a field logger legitimately needs the exemption; explained in-app first
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    try {
        context.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** Android › Apps › Tracer. */
fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
