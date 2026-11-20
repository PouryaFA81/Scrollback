// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.nearby

import fi.iki.elonen.NanoHTTPD
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory

/** Where a received file goes (MediaStore, a SAF folder, or a temp file in tests). */
interface ReceiveTarget {
    val out: OutputStream
    /** Finishes the file; returns a URI/path string for "open". */
    fun commit(): String
    fun abort()
}

/** Decisions and progress for incoming transfers. Called on server threads. */
interface ReceiveCallbacks {
    fun pin(): String?
    fun onRegister(peer: Peer) {}
    /** Blocking. Returns the accepted file ids, or null to decline (403). */
    fun onOffer(sessionId: String, sender: Peer, files: List<FileOffer>): Set<String>?
    fun onText(sender: Peer, text: String) {}
    fun openTarget(file: FileOffer): ReceiveTarget
    fun onProgress(sessionId: String, file: FileOffer, received: Long) {}
    fun onSaved(sessionId: String, file: FileOffer, location: String) {}
    fun onFailed(sessionId: String, file: FileOffer, message: String) {}
    /** Session over: everything received, cancelled by the sender, or replaced. */
    fun onSessionEnd(sessionId: String, complete: Boolean) {}
}

/**
 * LocalSend v2 receiver on NanoHTTPD (spec sections 3.2, 4, 6.1), ported from RSend's Server.java:
 * /info, /register, /prepare-upload, /upload, /cancel; one session at a time; optional PIN;
 * SHA-256 check when the sender provides one.
 */
class NearbyServer(
    private val self: () -> DeviceInfo,
    identity: Identity?,
    port: Int,
    private val cb: ReceiveCallbacks,
    host: String? = null,
) : NanoHTTPD(host, port) {

    private class Slot(val offer: FileOffer, val token: String) { @Volatile var done = false }
    private class Session(val id: String, val ip: String, val sender: Peer, val slots: Map<String, Slot>) {
        val created = System.currentTimeMillis()
        fun allDone() = slots.values.all { it.done }
    }

    private val lock = Any()
    private var session: Session? = null
    private val pinFailures = ConcurrentHashMap<String, Pair<Int, Long>>()

    init {
        if (identity != null) {
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(identity.keyStore, Identity.PASSWORD)
            makeSecure(makeSSLSocketFactory(identity.keyStore, kmf), null)
        }
    }

    fun startServer() = start(30_000, true)

    /** Receiver-side cancel (user tapped Stop). */
    fun cancelSession() {
        val s = synchronized(lock) { session.also { session = null } } ?: return
        cb.onSessionEnd(s.id, complete = false)
    }

    val busy: Boolean get() = synchronized(lock) { session?.let { System.currentTimeMillis() - it.created < SESSION_MAX_AGE } ?: false }

    override fun serve(s: IHTTPSession): Response = try {
        val uri = s.uri.trimEnd('/')
        val post = s.method == Method.POST
        when {
            uri.endsWith("/register") && post -> register(s)
            uri.endsWith("/info") && s.method == Method.GET -> json(Response.Status.OK, self().toJson(full = false).toString())
            uri.endsWith("/prepare-upload") -> if (post) prepare(s) else text(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")
            uri.endsWith("/upload") -> if (post) upload(s) else text(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")
            uri.endsWith("/cancel") -> if (post) cancel(s) else text(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")
            else -> text(Response.Status.NOT_FOUND, "not found")
        }
    } catch (e: Exception) {
        text(Response.Status.INTERNAL_ERROR, "error")
    }

    private fun register(s: IHTTPSession): Response {
        val body = readBody(s)
        runCatching { DeviceInfo.fromJson(org.json.JSONObject(String(body, Charsets.UTF_8)))?.let { cb.onRegister(Peer(it, s.remoteIpAddress)) } }
        return json(Response.Status.OK, self().toJson(full = false).toString())
    }

    private fun prepare(s: IHTTPSession): Response {
        val body = readBody(s)
        val ip = s.remoteIpAddress
        cb.pin()?.let { pin ->
            val (fails, since) = pinFailures[ip] ?: (0 to 0L)
            if (fails >= 5 && System.currentTimeMillis() - since < 60_000) return text(TOO_MANY, "too many attempts")
            if (param(s, "pin") != pin) {
                if (param(s, "pin") != null) pinFailures[ip] = (fails + 1) to System.currentTimeMillis()
                return text(Response.Status.UNAUTHORIZED, "pin required")
            }
            pinFailures.remove(ip)
        }
        val req = PrepareRequest.parse(String(body, Charsets.UTF_8)) ?: return text(Response.Status.BAD_REQUEST, "invalid body")
        val sender = Peer(req.info, ip)
        if (req.files.all { it.isText }) {
            req.files.forEach { cb.onText(sender, it.preview.orEmpty()) }
            return text(Response.Status.NO_CONTENT, "")
        }
        synchronized(lock) {
            val cur = session
            if (cur != null && System.currentTimeMillis() - cur.created < SESSION_MAX_AGE && cur.ip != ip && !cur.allDone()) {
                return text(Response.Status.CONFLICT, "blocked by another session")
            }
        }
        val sessionId = Hex.random()
        val accepted = cb.onOffer(sessionId, sender, req.files)?.takeIf { it.isNotEmpty() } ?: return text(Response.Status.FORBIDDEN, "declined")
        val slots = req.files.filter { it.id in accepted && !it.isText }.associate { it.id to Slot(it, Hex.random()) }
        if (slots.isEmpty()) return text(Response.Status.NO_CONTENT, "")
        val replaced = synchronized(lock) { session.also { session = Session(sessionId, ip, sender, slots) } }
        replaced?.let { cb.onSessionEnd(it.id, complete = false) }
        return json(Response.Status.OK, PrepareResponse(sessionId, slots.mapValues { it.value.token }).toJson())
    }

    private fun upload(s: IHTTPSession): Response {
        val sid = param(s, "sessionId"); val fid = param(s, "fileId"); val token = param(s, "token")
        val length = contentLength(s)
        if (sid == null || fid == null || token == null) { drain(s, length); return text(Response.Status.BAD_REQUEST, "missing parameters") }
        val (ses, slot) = synchronized(lock) {
            val ses = session?.takeIf { it.id == sid && it.ip == s.remoteIpAddress }
            ses to ses?.slots?.get(fid)?.takeIf { !it.done && it.token == token }
        }
        if (ses == null || slot == null) { drain(s, length); return text(Response.Status.FORBIDDEN, "invalid token, session or ip address") }
        val offer = slot.offer
        val target = try { cb.openTarget(offer) } catch (e: Exception) {
            drain(s, length); cb.onFailed(sid, offer, e.message ?: "Can't save"); return text(Response.Status.INTERNAL_ERROR, "cannot save")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var lastReport = 0L
        val input = body(s)
        try {
            target.out.use { out ->
                val buf = ByteArray(128 * 1024)
                var remaining = length
                while (remaining != 0L) {
                    val want = if (remaining > 0) minOf(buf.size.toLong(), remaining).toInt() else buf.size
                    val n = input.read(buf, 0, want)
                    if (n < 0) break
                    out.write(buf, 0, n); digest.update(buf, 0, n); total += n
                    if (remaining > 0) remaining -= n
                    if (total - lastReport > 256 * 1024) { lastReport = total; cb.onProgress(sid, offer, total) }
                    if (synchronized(lock) { session !== ses }) throw IOException("cancelled")
                }
            }
        } catch (e: IOException) {
            target.abort(); cb.onFailed(sid, offer, if (e.message == "cancelled") "Cancelled" else "Connection lost")
            return text(Response.Status.BAD_REQUEST, "incomplete")
        }
        if (total != offer.size) { target.abort(); cb.onFailed(sid, offer, "Incomplete file"); return text(Response.Status.BAD_REQUEST, "incomplete file") }
        if (offer.sha256 != null && !offer.sha256.equals(Hex.of(digest.digest()), ignoreCase = true)) {
            target.abort(); cb.onFailed(sid, offer, "Checksum mismatch"); return text(CHECKSUM, "checksum mismatch")
        }
        val location = target.commit()
        cb.onProgress(sid, offer, total)
        cb.onSaved(sid, offer, location)
        val finished = synchronized(lock) {
            slot.done = true
            if (session === ses && ses.allDone()) { session = null; true } else false
        }
        if (finished) cb.onSessionEnd(sid, complete = true)
        return text(Response.Status.OK, "")
    }

    private fun cancel(s: IHTTPSession): Response {
        val sid = param(s, "sessionId")
        val ended = synchronized(lock) { session?.takeIf { sid == null || it.id == sid }?.also { session = null } }
        ended?.let { cb.onSessionEnd(it.id, complete = false) }
        return text(Response.Status.OK, "")
    }

    // ---- helpers

    private fun param(s: IHTTPSession, k: String): String? = s.parameters[k]?.firstOrNull()

    private fun chunked(s: IHTTPSession) = s.headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
    private fun body(s: IHTTPSession): InputStream = if (chunked(s)) ChunkedInput(s.inputStream) else s.inputStream
    private fun contentLength(s: IHTTPSession): Long = if (chunked(s)) -1 else s.headers["content-length"]?.trim()?.toLongOrNull() ?: 0

    private fun readBody(s: IHTTPSession): ByteArray {
        val len = contentLength(s)
        if (len == 0L) return ByteArray(0)
        if (len > MAX_JSON) throw IOException("body too large")
        val input = body(s)
        return if (len > 0) input.readNBytes(len.toInt()) else input.readNBytes(MAX_JSON.toInt())
    }

    private fun drain(s: IHTTPSession, len: Long) {
        if (len == 0L) return
        val input = body(s); val buf = ByteArray(64 * 1024)
        var left = if (len < 0) Long.MAX_VALUE else len
        while (left > 0) { val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; left -= n }
    }

    private fun json(st: Response.IStatus, body: String) = newFixedLengthResponse(st, "application/json", body)
    private fun text(st: Response.IStatus, body: String) = newFixedLengthResponse(st, "text/plain; charset=utf-8", body)

    private class Code(private val code: Int, private val text: String) : Response.IStatus {
        override fun getDescription() = "$code $text"
        override fun getRequestStatus() = code
    }

    /** Minimal chunked transfer decoder (some clients stream without a length). */
    private class ChunkedInput(input: InputStream) : FilterInputStream(input) {
        private var left = 0L; private var eof = false
        private fun line(): String { val sb = StringBuilder(); while (true) { val c = `in`.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()) }; return sb.toString() }
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (eof) return -1
            if (left == 0L) {
                left = line().substringBefore(';').trim().toLongOrNull(16) ?: 0
                if (left == 0L) { while (line().isNotEmpty()) Unit; eof = true; return -1 }
            }
            val n = `in`.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) { eof = true; return -1 }
            left -= n
            if (left == 0L) line()
            return n
        }
    }

    companion object {
        private const val SESSION_MAX_AGE = 15 * 60_000L
        private const val MAX_JSON = 4L * 1024 * 1024
        private val TOO_MANY: Response.IStatus = Code(429, "Too Many Requests")
        private val CHECKSUM: Response.IStatus = Code(422, "Unprocessable Entity")
    }
}
