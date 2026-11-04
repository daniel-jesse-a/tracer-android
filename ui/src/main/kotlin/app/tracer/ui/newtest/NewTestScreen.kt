package app.tracer.ui.newtest

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracer.ui.kit.Caption
import app.tracer.ui.kit.Card
import app.tracer.ui.kit.Dot
import app.tracer.ui.kit.GroupCard
import app.tracer.ui.kit.K
import app.tracer.ui.kit.Pill
import app.tracer.ui.kit.RadioDot
import app.tracer.ui.kit.Ring
import app.tracer.ui.kit.Screen
import app.tracer.ui.kit.Segmented
import app.tracer.ui.kit.SelectCard
import app.tracer.ui.kit.Toggle
import app.tracer.ui.kit.TopLinks
import app.tracer.ui.kit.tc
import app.tracer.ui.preflight.CheckRow
import app.tracer.ui.preflight.CheckStatus
import app.tracer.ui.preflight.FixAction
import app.tracer.ui.preflight.PreflightViewModel
import app.tracer.ui.preflight.Readiness
import app.tracer.ui.theme.TType
import app.tracer.ui.welcome.openBatteryExemption

private data class MoveOption(val mode: String, val label: String, val icon: ImageVector)

private val MOVES = listOf(
    MoveOption("walk", "Walk", Icons.AutoMirrored.Filled.DirectionsWalk),
    MoveOption("drive", "Drive", Icons.Outlined.DirectionsCar),
    MoveOption("rail", "Train", Icons.Outlined.Train),
    MoveOption("static", "Stay still", Icons.Outlined.Schedule),
)

private data class NetOption(val key: String, val title: String, val sub: String)

private val NETS = listOf(
    NetOption("dual", "Wi-Fi + mobile", "Recommended"),
    NetOption("wifi", "Wi-Fi only", "This network"),
    NetOption("cellular", "Mobile only", "Coverage here"),
)

private val LENGTH_LABELS = listOf("5 min", "10 min", "15 min", "No limit")

/** Design frame 1: readiness, where / how / what / length, start. */
@Composable
fun NewTestScreen(
    onCancel: () -> Unit,
    onStarted: () -> Unit,
    onFixPermissions: () -> Unit,
    onOpenCompanions: () -> Unit,
) {
    val vm: PreflightViewModel = hiltViewModel()
    val context = LocalContext.current
    val readiness by vm.readiness.collectAsStateWithLifecycle()
    val setup by vm.setup.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val testNumber by vm.testNumber.collectAsStateWithLifecycle()
    val comparability by vm.comparability.collectAsStateWithLifecycle()
    val startCompanions by vm.startCompanions.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    val guides = app.tracer.ui.fix.LocalGuides.current
    val onFix: (FixAction) -> Unit = { fix ->
        when (fix) {
            FixAction.PERMISSIONS -> guides.limits.firstOrNull {
                it in setOf(app.tracer.ui.fix.Limit.LOCATION_PERMISSION, app.tracer.ui.fix.Limit.LOCATION_APPROXIMATE,
                    app.tracer.ui.fix.Limit.BACKGROUND_LOCATION, app.tracer.ui.fix.Limit.PHONE_PERMISSION, app.tracer.ui.fix.Limit.NEARBY_WIFI)
            }?.let { guides.open(it) } ?: onFixPermissions()
            FixAction.LOCATION_SETTINGS -> guides.open(app.tracer.ui.fix.Limit.LOCATION_OFF)
            FixAction.BATTERY -> if (!app.tracer.ui.onboarding.Permissions.batteryExempt(context)) guides.open(app.tracer.ui.fix.Limit.BATTERY_OPTIMISED) else openBatteryExemption(context)
            FixAction.RETRY_ENDPOINTS -> vm.checkEndpoints()
            FixAction.COMPANION -> onOpenCompanions()
            FixAction.STORAGE -> openSettings(context, Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        }
    }

    Screen {
        TopLinks(back = "Cancel", onBack = onCancel, title = "New test", backIsCancel = true)
        val r = readiness
        val st = settings
        if (r == null || st == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = tc.link) }
        } else {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            ReadinessCard(r, onFix)
            // phone settings that limit the result (scan throttling, data saver, …) not covered by the checks above
            val extra = guides.limits.filter {
                it.severity != app.tracer.ui.fix.Severity.NOTE || it == app.tracer.ui.fix.Limit.POWER_SAVER
            }
            if (extra.isNotEmpty()) {
                Spacer(Modifier.height(K.gap))
                GroupCard(rows = extra.map<app.tracer.ui.fix.Limit, @Composable () -> Unit> { l ->
                    { app.tracer.ui.kit.ListRow(l.title, sub = l.short, value = "Fix", onClick = { guides.open(l) }, leading = { app.tracer.ui.kit.Dot(tc.belowAvg, 10.dp) }) }
                }, inset = 42.dp)
            }

            Caption("Where")
            Card(padding = PaddingValues(horizontal = 18.dp, vertical = 0.dp)) {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Site", style = TType.bodyRegular, color = tc.text3, modifier = Modifier.width(68.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (setup.site.isEmpty()) Text("Office", style = TType.body.copy(fontWeight = FontWeight.SemiBold), color = tc.text4)
                        BasicTextField(
                            value = setup.site,
                            onValueChange = vm::setSite,
                            singleLine = true,
                            textStyle = TType.body.copy(fontWeight = FontWeight.SemiBold, color = tc.text),
                            cursorBrush = SolidColor(tc.link),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    val n = testNumber
                    if (setup.siteOrNull != null && n != null) Text("Test $n", style = TType.bodyRegular, color = tc.text3)
                }
            }

            Caption("How you’re moving")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MOVES.forEach { m ->
                    val sel = setup.mode == m.mode
                    SelectCard(sel, onClick = { vm.setMode(m.mode) }, modifier = Modifier.weight(1f).height(72.dp), radius = K.tileRadius) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(m.icon, null, tint = if (sel) tc.link else tc.text, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(m.label, style = TType.label, color = tc.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            Caption("What to test")
            GroupCard(
                rows = NETS.map { o ->
                    @Composable {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 62.dp).clickable { vm.setNetworks(o.key) }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioDot(setup.networks == o.key)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(o.title, style = TType.body.copy(fontWeight = FontWeight.SemiBold), color = tc.text)
                                Text(o.sub, style = TType.sub, color = tc.text3)
                            }
                        }
                    }
                },
            )

            Caption("Length")
            val lengthIdx = PreflightViewModel.LENGTHS.indexOf(setup.lengthMin).takeIf { it >= 0 } ?: 1
            Segmented(LENGTH_LABELS, lengthIdx, { i -> vm.setLengthMin(PreflightViewModel.LENGTHS[i]) })
            comparability?.let { c ->
                Text(
                    c.text, style = TType.sub, color = if (c.comparable) tc.text3 else tc.average,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }

            if (vm.isDebugBuild) {
                Spacer(Modifier.height(12.dp))
                Card(padding = PaddingValues(horizontal = 18.dp, vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Simulate run (debug)", style = TType.bodyRegular, color = tc.text, modifier = Modifier.weight(1f))
                        Toggle(setup.simulate, vm::setSimulate)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        // ---- start
        val companions = if (startCompanions) r.companions.size else 0
        val phones = companions + 1
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 8.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Pill(
                if (phones > 1) "Start on $phones phones" else "Start test",
                onClick = { vm.start(); onStarted() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !r.hardBlocked && setup.siteOrNull != null,
            )
            Spacer(Modifier.height(10.dp))
            val data = vm.mobileDataLabel(st, setup)
            val caption = when {
                r.hardBlocked -> "Fix the red items to start"
                setup.siteOrNull == null -> "Add a site name to start"
                phones > 1 -> "This phone + $companions ${if (companions == 1) "companion" else "companions"} · " + (data?.let { "$it each" } ?: "no mobile data")
                else -> data ?: "Wi-Fi only · no mobile data used"
            }
            Text(caption, style = TType.sub, color = tc.text3, textAlign = TextAlign.Center)
        }
        }
    }
}

@Composable
private fun ReadinessCard(r: Readiness, onFix: (FixAction) -> Unit) {
    val blocked = r.rows.any { it.status == CheckStatus.BLOCKED }
    Card(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ring(
                fraction = if (r.total > 0) r.passed.toFloat() / r.total else 0f,
                color = if (blocked) tc.bad else tc.veryGood,
                center = "${r.passed}/${r.total}", under = null, size = 62.dp, stroke = 6.dp,
            )
            Spacer(Modifier.width(18.dp))
            Column {
                Text(r.title, style = TType.title, color = tc.text)
                Text(r.sub, style = TType.sub.copy(fontSize = 16.sp), color = tc.text3)
            }
        }
        Spacer(Modifier.height(14.dp))
        r.rows.forEach { row ->
            HorizontalDivider(thickness = 0.5.dp, color = tc.sep)
            CheckLine(row, onFix)
        }
    }
}

@Composable
private fun CheckLine(row: CheckRow, onFix: (FixAction) -> Unit) {
    val fix = row.fix
    // companion rows always open Companions; other rows only when something needs fixing
    val tappable = fix != null && (fix == FixAction.COMPANION || row.status == CheckStatus.BLOCKED || row.status == CheckStatus.NOTE)
    val dot: Color = when (row.status) {
        CheckStatus.OK -> tc.veryGood
        CheckStatus.NOTE -> tc.average
        CheckStatus.BLOCKED -> tc.bad
        CheckStatus.PENDING -> tc.text4
    }
    val valueColor: Color = when (row.status) {
        CheckStatus.NOTE -> tc.average
        CheckStatus.BLOCKED -> tc.bad
        else -> tc.text3
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp)
            .then(if (tappable && fix != null) Modifier.clickable { onFix(fix) } else Modifier)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(dot, 8.dp)
        Spacer(Modifier.width(14.dp))
        Text(row.name, style = TType.bodyRegular, color = tc.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(12.dp).weight(1f))
        Text(row.value, style = TType.sub.copy(fontSize = 16.sp).merge(TType.num), color = valueColor, maxLines = 1, textAlign = TextAlign.End)
    }
}

private fun openSettings(context: Context, action: String) {
    runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
