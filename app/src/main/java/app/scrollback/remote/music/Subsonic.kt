package app.scrollback.remote.music

import app.scrollback.accounts.ServerAccount
import app.scrollback.core.hash.DuplicateFinder
import app.scrollback.data.db.MediaKind
import app.scrollback.data.source.MediaSource
import app.scrollback.data.source.PlaybackRequest
import app.scrollback.data.source.ScannedEntry
import app.scrollback.data.source.SourceType
import app.scrollback.remote.HttpException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/** Pure helpers for the Subsonic API (unit-tested). */
object SubsonicAuth {
    const val API_VERSION = "1.16.1"
    const val CLIENT = "Scrollback"

    fun md5Hex(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Token auth: t = md5(password + salt). The password itself is never sent. */
    fun token(password: String, salt: String) = md5Hex(password + salt)

    fun newSalt(): String = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun query(user: String, password: String, salt: String = newSalt()): String =
        "u=${enc(user)}&t=${token(password, salt)}&s=$salt&v=$API_VERSION&c=$CLIENT&f=json"

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Returns the `subsonic-response` object or throws with the server's error message. */
    fun unwrap(body: String): JSONObject {
        val r = JSONObject(body).getJSONObject("subsonic-response")
        if (r.optString("status") != "ok") {
            val e = r.optJSONObject("error")
            throw HttpException(e?.optInt("code", 0) ?: 0, e?.optString("message").orEmpty().ifEmpty { "Subsonic error" })
        }
        return r
    }
}

/** Subsonic / OpenSubsonic client (Navidrome, Nextcloud Music, Airsonic, Gonic…). */
class SubsonicClient(private val http: OkHttpClient, private val account: ServerAccount, private val password: String) {
    fun url(endpoint: String, params: String = ""): String =
        "${account.baseUrl}/rest/$endpoint.view?" + SubsonicAuth.query(account.loginName, password) + if (params.isNotEmpty()) "&$params" else ""

    fun call(endpoint: String, params: String = ""): JSONObject =
        http.newCall(Request.Builder().url(url(endpoint, params)).build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, "$endpoint failed")
            SubsonicAuth.unwrap(r.body.string())
        }

    fun ping() { call("ping") }

    fun streamUrl(id: String) = url("stream", "id=${SubsonicAuth.enc(id)}")
    fun coverUrl(id: String) = url("getCoverArt", "id=${SubsonicAuth.enc(id)}&size=600")

    fun star(id: String, starred: Boolean) { call(if (starred) "star" else "unstar", "id=${SubsonicAuth.enc(id)}") }

    /** All songs, album by album. */
    fun songs(): Sequence<JSONObject> = sequence {
        var offset = 0
        while (true) {
            val albums = call("getAlbumList2", "type=alphabeticalByName&size=500&offset=$offset")
                .optJSONObject("albumList2")?.optJSONArray("album") ?: JSONArray()
            if (albums.length() == 0) break
            for (i in 0 until albums.length()) {
                val id = albums.getJSONObject(i).getString("id")
                val songs = runCatching { call("getAlbum", "id=${SubsonicAuth.enc(id)}").getJSONObject("album").optJSONArray("song") }.getOrNull() ?: continue
                for (j in 0 until songs.length()) yield(songs.getJSONObject(j))
            }
            if (albums.length() < 500) break
            offset += 500
        }
    }
}

/** A Subsonic server's music library as a Scrollback source. Items are `subsonic:<account>/<songId>`. */
class SubsonicMediaSource(
    override val sourceId: Long,
    private val account: ServerAccount,
    private val client: SubsonicClient,
    private val http: OkHttpClient,
) : MediaSource {
    override val type = SourceType.SUBSONIC

    override fun scan(): Sequence<ScannedEntry> = client.songs().map { s ->
        val id = s.getString("id")
        val suffix = s.optString("suffix", "mp3")
        val title = s.optString("title", id)
        val artist = s.optString("artist").ifEmpty { null }
        val album = s.optString("album").ifEmpty { null }
        ScannedEntry(
            uri = "subsonic:${account.id}/$id",
            name = "$title.$suffix",
            relativePath = listOfNotNull(artist, album).joinToString("/"),
            mime = s.optString("contentType").ifEmpty { null } ?: MediaSource.resolveMime("x.$suffix", null) ?: "audio/mpeg",
            size = s.optLong("size"),
            dateModified = MusicDates.parseIso(s.optString("created")),
            kind = MediaKind.AUDIO,
            thumbUri = s.optString("coverArt").ifEmpty { null }?.let { "subsonic-cover:${account.id}/$it" },
            remoteId = id,
            artist = artist,
            album = album,
            title = title,
            trackNo = s.optInt("track"),
            durationMs = s.optLong("duration") * 1000,
            remoteLiked = s.has("starred"),
        )
    }

    override fun playback(uri: String) = PlaybackRequest(android.net.Uri.parse(uri))

    override fun byteSource(uri: String, size: Long) = HttpByteSource(http, client.streamUrl(uri.substringAfterLast('/')), size)

    override fun delete(uri: String) = false
    override fun shareUri(uri: String): android.net.Uri? = null

    override fun setRemoteLiked(remoteId: String, liked: Boolean): Boolean = runCatching { client.star(remoteId, liked) }.isSuccess
}

/** Range-request byte source for the duplicate finder. */
class HttpByteSource(private val http: OkHttpClient, private val url: String, override val size: Long) : DuplicateFinder.ByteSource {
    override fun readRange(offset: Long, length: Int): ByteArray =
        http.newCall(Request.Builder().url(url).header("Range", "bytes=$offset-${offset + length - 1}").build()).execute().use {
            if (!it.isSuccessful) ByteArray(0) else it.body.bytes()
        }
    override fun openStream(): InputStream = http.newCall(Request.Builder().url(url).build()).execute().body.byteStream()
}

object MusicDates {
    fun parseIso(s: String?): Long = if (s.isNullOrBlank()) 0L else runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        .recoverCatching { java.time.Instant.parse(s).toEpochMilli() }
        .recoverCatching { java.time.LocalDateTime.parse(s.take(19)).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }
        .getOrDefault(0L)
}
