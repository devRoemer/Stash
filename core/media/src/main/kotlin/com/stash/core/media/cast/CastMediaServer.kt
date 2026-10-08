package com.stash.core.media.cast

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * The small HTTP server a cast speaker fetches its audio from (spec 2026-10-06 §4).
 *
 * A speaker across the room can't open `file://`, `content://` or
 * `stash-resolve://`, and a signed stream URL handed to it would expire with no
 * way to refresh. So the speaker only ever gets `http://<phone>:<port>/<token>/m/<n>`,
 * and this server reads the bytes through the app's own [DataSource] stack —
 * the same lazy resolve, 403 refresh and cache that local playback uses.
 *
 * Runs only while casting. Bound to the phone's LAN address (never all
 * interfaces), on a random port, and every path carries a 128-bit random token,
 * so other devices on the network can't browse the library.
 *
 * One request per connection (`Connection: close`); the receiver opens a new
 * one per range request anyway.
 */
@OptIn(UnstableApi::class)
class CastMediaServer(
    /** The byte source for an audio entry's [Entry.uri]. */
    private val audioSource: (Entry) -> DataSource,
    /** The byte source for local artwork (`file://`, `content://`). */
    private val artworkSource: () -> DataSource,
    /** Where to listen. The phone's LAN address; tests use loopback. */
    private val bindAddress: () -> InetAddress? = ::lanAddress,
    /** Notes for the diagnostics bundle: fixed words, never an address. */
    private val onEvent: (String) -> Unit = {},
) {
    /** What one URL serves. [key] is opaque to callers; [payload] lets [audioSource] route the read. */
    class Entry internal constructor(val uri: Uri, val payload: Any?, internal val isArtwork: Boolean) {
        @Volatile internal var sniffedType: String? = null

        /** Total bytes, once a read has learned it; -1 until then. */
        @Volatile internal var totalLength: Long = -1L
    }

    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    private val entries = ConcurrentHashMap<String, Entry>()
    private val order = ArrayDeque<String>()
    private val nextKey = AtomicLong()

    /** The key each file is already served under, so asking again reuses it instead of adding one. */
    private val keysByFile = HashMap<String, String>()

    /** Connections being served, so [stop] can end them instead of leaving them to fail on their own. */
    private val clients: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var boundAddress: InetAddress? = null
    private var workers: ExecutorService? = null
    private var baseUrl: String? = null

    val isRunning: Boolean get() = serverSocket != null

    /**
     * Binds and starts accepting. Returns false when the phone has no LAN
     * address (mobile data only, no Wi-Fi), in which case a speaker couldn't
     * reach us anyway.
     */
    @Synchronized
    fun start(): Boolean {
        if (serverSocket != null) return true
        val address = bindAddress() ?: run {
            Log.w(TAG, "no LAN address — can't serve a cast speaker")
            onEvent("server: no LAN address")
            return false
        }
        val socket = try {
            ServerSocket(0, BACKLOG, address)
        } catch (e: IOException) {
            Log.w(TAG, "bind failed on ${address.hostAddress}", e)
            onEvent("server: bind failed")
            return false
        }
        serverSocket = socket
        boundAddress = address
        baseUrl = "http://${address.hostAddress}:${socket.localPort}/$token"
        // Unbounded, not fixed: a speaker switched off mid-song leaves its
        // connection's thread stuck in a write (no timeout covers writes) until
        // TCP gives up, minutes later. A fixed pool would run out of threads and
        // stop answering. Threads are cheap, idle ones exit after a minute, and
        // only the speaker's own few connections ever get past the token check.
        val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "cast-media-server").apply { isDaemon = true }
        }
        workers = pool
        Thread({ acceptLoop(socket, pool) }, "cast-media-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "serving on ${address.hostAddress}:${socket.localPort}")
        onEvent("server: started")
        return true
    }

    /**
     * Called before each song goes to the speaker. When the phone's LAN address
     * changed since [start] (a new DHCP lease, another network), the server
     * moves to the new one, so the songs after a reconnect point somewhere
     * live. The song that was playing can't be saved: the speaker holds its old
     * URL. False when there's no LAN address now.
     */
    @Synchronized
    fun ensureCurrentAddress(): Boolean {
        val current = bindAddress() ?: return false
        if (serverSocket != null && current == boundAddress) return true
        Log.i(TAG, "LAN address changed — moving the server")
        onEvent("server: LAN address changed, restarting")
        stop()
        return start()
    }

    @Synchronized
    fun stop() {
        serverSocket?.let { runCatching { it.close() } }
        serverSocket = null
        boundAddress = null
        workers?.shutdownNow()
        workers = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        baseUrl = null
        synchronized(order) {
            entries.clear()
            order.clear()
            keysByFile.clear()
        }
    }

    /** A URL the speaker can fetch [uri] from. Null when the server isn't running. */
    fun audioUrl(uri: Uri, payload: Any?): String? = register(Entry(uri, payload, isArtwork = false), "m")

    /**
     * A URL for an item's artwork. Web artwork goes to the speaker as is; local
     * cover files are served by us. Null when there is nothing usable.
     */
    fun artworkUrl(uri: Uri?): String? {
        uri ?: return null
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> uri.toString()
            "file", "content" -> register(Entry(uri, null, isArtwork = true), "a")
            null -> if (uri.path?.startsWith("/") == true) {
                register(Entry(Uri.fromFile(java.io.File(uri.path!!)), null, isArtwork = true), "a")
            } else null
            else -> null
        }
    }

    private fun register(entry: Entry, kind: String): String? {
        val base = baseUrl ?: return null
        val file = "$kind ${entry.uri}"
        val key = synchronized(order) {
            // The same file asked for again (a re-queued next song, a reload, its
            // cover) keeps its URL. Only the payload is refreshed: a stream's item
            // may since carry its resolved origin. What the first read learned
            // about the file stays.
            val key = keysByFile[file] ?: nextKey.incrementAndGet().toString()
            entries[key]?.let { old ->
                entry.sniffedType = old.sniffedType
                entry.totalLength = old.totalLength
            }
            entries[key] = entry
            keysByFile[file] = key
            order.remove(key)
            order.addLast(key)
            // The speaker needs the current song, the queued one and their covers.
            // The rest are spares for a skip back while an old request drains;
            // the oldest go first.
            while (order.size > MAX_ENTRIES) {
                val dropped = order.removeFirst()
                entries.remove(dropped)?.let { keysByFile.remove("${if (it.isArtwork) "a" else "m"} ${it.uri}") }
            }
            key
        }
        return "$base/$kind/$key"
    }

    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break // closed by stop()
            }
            clients += client
            try {
                pool.execute {
                    try {
                        client.use { handle(it) }
                    } finally {
                        clients -= client
                    }
                }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                clients -= client
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        // Buffered: readRequest reads byte by byte, which on a raw socket is a syscall per byte.
        val input = java.io.BufferedInputStream(client.getInputStream(), MAX_HEADER_BYTES)
        val out = BufferedOutputStream(client.getOutputStream(), BUFFER_SIZE)
        val request = try {
            readRequest(input)
        } catch (e: IOException) {
            return
        } ?: return
        try {
            serve(request, out)
        } catch (e: SocketException) {
            // The speaker hung up mid-song (seek, skip, stop). Routine.
        } catch (e: IOException) {
            Log.w(TAG, "serving ${request.path} failed: ${e.message}")
        } catch (e: RuntimeException) {
            // Uncaught here it would kill the whole app from this worker thread.
            Log.w(TAG, "serving ${request.path} failed", e)
            onEvent("server: request failed (${e.javaClass.simpleName})")
            runCatching { respondEmpty(out, 500, "Internal Server Error") }
        }
    }

    private fun serve(request: Request, out: OutputStream) {
        if (request.method != "GET" && request.method != "HEAD") {
            return respondEmpty(out, 405, "Method Not Allowed")
        }
        // /<token>/<kind>/<key>
        val parts = request.path.trimStart('/').split('/')
        if (parts.size != 3 || !tokenMatches(parts[0])) return respondEmpty(out, 404, "Not Found")
        val entry = entries[parts[2]]
        if (entry == null || (parts[1] == "a") != entry.isArtwork || parts[1] !in KINDS) {
            return respondEmpty(out, 404, "Not Found")
        }

        val range = request.headers["range"]?.let(::parseRange)
        val start = range?.first ?: 0L

        // A HEAD after the first read is answered from what that read learned.
        // Opening the source would resolve the stream (possibly yt-dlp, seconds)
        // and start an upstream request only to throw both away.
        val knownType = entry.sniffedType
        if (request.method == "HEAD" && knownType != null && entry.totalLength >= 0) {
            val total = entry.totalLength
            if (start >= total) return respondEmpty(out, 416, "Range Not Satisfiable")
            val end = range?.second?.let { minOf(it, total - 1) } ?: (total - 1)
            writeHeaders(out, partial = range != null, start = start, end = end, total = total, type = knownType)
            return out.flush()
        }

        val source = if (entry.isArtwork) artworkSource() else audioSource(entry)
        val remaining = try {
            source.open(DataSpec.Builder().setUri(entry.uri).setPosition(start).build())
        } catch (e: Exception) {
            // IOException, or a SecurityException from a content:// file whose
            // folder access is gone: the speaker gets a load error, not a crash.
            runCatching { source.close() }
            Log.w(TAG, "open failed for ${entry.uri.scheme}: ${e.message}")
            if (e !is IOException) onEvent("server: can't read a ${entry.uri.scheme} file (${e.javaClass.simpleName})")
            // 416 for a seek past the end; anything else the speaker sees as a load error.
            val status = if (start > 0 && e is androidx.media3.datasource.DataSourceException &&
                e.reason == androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
            ) 416 else 502
            return respondEmpty(out, status, if (status == 416) "Range Not Satisfiable" else "Bad Gateway")
        }
        try {
            val total = if (remaining == C.LENGTH_UNSET.toLong()) -1L else start + remaining
            if (total >= 0) entry.totalLength = total
            if (start > 0 && total < 0) {
                // Unknown length: we can't describe a partial response. Rare —
                // every source we read reports a length.
                return respondEmpty(out, 416, "Range Not Satisfiable")
            }
            val end = when {
                total < 0 -> -1L
                range?.second != null -> minOf(range.second!!, total - 1)
                else -> total - 1
            }
            if (total >= 0 && start >= total) return respondEmpty(out, 416, "Range Not Satisfiable")

            // Sniff the type from the first bytes: speakers trust the header,
            // and our URIs carry no usable extension.
            val head = ByteArray(BUFFER_SIZE)
            val headLength = readFully(source, head, if (end >= 0) minOf(BUFFER_SIZE.toLong(), end - start + 1).toInt() else BUFFER_SIZE)
            if (start == 0L && headLength > 0) entry.sniffedType = sniff(head, headLength, entry.isArtwork)
            val type = entry.sniffedType ?: if (entry.isArtwork) "image/jpeg" else "audio/mpeg"

            writeHeaders(out, partial = range != null && total >= 0, start = start, end = end, total = total, type = type)
            if (request.method == "HEAD") return out.flush()

            out.write(head, 0, headLength)
            var left = if (end >= 0) end - start + 1 - headLength else Long.MAX_VALUE
            val buffer = ByteArray(BUFFER_SIZE)
            while (left > 0) {
                val read = source.read(buffer, 0, minOf(left, BUFFER_SIZE.toLong()).toInt())
                if (read == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, read)
                left -= read
            }
            out.flush()
        } finally {
            runCatching { source.close() }
        }
    }

    /** Status line and headers; [end] and [total] are -1 when the length is unknown. */
    private fun writeHeaders(out: OutputStream, partial: Boolean, start: Long, end: Long, total: Long, type: String) {
        val headers = StringBuilder()
        if (partial) {
            headers.append("HTTP/1.1 206 Partial Content\r\n")
            headers.append("Content-Range: bytes $start-$end/$total\r\n")
        } else {
            headers.append("HTTP/1.1 200 OK\r\n")
        }
        if (end >= 0) headers.append("Content-Length: ${end - start + 1}\r\n")
        headers.append("Content-Type: $type\r\n")
        headers.append("Accept-Ranges: bytes\r\n")
        headers.append("Access-Control-Allow-Origin: *\r\n")
        headers.append("Cache-Control: no-store\r\n")
        headers.append("Connection: close\r\n\r\n")
        out.write(headers.toString().toByteArray(Charsets.US_ASCII))
    }

    private fun tokenMatches(candidate: String): Boolean =
        MessageDigest.isEqual(candidate.toByteArray(), token.toByteArray())

    private fun respondEmpty(out: OutputStream, code: Int, reason: String) {
        out.write("HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    internal class Request(val method: String, val path: String, val headers: Map<String, String>)

    internal companion object {
        private const val TAG = "CastMediaServer"
        private const val BACKLOG = 16
        private const val MAX_ENTRIES = 32
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_HEADER_BYTES = 16 * 1024
        private val KINDS = setOf("m", "a")

        /** Mobile data (Qualcomm, MediaTek) and VPN tunnels. */
        private val NON_LAN_INTERFACES = listOf("rmnet", "ccmni", "tun", "ppp")

        /** Reads the request line and headers. Null for an empty or malformed request. */
        fun readRequest(input: InputStream): Request? {
            val lines = mutableListOf<String>()
            val line = StringBuilder()
            var total = 0
            while (true) {
                val b = input.read()
                if (b == -1) return null
                if (++total > MAX_HEADER_BYTES) return null
                if (b == '\n'.code) {
                    val text = line.toString().trimEnd('\r')
                    line.setLength(0)
                    if (text.isEmpty()) break
                    lines += text
                } else {
                    line.append(b.toChar())
                }
            }
            val requestLine = lines.firstOrNull()?.split(' ') ?: return null
            if (requestLine.size < 2) return null
            val headers = lines.drop(1).mapNotNull { h ->
                val colon = h.indexOf(':')
                if (colon <= 0) null else h.substring(0, colon).trim().lowercase() to h.substring(colon + 1).trim()
            }.toMap()
            return Request(requestLine[0], requestLine[1].substringBefore('?'), headers)
        }

        /** `bytes=a-` or `bytes=a-b` → (a, b?). Suffix and multi-ranges aren't used by speakers; ignored. */
        fun parseRange(header: String): Pair<Long, Long?>? {
            val match = Regex("""^bytes=(\d+)-(\d*)$""").matchEntire(header.trim()) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull()
            if (end != null && end < start) return null
            return start to end
        }

        fun sniff(bytes: ByteArray, length: Int, isArtwork: Boolean): String? {
            fun at(i: Int, vararg expected: Int) =
                length >= i + expected.size && expected.indices.all { bytes[i + it].toInt() and 0xFF == expected[it] }
            return if (isArtwork) {
                when {
                    at(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
                    at(0, 0x89, 0x50, 0x4E, 0x47) -> "image/png"
                    at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50) -> "image/webp"
                    else -> null
                }
            } else {
                when {
                    at(0, 0x66, 0x4C, 0x61, 0x43) -> "audio/flac" // fLaC
                    at(0, 0x4F, 0x67, 0x67, 0x53) -> "audio/ogg" // OggS
                    at(4, 0x66, 0x74, 0x79, 0x70) -> "audio/mp4" // ....ftyp
                    at(0, 0x1A, 0x45, 0xDF, 0xA3) -> "audio/webm" // EBML
                    at(0, 0x49, 0x44, 0x33) -> "audio/mpeg" // ID3
                    length >= 2 && bytes[0].toInt() and 0xFF == 0xFF && bytes[1].toInt() and 0xE0 == 0xE0 ->
                        if (bytes[1].toInt() and 0x06 == 0) "audio/aac" else "audio/mpeg" // ADTS vs MPEG frame sync
                    else -> null
                }
            }
        }

        /**
         * The phone's IPv4 address on the local network, preferring Wi-Fi,
         * then Ethernet, then a hotspot it runs. Private (RFC 1918) addresses
         * first, but any routable IPv4 is accepted: some routers hand out
         * carrier-grade NAT (100.64/10) or public addresses on the LAN.
         * Mobile-data and VPN interfaces never: a speaker can't reach them, and
         * null tells the caller there is no LAN to serve on. IPv4 only: the Default
         * Media Receiver can't be relied on to fetch from an IPv6 literal.
         */
        fun lanAddress(): InetAddress? = pickLanAddress(
            runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
                .flatMap { nif -> nif.inetAddresses.toList().map { nif.name to it } },
        )

        /** [lanAddress]'s choice among the (interface name, address) pairs of the phone's up interfaces. */
        fun pickLanAddress(candidates: List<Pair<String, InetAddress>>): InetAddress? {
            fun rank(name: String, address: InetAddress): Int {
                val byInterface = when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("eth") -> 1
                    name.startsWith("ap") || name.startsWith("swlan") -> 2
                    else -> 3
                }
                return byInterface * 2 + if (address.isSiteLocalAddress) 0 else 1
            }
            return candidates
                .filterNot { (name, _) -> NON_LAN_INTERFACES.any { name.startsWith(it) } }
                .filter { (_, it) -> it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
                .minByOrNull { (name, address) -> rank(name, address) }
                ?.second
        }

        private fun readFully(source: DataSource, into: ByteArray, max: Int): Int {
            var filled = 0
            while (filled < max) {
                val read = source.read(into, filled, max - filled)
                if (read == C.RESULT_END_OF_INPUT) break
                filled += read
            }
            return filled
        }
    }
}
