// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.nearby

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.Inet4Address
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * LocalSend protocol v2 (https://github.com/localsend/protocol), ported to Kotlin from the owner's
 * RSend app (its Java `lsend` package). Pure JVM code so it can be unit-tested.
 */
object LocalSend {
    const val PORT = 53317
    const val GROUP = "224.0.0.167"
    const val VERSION = "2.1"
    const val API = "/api/localsend/v2"
}

enum class DeviceType(val json: String) {
    MOBILE("mobile"), DESKTOP("desktop"), WEB("web"), HEADLESS("headless"), SERVER("server");

    companion object {
        /** null stays null; unknown values fall back to DESKTOP like the official app. */
        fun from(v: String?): DeviceType? = if (v == null) null else entries.firstOrNull { it.json == v } ?: DESKTOP
    }
}

/** Device metadata exchanged by multicast, /info and /register. */
data class DeviceInfo(
    val alias: String,
    val fingerprint: String,
    val deviceModel: String? = null,
    val deviceType: DeviceType? = DeviceType.MOBILE,
    val port: Int = LocalSend.PORT,
    val protocol: String = "https",
    val version: String = LocalSend.VERSION,
    val download: Boolean = false,
) {
    /** port/protocol are only included in announcements and register bodies ([full]). */
    fun toJson(full: Boolean = true, announce: Boolean? = null): JSONObject = JSONObject().apply {
        put("alias", alias)
        put("version", version)
        put("deviceModel", deviceModel ?: JSONObject.NULL)
        put("deviceType", deviceType?.json ?: JSONObject.NULL)
        put("fingerprint", fingerprint)
        if (full) { put("port", port); put("protocol", protocol) }
        put("download", download)
        if (announce != null) put("announce", announce)
    }

    companion object {
        /** Lenient parse. Missing port/protocol (info/register answers) are filled from the transport. */
        fun fromJson(o: JSONObject, fallbackPort: Int = LocalSend.PORT, fallbackProtocol: String = "https"): DeviceInfo? {
            val fp = o.optString("fingerprint").takeIf { it.isNotBlank() && !o.isNull("fingerprint") } ?: return null
            return DeviceInfo(
                alias = o.optString("alias").takeIf { it.isNotBlank() && !o.isNull("alias") } ?: "Unknown",
                fingerprint = fp,
                deviceModel = if (o.isNull("deviceModel")) null else o.optString("deviceModel").ifBlank { null },
                deviceType = DeviceType.from(if (o.isNull("deviceType")) null else o.optString("deviceType").ifBlank { null }),
                port = o.optInt("port", 0).takeIf { it in 1..65535 } ?: fallbackPort,
                protocol = o.optString("protocol").takeIf { it == "http" || it == "https" } ?: fallbackProtocol,
                version = o.optString("version").ifBlank { "1.0" },
                download = o.optBoolean("download", false),
            )
        }
    }
}

/** A peer we can reach. */
data class Peer(val info: DeviceInfo, val address: String, val lastSeen: Long = System.currentTimeMillis()) {
    val baseUrl: String get() = "${info.protocol}://${hostPart(address)}:${info.port}"
    /** Fingerprint to pin the TLS certificate against (only meaningful for HTTPS). */
    val pin: String? get() = if (info.protocol == "https") info.fingerprint else null

    private fun hostPart(a: String) = if (a.contains(':') && !a.startsWith("[")) "[$a]" else a
}

/** One file in a prepare-upload request. Text messages carry their content in [preview]. */
data class FileOffer(
    val id: String,
    val fileName: String,
    val size: Long,
    val fileType: String,
    val sha256: String? = null,
    val preview: String? = null,
) {
    val isText: Boolean get() = preview != null && fileType.startsWith("text/plain")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("fileName", fileName); put("size", size); put("fileType", fileType)
        sha256?.let { put("sha256", it) }
        preview?.let { put("preview", it) }
    }

    companion object {
        fun fromJson(o: JSONObject, idFallback: String) = FileOffer(
            id = o.optString("id").ifBlank { idFallback },
            fileName = o.optString("fileName").ifBlank { "file" },
            size = o.optLong("size", 0).coerceAtLeast(0),
            fileType = o.optString("fileType").ifBlank { "application/octet-stream" },
            sha256 = if (o.isNull("sha256")) null else o.optString("sha256").ifBlank { null },
            preview = if (o.isNull("preview")) null else o.optString("preview").ifBlank { null },
        )
    }
}

data class PrepareRequest(val info: DeviceInfo, val files: List<FileOffer>) {
    fun toJson(): String = JSONObject().apply {
        put("info", info.toJson(full = true))
        put("files", JSONObject().apply { files.forEach { put(it.id, it.toJson()) } })
    }.toString()

    companion object {
        fun parse(body: String, fallbackPort: Int = LocalSend.PORT): PrepareRequest? {
            val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val info = o.optJSONObject("info")?.let { DeviceInfo.fromJson(it, fallbackPort) } ?: return null
            val fm = o.optJSONObject("files") ?: return null
            val files = fm.keys().asSequence().mapNotNull { k -> fm.optJSONObject(k)?.let { FileOffer.fromJson(it, k) } }.toList()
            return if (files.isEmpty()) null else PrepareRequest(info, files)
        }
    }
}

data class PrepareResponse(val sessionId: String, val tokens: Map<String, String>) {
    fun toJson(): String = JSONObject().put("sessionId", sessionId).put("files", JSONObject(tokens)).toString()

    companion object {
        fun parse(body: String): PrepareResponse? {
            val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val sid = o.optString("sessionId").ifBlank { return null }
            val f = o.optJSONObject("files") ?: return null
            return PrepareResponse(sid, f.keys().asSequence().associateWith { f.getString(it) })
        }
    }
}

/** HTTP status codes from the spec, mapped to messages the UI can show. */
object Status {
    fun message(code: Int): String = when (code) {
        204 -> "Nothing to transfer"
        400 -> "The other device rejected the request"
        401 -> "Wrong or missing PIN"
        403 -> "Declined"
        409 -> "The other device is busy with another transfer"
        429 -> "Too many requests; try again shortly"
        500 -> "The other device had an error"
        else -> "Failed (HTTP $code)"
    }
}

object Hex {
    fun of(b: ByteArray): String = buildString(b.size * 2) { b.forEach { append(Character.forDigit((it.toInt() shr 4) and 0xf, 16)); append(Character.forDigit(it.toInt() and 0xf, 16)) } }
    fun sha256(b: ByteArray): String = of(MessageDigest.getInstance("SHA-256").digest(b))
    fun random(bytes: Int = 16): String = of(ByteArray(bytes).also { SecureRandom().nextBytes(it) })
}

/** Spec section 2: the fingerprint is the SHA-256 of the DER certificate, lowercase hex. */
object Fingerprint {
    fun of(cert: X509Certificate): String = Hex.sha256(cert.encoded)
    /** "a1b2c3d4…" -> "A1B2 C3D4" for showing to people. */
    fun short(fp: String): String = fp.take(8).uppercase().chunked(4).joinToString(" ")
}

/** Self-signed X.509 v3 (SHA256withRSA) built with plain DER, as in RSend; no BouncyCastle needed. */
object SelfSigned {
    private val OID_SHA256_RSA = byteArrayOf(0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)
    private val OID_CN = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    fun create(kp: KeyPair, cn: String = "LocalSend User", from: Date = Date(System.currentTimeMillis() - 60_000), to: Date = Date(System.currentTimeMillis() + 20L * 365 * 86_400_000)): X509Certificate {
        val algId = seq(OID_SHA256_RSA, byteArrayOf(0x05, 0x00))
        val name = seq(tlv(0x31, seq(OID_CN, tlv(0x0c, cn.toByteArray(Charsets.UTF_8)))))
        val serial = tlv(0x02, BigInteger(63, SecureRandom()).add(BigInteger.ONE).toByteArray())
        val tbs = seq(tlv(0xa0, tlv(0x02, byteArrayOf(2))), serial, algId, name, seq(time(from), time(to)), name, kp.public.encoded)
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(kp.private); update(tbs); sign() }
        val der = seq(tbs, algId, tlv(0x03, byteArrayOf(0) + sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private fun time(d: Date): ByteArray {
        val utc = d.time < 2524608000000L // 2050-01-01: UTCTime before, GeneralizedTime after
        val f = SimpleDateFormat(if (utc) "yyMMddHHmmss'Z'" else "yyyyMMddHHmmss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(if (utc) 0x17 else 0x18, f.format(d).toByteArray(Charsets.US_ASCII))
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray())

    internal fun tlv(tag: Int, v: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(tag)
        val n = v.size
        when {
            n < 0x80 -> write(n)
            n < 0x100 -> { write(0x81); write(n) }
            n < 0x10000 -> { write(0x82); write(n shr 8); write(n and 0xff) }
            else -> { write(0x83); write(n shr 16); write((n shr 8) and 0xff); write(n and 0xff) }
        }
        write(v)
    }.toByteArray()
}

/** Received names: basename only, no traversal, no control chars, ≤200 bytes. */
object SafeName {
    fun clean(name: String?): String {
        var n = (name ?: "").replace('\\', '/').substringAfterLast('/')
        n = n.filter { it >= ' ' && it != '\u007f' }.trim()
        if (n.isEmpty() || n == "." || n == "..") n = "file"
        n = n.replace("..", "__").replace(Regex("[:*?\"<>|]"), "_")
        while (n.toByteArray(Charsets.UTF_8).size > 200) n = n.dropLast(1)
        return n.ifEmpty { "file" }
    }
}

/** Legacy HTTP discovery: the other hosts in our /24 (or smaller) subnet. */
object Subnet {
    fun hosts(address: String, prefix: Int): List<String> {
        val parts = address.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return emptyList()
        val p = prefix.coerceIn(24, 30) // never scan more than 254 hosts
        val ip = parts.fold(0L) { acc, b -> (acc shl 8) or b.toLong() }
        val mask = (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
        val net = ip and mask
        val count = 1L shl (32 - p)
        return (1 until count - 1).map { net + it }.filter { it != ip }.map { v ->
            "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"
        }
    }

    fun isPrivate(a: Inet4Address): Boolean = a.isSiteLocalAddress && !a.isLoopbackAddress
}
