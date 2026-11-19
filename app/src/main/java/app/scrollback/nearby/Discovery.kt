package app.scrollback.nearby

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * LocalSend discovery (spec 3.1 multicast + 3.2 HTTP legacy scan), ported from RSend's Discovery:
 * - announce {announce:true} to 224.0.0.167:53317 on all IPv4 interfaces;
 * - answer others' announcements with POST /register (fallback: multicast {announce:false});
 * - optional subnet scan: POST /register to every host of our private /24.
 * The Android multicast lock is held by the caller (NearbyManager).
 */
class Discovery(
    private val self: () -> DeviceInfo,
    private val client: NearbyClient,
    private val onPeer: (Peer) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var socket: MulticastSocket? = null
    private var listener: Thread? = null
    private val io = Executors.newCachedThreadPool { r -> Thread(r, "nearby-io").apply { isDaemon = true } }
    private val group: InetAddress = InetAddress.getByName(LocalSend.GROUP)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val s = runCatching {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(LocalSend.PORT))
                timeToLive = 1
                soTimeout = 1000
                interfaces().forEach { nif -> runCatching { joinGroup(InetSocketAddress(group, LocalSend.PORT), nif) } }
            }
        }.getOrNull()
        socket = s
        if (s != null) listener = thread(isDaemon = true, name = "nearby-multicast") { listen(s) }
        announce()
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null; listener = null
    }

    fun shutdown() { stop(); io.shutdownNow() }

    /** Multicast announcement (sent a few times; UDP on Wi-Fi drops packets). */
    fun announce() {
        if (!running.get()) return
        submit {
            try { for (i in 0 until 3) { if (!running.get()) return@submit; send(self().toJson(full = true, announce = true)); Thread.sleep(300) } }
            catch (_: InterruptedException) {}
        }
    }

    /** Background task that never crashes the app (pool may be shut down when the screen closes). */
    private fun submit(block: () -> Unit) {
        runCatching { io.execute { runCatching(block) } }
    }

    private fun send(json: JSONObject) {
        val data = json.toString().toByteArray(Charsets.UTF_8)
        val ifs = interfaces()
        if (ifs.isEmpty()) runCatching { socket?.send(DatagramPacket(data, data.size, group, LocalSend.PORT)) }
        ifs.forEach { nif ->
            runCatching {
                MulticastSocket().use { out ->
                    out.timeToLive = 1
                    out.networkInterface = nif
                    out.send(DatagramPacket(data, data.size, group, LocalSend.PORT))
                }
            }
        }
    }

    private fun listen(s: MulticastSocket) {
        val buf = ByteArray(64 * 1024)
        while (running.get() && !s.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (_: SocketTimeoutException) { continue } catch (_: Exception) { break }
            val o = runCatching { JSONObject(String(p.data, p.offset, p.length, Charsets.UTF_8)) }.getOrNull() ?: continue
            val info = DeviceInfo.fromJson(o) ?: continue
            val me = self()
            if (info.fingerprint == me.fingerprint) continue
            val host = p.address.hostAddress ?: continue
            onPeer(Peer(info, host))
            val announce = o.optBoolean("announce", o.optBoolean("announcement", false))
            if (announce) submit {
                val ok = runCatching { client.register(me, host, info.port, info.protocol) }.getOrNull() != null
                if (!ok) send(me.toJson(full = true, announce = false))
            }
        }
    }

    /**
     * Legacy HTTP scan of our private IPv4 subnets (for networks that drop multicast).
     * Blocking; returns how many hosts were probed.
     */
    fun scan(isCancelled: () -> Boolean = { false }): Int {
        val me = self()
        val hosts = localSubnets().flatMap { (addr, prefix) -> Subnet.hosts(addr, prefix) }.distinct()
        if (hosts.isEmpty()) return 0
        val pool = Executors.newFixedThreadPool(48) { r -> Thread(r, "nearby-scan").apply { isDaemon = true } }
        hosts.forEach { h ->
            pool.execute {
                if (isCancelled() || !running.get()) return@execute
                val p = runCatching { client.register(me, h, LocalSend.PORT, "https", connectMs = 800) }.getOrNull()
                    ?: runCatching { client.register(me, h, LocalSend.PORT, "http", connectMs = 800) }.getOrNull()
                p?.let(onPeer)
            }
        }
        pool.shutdown()
        pool.awaitTermination(60, TimeUnit.SECONDS)
        return hosts.size
    }

    /** Add a device by address (manual entry): tries https then http. */
    fun probe(host: String, port: Int = LocalSend.PORT): Peer? {
        val me = self()
        return (runCatching { client.register(me, host, port, "https") }.getOrNull()
            ?: runCatching { client.register(me, host, port, "http") }.getOrNull())?.also(onPeer)
    }

    companion object {
        fun interfaces(): List<NetworkInterface> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { n ->
                n.isUp && !n.isLoopback && n.supportsMulticast() && !n.isVirtual &&
                    n.inetAddresses.toList().any { it is Inet4Address } &&
                    !n.name.startsWith("rmnet") && !n.name.startsWith("ccmni") // mobile data
            }
        }.getOrDefault(emptyList())

        /** (address, prefix) of each private IPv4 address on an up interface. */
        fun localSubnets(): List<Pair<String, Int>> = interfaces().flatMap { n ->
            n.interfaceAddresses.mapNotNull { ia ->
                val a = ia.address as? Inet4Address ?: return@mapNotNull null
                if (!Subnet.isPrivate(a)) null else a.hostAddress!! to ia.networkPrefixLength.toInt()
            }
        }

        fun localAddresses(): List<String> = localSubnets().map { it.first }
    }
}
