package app.tracer.ui.onboarding

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
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.tracer.ui.R
import app.tracer.ui.common.GroupedRow
import app.tracer.ui.common.ReadinessStatus
import app.tracer.ui.common.StatusChip
import app.tracer.ui.theme.Space

private enum class StepKind { LOCATION, BACKGROUND, PHONE, NEARBY_WIFI, ACTIVITY, NOTIFICATIONS, BATTERY }

private data class Step(
    val kind: StepKind,
    @StringRes val title: Int,
    @StringRes val rationale: Int,
    val granted: Boolean,
    /** false while a prerequisite step is missing (background needs fine location first). */
    val enabled: Boolean,
    val required: Boolean,
    /** runtime permission name (null for the battery step) */
    val permission: String?,
)

/**
 * Guided permission onboarding. Each step explains why the permission is needed; statuses are
 * re-checked on every ON_RESUME (the user may come back from system settings).
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    // permissions the user has been asked for at least once (to detect "don't ask again")
    var asked by rememberSaveable { mutableStateOf(listOf<String>()) }
    val multiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val singleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    val steps = remember(refresh) { buildSteps(context) }
    val requiredOk = steps.filter { it.required }.all { it.granted }

    fun request(step: Step) {
        val perm = step.permission
        when {
            step.kind == StepKind.BATTERY -> openBatteryExemption(context)
            perm == null -> Unit
            // asked before, denied, and the system will no longer show a dialog → app settings
            perm in asked && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm) ->
                openAppSettings(context)
            step.kind == StepKind.LOCATION -> {
                asked = asked + perm
                multiLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            else -> {
                asked = asked + perm
                // API 30+: background location opens the system page with "Allow all the time"
                singleLauncher.launch(perm)
            }
        }
    }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l, vertical = Space.xl),
        ) {
            Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(Space.s))
            Text(stringResource(R.string.onboarding_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Space.xl))
            Column(verticalArrangement = Arrangement.spacedBy(Space.rowGap)) {
                steps.forEachIndexed { i, step ->
                    StepRow(i, steps.size, step) { request(step) }
                }
            }
            Spacer(Modifier.height(Space.l))
            Text(stringResource(R.string.onboarding_camera_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Space.xl))
            Button(onClick = onDone, enabled = requiredOk, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_continue))
            }
            if (!requiredOk) {
                Text(
                    stringResource(R.string.onboarding_required_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.s),
                )
            }
        }
    }
}

@Composable
private fun StepRow(index: Int, count: Int, step: Step, onRequest: () -> Unit) {
    GroupedRow(index, count) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(step.title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(step.rationale), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(Space.m))
        Column(horizontalAlignment = Alignment.End) {
            StatusChip(
                when {
                    step.granted -> ReadinessStatus.OK
                    step.required -> ReadinessStatus.BLOCKED
                    else -> ReadinessStatus.WARN
                },
            )
            if (!step.granted) {
                if (step.enabled) {
                    FilledTonalButton(onClick = onRequest, modifier = Modifier.padding(top = Space.s)) {
                        Text(stringResource(if (step.kind == StepKind.BACKGROUND) R.string.onboarding_allow_all_time else R.string.onboarding_allow))
                    }
                } else {
                    TextButton(onClick = {}, enabled = false) { Text(stringResource(R.string.onboarding_after_location)) }
                }
            }
        }
    }
}

private fun buildSteps(context: Context): List<Step> {
    val fine = Permissions.fineLocation(context)
    return buildList {
        add(Step(StepKind.LOCATION, R.string.perm_location, R.string.perm_location_why, fine, true, true, Manifest.permission.ACCESS_FINE_LOCATION))
        add(Step(StepKind.BACKGROUND, R.string.perm_background, R.string.perm_background_why, Permissions.backgroundLocation(context), fine, false, Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        add(Step(StepKind.PHONE, R.string.perm_phone, R.string.perm_phone_why, Permissions.phoneState(context), true, true, Manifest.permission.READ_PHONE_STATE))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Step(StepKind.NEARBY_WIFI, R.string.perm_nearby_wifi, R.string.perm_nearby_wifi_why, Permissions.nearbyWifi(context), true, false, Manifest.permission.NEARBY_WIFI_DEVICES))
        }
        add(Step(StepKind.ACTIVITY, R.string.perm_activity, R.string.perm_activity_why, Permissions.activityRecognition(context), true, false, Manifest.permission.ACTIVITY_RECOGNITION))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Step(StepKind.NOTIFICATIONS, R.string.perm_notifications, R.string.perm_notifications_why, Permissions.notifications(context), true, false, Manifest.permission.POST_NOTIFICATIONS))
        }
        add(Step(StepKind.BATTERY, R.string.perm_battery, R.string.perm_battery_why, Permissions.batteryExempt(context), true, false, null))
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

fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
