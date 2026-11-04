package app.tracer.ui.fix

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.tracer.core.runtime.CollectorStatusBoard
import app.tracer.core.runtime.FixAction
import app.tracer.ui.kit.*
import app.tracer.ui.settings.SettingsRepository
import app.tracer.ui.theme.TType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Opens a guided fix from anywhere below [GuidedFixHost]. */
class Guides(val open: (Limit) -> Unit, val openForCollector: (FixAction) -> Boolean, val limits: List<Limit>)

val LocalGuides = staticCompositionLocalOf { Guides({}, { false }, emptyList()) }

/**
 * Watches for phone limits (every 10 s, on resume, and whenever a collector reports a problem) and
 * pops the guided fix the first time an important one appears in this app session.
 */
@HiltViewModel
class LimitsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    statusBoard: CollectorStatusBoard,
) : ViewModel() {
    private val _limits = MutableStateFlow<List<Limit>>(emptyList())
    val limits: StateFlow<List<Limit>> = _limits.asStateFlow()

    private val _shown = MutableStateFlow<Limit?>(null)
    val shown: StateFlow<Limit?> = _shown.asStateFlow()

    private val seen = HashSet<Limit>()
    private var muted: Set<String> = emptySet()
    /** popups are suppressed while the welcome flow is on screen */
    var autoEnabled = false
        set(v) { field = v; if (v) maybePop() }

    init {
        viewModelScope.launch { settings.mutedLimits.collect { muted = it } }
        viewModelScope.launch { while (true) { refresh(); delay(10_000) } }
        viewModelScope.launch {
            statusBoard.state.map { m -> m.values.mapNotNull { it.fix }.toSet() }.distinctUntilChanged().collect { if (it.isNotEmpty()) refresh() }
        }
    }

    fun refresh() {
        _limits.value = Limit.detect(context)
        maybePop()
    }

    private fun maybePop() {
        if (!autoEnabled || _shown.value != null) return
        val next = _limits.value.firstOrNull { it.autoPopup && it !in seen && it.name !in muted } ?: return
        seen += next
        _shown.value = next
    }

    fun open(l: Limit) { seen += l; _shown.value = l }
    fun close(mute: Boolean) {
        val l = _shown.value ?: return
        if (mute) viewModelScope.launch { settings.setLimitMuted(l.name, true) }
        _shown.value = null
        refresh()
    }
}

/** Wrap the app content: provides [LocalGuides] and shows the guided-fix sheet. */
@Composable
fun GuidedFixHost(autoPopups: Boolean, vm: LimitsViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val limits by vm.limits.collectAsStateWithLifecycle()
    val shown by vm.shown.collectAsStateWithLifecycle()
    LaunchedEffect(autoPopups) { vm.autoEnabled = autoPopups }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val guides = remember(limits) {
        Guides(
            open = vm::open,
            openForCollector = { fix -> Limit.forCollectorFix(fix, context)?.let { vm.open(it); true } ?: false },
            limits = limits,
        )
    }
    CompositionLocalProvider(LocalGuides provides guides) { content() }
    shown?.let { l -> GuidedFixSheet(l, onDone = { vm.close(false) }, onMute = { vm.close(true) }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuidedFixSheet(limit: Limit, onDone: () -> Unit, onMute: () -> Unit) {
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    LaunchedEffect(limit) { while (true) { delay(1_500); tick++ } }
    val steps = limit.steps
    val done = remember(tick, limit) { steps.map { s -> s.done?.let { runCatching { it(context) }.getOrDefault(false) } } }
    val fixed = remember(tick, limit) { !runCatching { limit.active(context) }.getOrDefault(true) }
    LaunchedEffect(fixed) { if (fixed) { delay(1_800); onDone() } }

    var pendingFallback by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        tick++
        // Android stops showing the prompt after two denials: send the user to App info instead.
        if (res.values.any { !it } && pendingFallback) launchFirst(context, listOf(Limit.appInfoIntent(context)))
        pendingFallback = false
    }
    val run: (FixAct) -> Unit = { a ->
        when (a) {
            is FixAct.Open -> if (!launchFirst(context, a.intents(context))) launchFirst(context, listOf(Limit.appInfoIntent(context)))
            is FixAct.Request -> { pendingFallback = true; request.launch(a.permissions.toTypedArray()) }
        }
    }
    val current = steps.indices.firstOrNull { i -> steps[i].act != null && done[i] != true }

    ModalBottomSheet(onDismissRequest = onDone, sheetState = sheet, containerColor = tc.bg, dragHandle = null) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = K.screenPad).padding(top = 22.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (fixed) "Fixed" else when (limit.severity) { Severity.BLOCKER -> "Needed for tests"; Severity.IMPORTANT -> "Limits this test"; Severity.NOTE -> "Worth fixing" },
                    style = TType.caps, color = if (fixed) tc.veryGood else when (limit.severity) {
                        Severity.BLOCKER -> tc.bad; Severity.IMPORTANT -> tc.belowAvg; Severity.NOTE -> tc.average })
                Spacer(Modifier.weight(1f))
                Text("Close", style = TType.body, color = tc.link, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickableNoRipple(onDone).padding(6.dp))
            }
            Text(if (fixed) "All set" else limit.title, style = TType.largeTitle.copy(fontSize = 30.sp), color = tc.text, modifier = Modifier.padding(top = 4.dp))
            Text(limit.why, style = TType.sub.copy(fontSize = 16.sp), color = tc.text2, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))

            Card(padding = PaddingValues(vertical = 6.dp)) {
                steps.forEachIndexed { i, s ->
                    val ok = done[i] == true || (s.done == null && fixed)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                        StepBadge(i + 1, ok, active = i == current && !fixed)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = TType.body.copy(fontWeight = FontWeight.SemiBold), color = if (ok) tc.text3 else tc.text)
                            Text(s.detail, style = TType.sub, color = tc.text3, modifier = Modifier.padding(top = 2.dp))
                            val a = s.act
                            if (a != null && !ok && i != current) {
                                Text(label(a), style = TType.label, color = tc.link,
                                    modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).clickableNoRipple { run(a) }.padding(vertical = 2.dp))
                            }
                        }
                    }
                    if (i < steps.lastIndex) androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 62.dp), thickness = 0.5.dp, color = tc.sep)
                }
            }
            Spacer(Modifier.height(18.dp))
            val cur = current?.let { steps[it].act }
            when {
                fixed -> StatusCapsule("Tracer confirmed the change", tc.veryGood, tc.veryGoodBg)
                cur != null -> Pill(label(cur), { run(cur) }, Modifier.fillMaxWidth(), kind = PillKind.BLUE)
                else -> Pill("Done", onDone, Modifier.fillMaxWidth())
            }
            if (!fixed) {
                Text("Tracer checks again when you come back.", style = TType.footnote.copy(fontSize = 14.sp), color = tc.text3,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
                    Text("Not now", style = TType.label, color = tc.link, modifier = Modifier.clickableNoRipple(onDone).padding(10.dp))
                    if (limit.autoPopup) Text("Don't remind me", style = TType.label, color = tc.text3, modifier = Modifier.clickableNoRipple(onMute).padding(10.dp))
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
}

private fun label(a: FixAct) = when (a) { is FixAct.Open -> a.label; is FixAct.Request -> a.label }

@Composable
private fun StepBadge(n: Int, done: Boolean, active: Boolean) {
    Box(
        Modifier.size(30.dp).clip(CircleShape)
            .then(if (done) Modifier.background(tc.veryGood) else if (active) Modifier.background(tc.link) else Modifier.border(2.dp, tc.text4, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (done) "✓" else "$n", style = TType.label, color = if (done || active) Color.White else tc.text3)
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

// ------------------------------------------------------------------------- Phone setup checklist

/** Settings › Phone setup: every limit with its live status. */
@Composable
fun PhoneSetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val guides = LocalGuides.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    LaunchedEffect(Unit) { while (true) { delay(2_000); tick++ } }
    val states = remember(tick) { Limit.entries.map { it to runCatching { it.active(context) }.getOrDefault(false) } }
    val toFix = states.count { it.second }
    Screen {
        TopLinks(back = "Settings", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TitleBlock(eyebrow = if (toFix == 0) "Everything Tracer needs is on" else "$toFix ${if (toFix == 1) "thing" else "things"} to fix",
                title = "Phone setup")
            val groups = listOf(Severity.BLOCKER to "Needed", Severity.IMPORTANT to "Affects results", Severity.NOTE to "Good to have")
            for ((sev, cap) in groups) {
                val rows = states.filter { it.first.severity == sev }
                Caption(cap)
                GroupCard(rows = rows.map<Pair<Limit, Boolean>, @Composable () -> Unit> { (l, on) ->
                    {
                        ListRow(
                            title = if (on) l.title else okTitle(l),
                            sub = if (on) l.short else null,
                            leading = { Dot(if (on) when (sev) { Severity.BLOCKER -> tc.bad; Severity.IMPORTANT -> tc.belowAvg; Severity.NOTE -> tc.average } else tc.veryGood, 10.dp) },
                            value = if (on) "Fix" else null,
                            onClick = { guides.open(l) },
                        )
                    }
                }, inset = 42.dp)
            }
            if (Limit.isSamsung) {
                Caption("Samsung")
                Card { Text("One UI can still put Tracer to sleep. Settings › Battery › Background usage limits › Never sleeping apps › add Tracer.",
                    style = TType.sub, color = tc.text2) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun okTitle(l: Limit): String = when (l) {
    Limit.WIFI_SCAN_THROTTLE -> "Wi-Fi scans unlimited"
    Limit.LOCATION_OFF -> "Location on"
    Limit.LOCATION_PERMISSION -> "Location allowed"
    Limit.LOCATION_APPROXIMATE -> "Precise location"
    Limit.BACKGROUND_LOCATION -> "Location all the time"
    Limit.PHONE_PERMISSION -> "Phone access allowed"
    Limit.NEARBY_WIFI -> "Nearby Wi-Fi allowed"
    Limit.BATTERY_OPTIMISED -> "Battery optimisation off"
    Limit.BACKGROUND_RESTRICTED -> "Battery use not restricted"
    Limit.NOTIFICATIONS_OFF -> "Notifications on"
    Limit.DATA_SAVER -> "Data Saver not limiting"
    Limit.AIRPLANE_MODE -> "Airplane mode off"
    Limit.MOBILE_DATA_OFF -> "Mobile data on"
    Limit.WIFI_OFF -> "Wi-Fi on"
    Limit.POWER_SAVER -> "Battery saver off"
    Limit.ACTIVITY_PERMISSION -> "Motion allowed"
    Limit.LOW_STORAGE -> "Enough storage"
}
