package app.tracer

import android.Manifest
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tracer.core.config.RunSettings
import app.tracer.core.model.RunState
import app.tracer.core.model.Stamp
import app.tracer.core.model.Table
import app.tracer.core.model.WaypointSample
import app.tracer.core.runtime.RunController
import app.tracer.core.runtime.RunRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * End-to-end RunService lifecycle on a device/emulator using the debug "Simulate run" mode, so no
 * real radio conditions are needed: start (with a site) → RECORDING with events → waypoint → pause →
 * resume → stop → IDLE with endedAt, site/test number/time zone/method fingerprint persisted.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RunServiceLifecycleTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var controller: RunController
    @Inject lateinit var repository: RunRepository

    @Before
    fun setUp() {
        hilt.inject()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pkg = instrumentation.targetContext.packageName
        val automation = instrumentation.uiAutomation
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        perms.forEach { automation.grantRuntimePermission(pkg, it) }
        // Power-save allowlisted apps are exempt from background FGS-start restrictions, so the
        // test does not depend on an activity being in the foreground.
        automation.executeShellCommand("cmd deviceidle whitelist +$pkg").use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        }
    }

    @Test
    fun simulatedRun_startPauseResumeStop() = runBlocking<Unit> {
        assertEquals(RunState.IDLE, controller.state.value)

        val site = "Lifecycle"
        val expectedTest = repository.nextTestNumber(site)
        controller.start("Lifecycle test", "drive", RunSettings(), simulateScenario = "congestion", site = site, protocolId = null)
        withTimeout(90_000) {
            controller.state.first { it == RunState.RECORDING }
            controller.live.first { it.eventCount > 0 } // congestion scenario fires latency events at t≈40 s
        }
        val runId = controller.runId.value
        assertNotNull(runId)

        val tap = Stamp(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtimeNanos())
        controller.markWaypoint("door B", tap)

        controller.pause()
        withTimeout(15_000) { controller.state.first { it == RunState.PAUSED } }

        controller.resume()
        withTimeout(15_000) { controller.state.first { it == RunState.RECORDING } }

        controller.stop()
        withTimeout(30_000) { controller.state.first { it == RunState.IDLE } }

        val run = repository.getRun(runId!!)
        assertNotNull(run)
        assertNotNull("endedAt must be set after stop", run!!.endedAt)
        assertEquals("simulated", run.mode)
        assertNotNull(run.completenessJson)
        assertEquals(site, run.site)
        assertEquals(expectedTest, run.testNumber)
        assertTrue(run.name, run.name.startsWith("$site · T$expectedTest · "))
        assertNotNull(run.timeZone)
        assertNotNull(run.methodFingerprint)
        assertEquals(null, run.protocol)

        val waypoints = repository.samples(runId, Table.WAYPOINT).filterIsInstance<WaypointSample>()
        assertEquals(1, waypoints.size)
        assertEquals(1, waypoints.single().seq)
        assertEquals("door B", waypoints.single().label)
        assertEquals(tap.tMonoNs, waypoints.single().stamp.tMonoNs)
        // collector health is no longer written as annotations
        assertTrue(repository.samples(runId, Table.ANNOTATION).none { (it as? app.tracer.core.model.AnnotationSample)?.text?.startsWith("collector:") == true })
    }
}
