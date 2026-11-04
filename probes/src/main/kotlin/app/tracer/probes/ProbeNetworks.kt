package app.tracer.probes

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import app.tracer.core.config.ProbePaths
import app.tracer.core.model.ProbeSample
import app.tracer.core.model.Stamp
import app.tracer.core.runtime.RunSignals
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A network a probe runs on.
 * @param iface transport name for probe.interface: wifi | cellular | ethernet | vpn | other (never wlan0/rmnet1)
 * @param ifname kernel interface name (wlan0, rmnet1 …) — for `ping -I`; not persisted in probe rows
 */
data class BoundNetwork(val network: Network, val iface: String, val isCellular: Boolean, val ifname: String? = null) {
    val isWifi: Boolean get() = iface == "wifi"
}

/** Transports a collector can hold a lease on (ConnectivityManager.requestNetwork). */
enum class LeaseTransport(val androidTransport: Int, val iface: String) {
    WIFI(NetworkCapabilities.TRANSPORT_WIFI, "wifi"),
    CELLULAR(NetworkCapabilities.TRANSPORT_CELLULAR, "cellular"),
}

/** Transport a [ProbePaths] mode needs a lease on (null = default network only). */
fun ProbePaths.leaseTransport(): LeaseTransport? = when (this) {
    ProbePaths.DEFAULT -> null
    ProbePaths.DUAL, ProbePaths.CELLULAR_ONLY -> LeaseTransport.CELLULAR
    ProbePaths.WIFI_ONLY -> LeaseTransport.WIFI
}

/** The transport a forced-path mode pins probes to (WIFI_ONLY / CELLULAR_ONLY), else null. */
fun ProbePaths.forcedTransport(): LeaseTransport? = when (this) {
    ProbePaths.WIFI_ONLY -> LeaseTransport.WIFI
    ProbePaths.CELLULAR_ONLY -> LeaseTransport.CELLULAR
    else -> null
}

/**
 * The success=false, error="no_network" rows for a probe that had no network to run on: one row, or
 * none in WIFI_ONLY mode (Wi-Fi off / not connected is "Wi-Fi not tested", not a failed probe).
 */
fun noNetworkSamples(runId: String, stamp: Stamp, probeType: String, endpoint: String, paths: ProbePaths): List<ProbeSample> =
    if (paths.emitsNoNetworkRows()) listOf(noNetworkSample(runId, stamp, probeType, endpoint, paths)) else emptyList()

/**
 * A success=false, error="no_network" row for a probe that had no network to run on. In a forced-path
 * mode the row carries that path's transport, so "Wi-Fi was down" is distinguishable from "offline".
 */
fun noNetworkSample(runId: String, stamp: Stamp, probeType: String, endpoint: String, paths: ProbePaths) =
    ProbeSample(
        runId = runId, stamp = stamp, probeType = probeType, endpoint = endpoint, success = false, error = "no_network",
        iface = paths.forcedTransport()?.iface, isCellular = paths.forcedTransport()?.let { it == LeaseTransport.CELLULAR },
    )

/**
 * Resolves the default network, holds ref-counted requested networks (Wi-Fi / mobile data) for the
 * probe-path modes, and binds OkHttp clients to a network.
 */
@Singleton
class ProbeNetworks @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

    /** Shared by every probe client: nothing is kept idle, so every call opens a fresh connection. */
    private val noReusePool = ConnectionPool(0, 1, TimeUnit.SECONDS)

    private class Lease(val callback: ConnectivityManager.NetworkCallback) {
        @Volatile var network: Network? = null
        var count = 0
    }

    private val leases = HashMap<LeaseTransport, Lease>() // ≤ 2 entries

    /**
     * Keeps a network of [transport] requested (ConnectivityManager.requestNetwork with INTERNET) so
     * probes can run on it even when it is not the default — e.g. mobile data while on Wi-Fi.
     * Ref-counted across collectors; pair every call with [release].
     */
    @Synchronized
    fun acquire(transport: LeaseTransport) {
        val manager = cm ?: return
        leases[transport]?.let { it.count++; return }
        lateinit var lease: Lease
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { lease.network = network }
            override fun onLost(network: Network) { if (lease.network == network) lease.network = null }
            override fun onUnavailable() { lease.network = null }
        }
        lease = Lease(cb)
        val request = NetworkRequest.Builder()
            .addTransportType(transport.androidTransport)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            manager.requestNetwork(request, cb)
            lease.count = 1
            leases[transport] = lease
        } catch (e: RuntimeException) {
            // SecurityException (CHANGE_NETWORK_STATE missing) or too many requests — no leased path.
        }
    }

    @Synchronized
    fun release(transport: LeaseTransport) {
        val lease = leases[transport] ?: return
        if (--lease.count > 0) return
        leases.remove(transport)
        lease.network = null
        try { cm?.unregisterNetworkCallback(lease.callback) } catch (e: IllegalArgumentException) { /* already gone */ }
    }

    /** Acquire whatever lease [paths] needs (no-op for DEFAULT). */
    fun acquireFor(paths: ProbePaths) { paths.leaseTransport()?.let(::acquire) }

    /** Counterpart of [acquireFor]. */
    fun releaseFor(paths: ProbePaths) { paths.leaseTransport()?.let(::release) }

    @Synchronized
    private fun leased(transport: LeaseTransport): Network? = leases[transport]?.network

    /** The leased network of [transport] as a [BoundNetwork], if it is up and really has that transport. */
    fun leasedNetwork(transport: LeaseTransport): BoundNetwork? {
        val network = leased(transport) ?: return null
        val bound = bind(network) ?: return null
        val caps = cm?.getNetworkCapabilities(network) ?: return null
        return if (caps.hasTransport(transport.androidTransport)) bound else null
    }

    /**
     * The networks a probe set must cover for [paths]; the first is the primary path. Empty = no
     * usable network for this mode (callers emit success=false, error="no_network" rows).
     *  - DEFAULT: the default network
     *  - DUAL: the default network, plus the leased mobile-data network while the default is not cellular
     *  - WIFI_ONLY: only a Wi-Fi network (leased; or the default when it is Wi-Fi) — never cellular
     *  - CELLULAR_ONLY: only a mobile-data network (leased; or the default when it is cellular)
     */
    fun networksFor(paths: ProbePaths): List<BoundNetwork> = when (paths) {
        ProbePaths.DEFAULT -> listOfNotNull(current())
        ProbePaths.DUAL -> {
            val default = current()
            val cell = if (default?.isCellular == true) null else leasedNetwork(LeaseTransport.CELLULAR)?.takeIf { it.network != default?.network }
            listOfNotNull(default, cell)
        }
        ProbePaths.WIFI_ONLY -> listOfNotNull(leasedNetwork(LeaseTransport.WIFI) ?: current()?.takeIf { isTransport(it.network, LeaseTransport.WIFI) })
        ProbePaths.CELLULAR_ONLY -> listOfNotNull(leasedNetwork(LeaseTransport.CELLULAR) ?: current()?.takeIf { isTransport(it.network, LeaseTransport.CELLULAR) })
    }

    /**
     * After [acquireFor], the requested network arrives asynchronously: waits up to [timeoutMs] for
     * the leased network so the first probe of a run does not report no_network needlessly.
     */
    suspend fun awaitLease(paths: ProbePaths, timeoutMs: Long = 3_000L) {
        val transport = paths.leaseTransport() ?: return
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (leasedNetwork(transport) == null && SystemClock.elapsedRealtime() < deadline) delay(100L)
    }


    /** true while [bound]'s network still exists (not lost / torn down). */
    fun isUp(bound: BoundNetwork): Boolean = try {
        cm?.getNetworkCapabilities(bound.network) != null
    } catch (e: SecurityException) {
        true
    }

    /**
     * Whether a probe row measured on [bound] should be written: always for a success; a failure on a
     * Wi-Fi path only while that Wi-Fi network still exists. A probe in flight when Wi-Fi is switched
     * off / lost must not be written as a failed Wi-Fi probe (the report shows "Wi-Fi not tested").
     */
    fun keepRow(bound: BoundNetwork, success: Boolean): Boolean = success || !bound.isWifi || isUp(bound)

    private fun isTransport(network: Network, transport: LeaseTransport): Boolean =
        cm?.getNetworkCapabilities(network)?.hasTransport(transport.androidTransport) == true

    /** Current default network, or null when there is none. */
    fun current(): BoundNetwork? {
        val manager = cm ?: return null
        val network = manager.activeNetwork ?: return null
        return bind(network)
    }

    /** LinkProperties of [network] (routes, DNS servers, interface name), if still known. */
    fun linkProperties(network: Network): LinkProperties? = try {
        cm?.getLinkProperties(network)
    } catch (e: SecurityException) {
        null
    }

    private fun bind(network: Network): BoundNetwork? {
        val manager = cm ?: return null
        val caps = manager.getNetworkCapabilities(network)
        val lp = manager.getLinkProperties(network)
        return BoundNetwork(
            network = network,
            iface = if (caps == null) "other" else transportName(
                wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            ),
            isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false,
            ifname = lp?.interfaceName,
        )
    }

    /**
     * A client whose sockets and DNS are bound to [network], with no connection reuse (every call
     * opens a fresh connection so DNS/TCP/TLS phases are measured each time).
     */
    fun client(base: OkHttpClient, network: Network, callTimeoutMs: Long): OkHttpClient =
        base.newBuilder()
            .connectionPool(noReusePool)
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
            })
            .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .build()

    /**
     * Network handle of the default network (-1 when none). Emits the current value first,
     * then on every change.
     */
    fun defaultNetworkHandles(): Flow<Long> = callbackFlow {
        val manager = cm
        if (manager == null) {
            trySend(-1L)
            awaitClose { }
            return@callbackFlow
        }
        trySend(manager.activeNetwork?.networkHandle ?: -1L)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(network.networkHandle)
            }

            override fun onLost(network: Network) {
                trySend(-1L)
            }
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
        } catch (e: RuntimeException) {
            // SecurityException (missing ACCESS_NETWORK_STATE) or too many callbacks — no change events.
            awaitClose { }
            return@callbackFlow
        }
        awaitClose {
            try {
                manager.unregisterNetworkCallback(callback)
            } catch (e: IllegalArgumentException) {
                // already unregistered
            }
        }
    }.conflate().distinctUntilChanged()
}

/** Suspends until the run is not paused. */
internal suspend fun RunSignals.awaitResumed() {
    paused.first { !it }
}

/**
 * probe row from an [HttpProbeResult] measured on [bound]: success = reachable, http_status = first
 * hop, timing phases of the first hop, redirect chain + [expected] from the end of the chain.
 */
internal fun HttpProbeResult.toProbeSample(
    runId: String,
    stamp: Stamp,
    probeType: String,
    endpoint: String,
    bound: BoundNetwork,
    expected: Boolean,
): ProbeSample = ProbeSample(
    runId = runId,
    stamp = stamp,
    probeType = probeType,
    endpoint = endpoint,
    success = success,
    error = error,
    dnsMs = phases.dnsMs,
    connectMs = phases.connectMs,
    tlsMs = phases.tlsMs,
    ttfbMs = phases.ttfbMs,
    totalMs = phases.totalMs,
    iface = bound.iface,
    isCellular = bound.isCellular,
    httpStatus = code,
    redirectCount = if (success) redirectCount else null,
    finalUrl = if (success) finalUrl else null,
    expectedResponse = if (success) expected else false,
    remoteIp = identity.remoteIp,
    ipVersion = identity.ipVersion,
    edgePop = identity.edgePop,
    edgeHeaders = identity.edgeHeaders,
    tlsCertSha256 = identity.tlsCertSha256,
)
