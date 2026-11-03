package app.tracer.probes

import app.tracer.core.runtime.NtpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal SNTP v4 client ([NtpClient]); the socket and DNS are bound to the default network
 * when there is one. Returns server − device wall clock in ms, or null on any failure.
 */
@Singleton
class SntpClient @Inject constructor(
    private val networks: ProbeNetworks,
) : NtpClient {

    override suspend fun offsetMs(host: String, timeoutMs: Int): Long? = withContext(Dispatchers.IO) {
        try {
            val bound = networks.current()?.network
            DatagramSocket().use { socket ->
                bound?.bindSocket(socket)
                socket.soTimeout = timeoutMs
                val address: InetAddress = bound?.getByName(host) ?: InetAddress.getByName(host)
                val t0 = System.currentTimeMillis()
                val t0Nano = System.nanoTime()
                val request = Sntp.buildRequest(t0)
                socket.send(DatagramPacket(request, request.size, address, Sntp.PORT))
                val reply = ByteArray(Sntp.PACKET_SIZE)
                val packet = DatagramPacket(reply, reply.size)
                socket.receive(packet)
                // t3 from the monotonic clock so a wall-clock jump mid-exchange cannot skew the result.
                val t3 = t0 + (System.nanoTime() - t0Nano) / 1_000_000L
                Sntp.offsetFromReply(reply, packet.length, t0, t3)
            }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }
}
