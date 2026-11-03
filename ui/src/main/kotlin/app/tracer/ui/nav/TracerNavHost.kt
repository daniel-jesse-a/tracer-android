package app.tracer.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.tracer.core.model.RunState
import app.tracer.core.runtime.RunController
import app.tracer.ui.companion.CompanionsScreen
import app.tracer.ui.diagnostics.DiagnosticsScreen
import app.tracer.ui.kit.tc
import app.tracer.ui.library.RunLibraryScreen
import app.tracer.ui.live.LiveRunRoute
import app.tracer.ui.moment.MomentScreen
import app.tracer.ui.newtest.NewTestScreen
import app.tracer.ui.onboarding.Permissions
import app.tracer.ui.replay.ReplayRoute
import app.tracer.ui.settings.SettingsRepository
import app.tracer.ui.settings.SettingsScreen
import app.tracer.ui.share.ShareScreen
import app.tracer.ui.signals.SignalsScreen
import app.tracer.ui.summary.TestSummaryScreen
import app.tracer.ui.tests.TestsScreen
import app.tracer.ui.theme.TType
import app.tracer.ui.transfer.TransferScreen
import app.tracer.ui.welcome.WelcomeScreen
import app.tracer.ui.worst.WorstSegmentsRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Design v2 navigation.
 *
 *  Welcome → Tests (tab) ─┬─ New test (tab, full screen) → Live → Signals
 *                         │                                 └→ Test summary
 *                         └─ Test summary → Diagnostics (Simple | Detailed) → Moment detail
 *                                        └→ Share
 *  Settings (tab) → Companions · (engineer view) All runs / Replay / Worst segments / Transfer
 */
object Routes {
    const val WELCOME = "welcome"
    const val TESTS = "tests"
    const val NEW_TEST = "newtest"
    const val SETTINGS = "settings"
    const val LIVE = "live"
    const val SIGNALS = "signals"
    const val SUMMARY = "summary/{runId}"
    const val DIAGNOSTICS = "diagnostics/{runId}"
    const val MOMENT = "moment/{runId}/{index}"
    const val SHARE = "share/{runId}"
    const val COMPANIONS = "companions"
    // engineer view
    const val LIBRARY = "library"
    const val REPLAY = "replay/{runId}?atMonoNs={atMonoNs}"
    const val WORST = "worst/{runId}"
    const val TRANSFER = "transfer/{runId}"

    fun summary(runId: String) = "summary/$runId"
    fun diagnostics(runId: String) = "diagnostics/$runId"
    fun moment(runId: String, index: Int) = "moment/$runId/$index"
    fun share(runId: String) = "share/$runId"
    fun replay(runId: String, atMonoNs: Long? = null) = if (atMonoNs == null) "replay/$runId" else "replay/$runId?atMonoNs=$atMonoNs"
    fun worst(runId: String) = "worst/$runId"
    fun transfer(runId: String) = "transfer/$runId"
}

@HiltViewModel
class NavViewModel @Inject constructor(val controller: RunController, val settings: SettingsRepository) : ViewModel()

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.TESTS, "Tests", Icons.AutoMirrored.Outlined.List),
    Tab(Routes.NEW_TEST, "New test", Icons.Outlined.Add),
    Tab(Routes.SETTINGS, "Settings", Icons.Outlined.Settings),
)
private val TAB_ROOTS = setOf(Routes.TESTS, Routes.SETTINGS)

/**
 * @param startOnLive opened from the run notification
 * @param openLiveSignal incremented by MainActivity when the notification is tapped while alive
 */
@Composable
fun TracerNavHost(startOnLive: Boolean, openLiveSignal: Int = 0, viewModel: NavViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val nav = rememberNavController()
    val runState by viewModel.controller.state.collectAsStateWithLifecycle()
    val start = remember {
        val welcomed = runBlocking { viewModel.settings.welcomed.first() }
        if (Permissions.hasRequired(context) && welcomed) Routes.TESTS else Routes.WELCOME
    }
    var handledInitialLive by rememberSaveable { mutableStateOf(false) }
    val active = runState == RunState.RECORDING || runState == RunState.PAUSED

    LaunchedEffect(active, startOnLive) {
        if (!handledInitialLive && (active || startOnLive) && start != Routes.WELCOME) {
            handledInitialLive = true
            if (active) nav.navigate(Routes.LIVE) { launchSingleTop = true }
        }
    }
    LaunchedEffect(openLiveSignal) {
        if (openLiveSignal > 0 && viewModel.controller.state.value.let { it == RunState.RECORDING || it == RunState.PAUSED }) {
            nav.navigate(Routes.LIVE) { launchSingleTop = true }
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = currentRoute in TAB_ROOTS
    val goLive = { nav.navigate(Routes.LIVE) { launchSingleTop = true } }
    val newTest = { if (active) goLive() else nav.navigate(Routes.NEW_TEST) { launchSingleTop = true } }

    Scaffold(
        containerColor = tc.bg,
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        bottomBar = {
            if (showBar) TabBar(currentRoute) { route ->
                if (route == Routes.NEW_TEST) newTest() else nav.navigateTab(route)
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = start,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding).background(tc.bg),
        ) {
            composable(Routes.WELCOME) {
                WelcomeScreen(onDone = {
                    if (nav.previousBackStackEntry != null) nav.popBackStack()
                    else nav.navigate(Routes.TESTS) { popUpTo(Routes.WELCOME) { inclusive = true } }
                })
            }
            composable(Routes.TESTS) {
                TestsScreen(
                    onOpenTest = { id -> nav.navigate(Routes.summary(id)) },
                    onNewTest = newTest,
                    onOpenLive = goLive,
                )
            }
            composable(Routes.NEW_TEST) {
                NewTestScreen(
                    onCancel = { nav.popBackStack() },
                    onStarted = { nav.navigate(Routes.LIVE) { popUpTo(Routes.NEW_TEST) { inclusive = true }; launchSingleTop = true } },
                    onFixPermissions = { nav.navigate(Routes.WELCOME) },
                    onOpenCompanions = { nav.navigate(Routes.COMPANIONS) },
                )
            }
            composable(Routes.LIVE) {
                LiveRunRoute(
                    onRunEnded = { runId -> nav.navigate(Routes.summary(runId)) { popUpTo(Routes.LIVE) { inclusive = true } } },
                    onOpenSignals = { nav.navigate(Routes.SIGNALS) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.SIGNALS) { SignalsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SUMMARY, arguments = listOf(navArgument("runId") { type = NavType.StringType })) { e ->
                val id = e.arguments?.getString("runId").orEmpty()
                TestSummaryScreen(
                    runId = id,
                    onBack = { if (!nav.popBackStack(Routes.TESTS, false)) nav.navigate(Routes.TESTS) { popUpTo(0) } },
                    onShare = { nav.navigate(Routes.share(id)) },
                    onDiagnostics = { nav.navigate(Routes.diagnostics(id)) },
                    onMoment = { i -> nav.navigate(Routes.moment(id, i)) },
                )
            }
            composable(Routes.DIAGNOSTICS, arguments = listOf(navArgument("runId") { type = NavType.StringType })) { e ->
                val id = e.arguments?.getString("runId").orEmpty()
                DiagnosticsScreen(
                    runId = id,
                    onBack = { nav.popBackStack() },
                    onShare = { nav.navigate(Routes.share(id)) },
                    onMoment = { i -> nav.navigate(Routes.moment(id, i)) },
                    onReplay = { mono -> nav.navigate(Routes.replay(id, mono)) },
                    onWorst = { nav.navigate(Routes.worst(id)) },
                )
            }
            composable(
                Routes.MOMENT,
                arguments = listOf(navArgument("runId") { type = NavType.StringType }, navArgument("index") { type = NavType.IntType }),
            ) { e ->
                val id = e.arguments?.getString("runId").orEmpty()
                MomentScreen(
                    runId = id,
                    index = e.arguments?.getInt("index") ?: 0,
                    onBack = { nav.popBackStack() },
                    onOpenIndex = { i -> nav.navigate(Routes.moment(id, i)) { popUpTo(Routes.MOMENT) { inclusive = true } } },
                )
            }
            composable(Routes.SHARE, arguments = listOf(navArgument("runId") { type = NavType.StringType })) { e ->
                ShareScreen(runId = e.arguments?.getString("runId").orEmpty(), onCancel = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                val pushed = nav.previousBackStackEntry?.destination?.route == Routes.LIVE
                SettingsScreen(
                    onBack = if (pushed) ({ nav.popBackStack(); Unit }) else null,
                    onOpenCompanions = { nav.navigate(Routes.COMPANIONS) },
                    onOpenLibrary = { nav.navigate(Routes.LIBRARY) },
                )
            }
            composable(Routes.COMPANIONS) {
                CompanionsScreen(onBack = { nav.popBackStack() }, onOpenRun = { id -> nav.navigate(Routes.summary(id)) })
            }
            // ---- engineer view
            composable(Routes.LIBRARY) {
                RunLibraryScreen(
                    onOpenRun = { id -> nav.navigate(Routes.replay(id)) },
                    onExport = { id -> nav.navigate(Routes.transfer(id)) },
                    onOpenLive = goLive,
                )
            }
            composable(
                Routes.REPLAY,
                arguments = listOf(
                    navArgument("runId") { type = NavType.StringType },
                    navArgument("atMonoNs") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { e ->
                ReplayRoute(
                    runId = e.arguments?.getString("runId").orEmpty(),
                    atMonoNs = e.arguments?.getString("atMonoNs")?.toLongOrNull(),
                    onBack = { nav.popBackStack() },
                    onWorstSegments = { id -> nav.navigate(Routes.worst(id)) },
                    onExport = { id -> nav.navigate(Routes.share(id)) },
                )
            }
            composable(Routes.WORST, arguments = listOf(navArgument("runId") { type = NavType.StringType })) { e ->
                WorstSegmentsRoute(
                    runId = e.arguments?.getString("runId").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenAt = { id, mono -> nav.navigate(Routes.replay(id, mono)) },
                )
            }
            composable(Routes.TRANSFER, arguments = listOf(navArgument("runId") { type = NavType.StringType })) { e ->
                TransferScreen(runId = e.arguments?.getString("runId").orEmpty(), onBack = { nav.popBackStack() })
            }
        }
    }
}

/** Design tab bar: hairline on top, icon over label, blue when selected. */
@Composable
private fun TabBar(current: String?, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(if (tc.dark) androidx.compose.ui.graphics.Color(0xF0121214) else tc.card)) {
        HorizontalDivider(thickness = 0.5.dp, color = tc.sep)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp)) {
            TABS.forEach { tab ->
                val sel = current == tab.route
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(tab.route) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(tab.icon, null, tint = if (sel) tc.link else tc.text3, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(tab.label, style = TType.footnote.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium), color = if (sel) tc.link else tc.text3)
                }
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(Routes.TESTS) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
