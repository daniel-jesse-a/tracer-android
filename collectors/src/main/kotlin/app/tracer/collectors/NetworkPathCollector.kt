package app.tracer.collectors

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import app.tracer.core.model.AndroidNetworkSample
import app.tracer.core.model.NetworkPathSample
import app.tracer.core.model.Sample
import app.tracer.core.model.Stamp
import app.tracer.core.runtime.Collector
import app.tracer.core.runtime.CollectorContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.net.Inet6Address
import javax.inject.Inject

/**
 * [network_path] — the system default network as NetworkPathSample (iOS NWPath-compatible) plus
 * AndroidNetworkSample, emitted whenever the content changes.
 *
 * is_constrained is defined as !NET_CAPABILITY_NOT_CONGESTED (Android has no direct
 * equivalent of iOS "Low Data Mode" per network).
 */
class NetworkPathCollector @Inject constructor(
    @ApplicationContext private val context: Context,
) : Collector {
    override val id = "network_path"

    override fun samples(ctx: CollectorContext): Flow<Sample> = callbackFlow<Sample> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            ctx.status.unavailable(id, "Connectivity service unavailable.")
            awaitClose { }
            return@callbackFlow
        }
        val worker = Worker(id)
        val zero = Stamp(0, 0)

        // Worker-thread state.
        var current: Network? = null
        var caps: NetworkCapabilities? = null
        var link: LinkProperties? = null
        var isBlocked = false
        var lastPath: NetworkPathSample? = null
        var lastAndroid: AndroidNetworkSample? = null

        fun emitIfChanged(path: NetworkPathSample, android: AndroidNetworkSample?) {
            if (lastPath?.copy(stamp = zero) != path.copy(stamp = zero)) {
                lastPath = path
                channel.emitFromCallback(ctx, id, path)
            }
            if (android != null && lastAndroid?.copy(stamp = zero) != android.copy(stamp = zero)) {
                lastAndroid = android
                channel.emitFromCallback(ctx, id, android)
            }
        }

        fun emitNoNetwork() {
            val airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            val stamp = ctx.clock.now()
            emitIfChanged(
                NetworkPathSample(
                    runId = ctx.runId, stamp = stamp, status = "unsatisfied", interfaces = "",
                    primaryInterface = "none", unsatisfiedReason = if (airplane) "airplane_mode" else "no_network",
                ),
                AndroidNetworkSample(runId = ctx.runId, stamp = stamp),
            )
        }

        fun emitCurrent() {
            val network = current ?: return
            val c = caps ?: return
            val lp = link
            val stamp = ctx.clock.now()
            val transports = transportNames(c)
            val validated = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val captive = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            val (status, reason) = when {
                isBlocked -> "unsatisfied" to "blocked"
                captive -> "requires_connection" to "captive_portal"
                validated -> "satisfied" to null
                else -> "unsatisfied" to "not_validated"
            }
            val addresses = lp?.linkAddresses.orEmpty().map { it.address }
            val ipv4 = addresses.firstOrNull { it is Inet4Address }?.hostAddress
            val ipv6 = (addresses.firstOrNull { it is Inet6Address && !it.isLinkLocalAddress } ?: addresses.firstOrNull { it is Inet6Address })
                ?.hostAddress?.substringBefore('%')
            val down = c.linkDownstreamBandwidthKbps
            val path = NetworkPathSample(
                runId = ctx.runId,
                stamp = stamp,
                status = status,
                interfaces = transports.joinToString(","),
                primaryInterface = primaryInterfaceOf(transports),
                isExpensive = !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                isConstrained = !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED),
                ipv4 = ipv4,
                ipv6 = ipv6,
                linkQuality = linkQualityOf(down),
                unsatisfiedReason = reason,
            )
            val android = AndroidNetworkSample(
                runId = ctx.runId,
                stamp = stamp,
                networkHandle = network.networkHandle,
                transports = transports.joinToString(","),
                validated = validated,
                captivePortal = captive,
                notMetered = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                downKbps = down,
                upKbps = c.linkUpstreamBandwidthKbps,
                // transport name (wifi | cellular | ethernet | vpn), as in probe.interface; kernel name in ifname
                iface = transportNameOf(transports),
                ifname = lp?.interfaceName,
                dnsServers = lp?.dnsServers?.mapNotNull { it.hostAddress }?.joinToString(","),
                privateDns = lp?.privateDnsServerName,
            )
            emitIfChanged(path, android)
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (network != current) {
                    current = network; caps = null; link = null; isBlocked = false
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                current = network
                caps = networkCapabilities
                emitCurrent()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                current = network
                link = linkProperties
                emitCurrent()
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                if (network == current) {
                    isBlocked = blocked
                    emitCurrent()
                }
            }

            override fun onLost(network: Network) {
                if (network == current) {
                    current = null; caps = null; link = null
                    emitNoNetwork()
                }
            }
        }
        frameworkCall({ ctx.status.unavailable(id, "Network monitoring refused: ${it.message}") }) {
            cm.registerDefaultNetworkCallback(callback, worker.handler)
        }
        worker.handler.post { if (cm.activeNetwork == null) emitNoNetwork() }

        awaitClose {
            frameworkCall { cm.unregisterNetworkCallback(callback) }
            worker.quit()
        }
    }.buffer(64)

    private fun transportNames(c: NetworkCapabilities): List<String> = buildList {
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_USB)) add("usb")
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) add("wifi_aware")
    }
}
