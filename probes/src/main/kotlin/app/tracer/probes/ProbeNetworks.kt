package app.tracer.probes

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.tracer.core.runtime.RunSignals
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
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

/** The default network a probe runs on. */
data class BoundNetwork(val network: Network, val iface: String?, val isCellular: Boolean)

/** Resolves and watches the system default network and binds OkHttp clients to it. */
@Singleton
class ProbeNetworks @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

    /** Current default network, or null when there is none. */
    fun current(): BoundNetwork? {
        val manager = cm ?: return null
        val network = manager.activeNetwork ?: return null
        val caps = manager.getNetworkCapabilities(network)
        val lp = manager.getLinkProperties(network)
        return BoundNetwork(
            network = network,
            iface = lp?.interfaceName,
            isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false,
        )
    }

    /**
     * A client whose sockets and DNS are bound to [network], with no connection reuse (every call
     * opens a fresh connection so DNS/TCP/TLS phases are measured each time).
     */
    fun client(base: OkHttpClient, network: Network, callTimeoutMs: Long): OkHttpClient =
        base.newBuilder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
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
