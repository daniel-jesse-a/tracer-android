package app.tracer.ui.nav

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import app.tracer.ui.R
import app.tracer.ui.companion.CompanionScreen
import app.tracer.ui.library.RunLibraryScreen
import app.tracer.ui.live.LiveRunRoute
import app.tracer.ui.onboarding.OnboardingScreen
import app.tracer.ui.onboarding.Permissions
import app.tracer.ui.preflight.PreflightScreen
import app.tracer.ui.replay.ReplayRoute
import app.tracer.ui.settings.SettingsScreen
import app.tracer.ui.summary.EndRunSummaryRoute
import app.tracer.ui.transfer.TransferScreen
import app.tracer.ui.worst.WorstSegmentsRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Route names (string routes, Navigation-Compose 2.9). */
object Routes {
    const val ONBOARDING = "onboarding"
    const val PREFLIGHT = "preflight"
    const val LIVE = "live"
    const val SUMMARY = "summary/{runId}"
    const val LIBRARY = "library"
    const val REPLAY = "replay/{runId}?atMonoNs={atMonoNs}"
    const val WORST = "worst/{runId}"
    const val TRANSFER = "transfer/{runId}"
    const val SETTINGS = "settings"
    const val COMPANION = "companion"

    fun summary(runId: String) = "summary/$runId"
    fun replay(runId: String, atMonoNs: Long? = null) = if (atMonoNs == null) "replay/$runId" else "replay/$runId?atMonoNs=$atMonoNs"
    fun worst(runId: String) = "worst/$runId"
    fun transfer(runId: String) = "transfer/$runId"
}

@HiltViewModel
class NavViewModel @Inject constructor(val controller: RunController) : ViewModel()

private data class Tab(val route: String, @StringRes val label: Int, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.PREFLIGHT, R.string.nav_preflight, Icons.Outlined.Home),
    Tab(Routes.LIBRARY, R.string.nav_runs, Icons.AutoMirrored.Outlined.List),
    Tab(Routes.COMPANION, R.string.nav_companion, Icons.Outlined.Devices),
    Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Outlined.Settings),
)

/**
 * App navigation. Bottom NavigationBar (Pre-flight · Runs · Companion · Settings) on top-level
 * routes; Live Run and detail screens are full-screen.
 *
 * @param startOnLive opened from the run notification
 * @param openLiveSignal incremented by MainActivity when the notification is tapped while alive
 */
@Composable
fun TracerNavHost(startOnLive: Boolean, openLiveSignal: Int = 0, viewModel: NavViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val nav = rememberNavController()
    val runState by viewModel.controller.state.collectAsStateWithLifecycle()
    val start = remember { if (Permissions.hasRequired(context)) Routes.PREFLIGHT else Routes.ONBOARDING }
    var handledInitialLive by rememberSaveable { mutableStateOf(false) }
    val active = runState == RunState.RECORDING || runState == RunState.PAUSED

    // jump to Live once at launch when a run is active (or the notification asked for it)
    LaunchedEffect(active, startOnLive) {
        if (!handledInitialLive && (active || startOnLive) && start != Routes.ONBOARDING) {
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
    val showBar = TABS.any { it.route == currentRoute }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = start,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(onDone = {
                    if (nav.previousBackStackEntry != null) {
                        nav.popBackStack()
                    } else {
                        nav.navigate(Routes.PREFLIGHT) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                    }
                })
            }
            composable(Routes.PREFLIGHT) {
                PreflightScreen(
                    onStarted = { nav.navigate(Routes.LIVE) { launchSingleTop = true } },
                    onFixPermissions = { nav.navigate(Routes.ONBOARDING) },
                    onOpenCompanion = { nav.navigateTab(Routes.COMPANION) },
                )
            }
            composable(Routes.LIVE) {
                LiveRunRoute(
                    onRunEnded = { runId ->
                        nav.navigate(Routes.summary(runId)) { popUpTo(Routes.LIVE) { inclusive = true } }
                    },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.SUMMARY, arguments = listOf(navArgument("runId") { type = NavType.StringType })) { entry ->
                val runId = entry.arguments?.getString("runId").orEmpty()
                EndRunSummaryRoute(
                    runId = runId,
                    onDone = { nav.navigateTab(Routes.LIBRARY) },
                    onOpenReplay = { id -> nav.navigate(Routes.replay(id)) },
                    onExport = { id -> nav.navigate(Routes.transfer(id)) },
                )
            }
            composable(Routes.LIBRARY) {
                RunLibraryScreen(
                    onOpenRun = { id -> nav.navigate(Routes.replay(id)) },
                    onExport = { id -> nav.navigate(Routes.transfer(id)) },
                    onOpenLive = { nav.navigate(Routes.LIVE) { launchSingleTop = true } },
                )
            }
            composable(
                Routes.REPLAY,
                arguments = listOf(
                    navArgument("runId") { type = NavType.StringType },
                    navArgument("atMonoNs") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                ReplayRoute(
                    runId = entry.arguments?.getString("runId").orEmpty(),
                    atMonoNs = entry.arguments?.getString("atMonoNs")?.toLongOrNull(),
                    onBack = { nav.popBackStack() },
                    onWorstSegments = { id -> nav.navigate(Routes.worst(id)) },
                    onExport = { id -> nav.navigate(Routes.transfer(id)) },
                )
            }
            composable(Routes.WORST, arguments = listOf(navArgument("runId") { type = NavType.StringType })) { entry ->
                WorstSegmentsRoute(
                    runId = entry.arguments?.getString("runId").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenAt = { id, mono -> nav.navigate(Routes.replay(id, mono)) },
                )
            }
            composable(Routes.TRANSFER, arguments = listOf(navArgument("runId") { type = NavType.StringType })) { entry ->
                TransferScreen(runId = entry.arguments?.getString("runId").orEmpty(), onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                // as a tab there is nothing to go back to; pushed from Live it shows a back arrow
                val pushed = nav.previousBackStackEntry?.destination?.route == Routes.LIVE
                SettingsScreen(onBack = if (pushed) ({ nav.popBackStack(); Unit }) else null)
            }
            composable(Routes.COMPANION) {
                CompanionScreen(onOpenRun = { id -> nav.navigate(Routes.replay(id)) })
            }
        }
    }
}

/** Standard bottom-nav navigation: single top, state saved/restored, rooted at Pre-flight. */
private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(Routes.PREFLIGHT) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
