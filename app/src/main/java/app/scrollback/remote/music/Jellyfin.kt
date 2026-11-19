package app.scrollback.remote.music

import app.scrollback.accounts.ServerAccount
import app.scrollback.data.db.MediaKind
import app.scrollback.data.source.MediaSource
import app.scrollback.data.source.PlaybackRequest
import app.scrollback.data.source.ScannedEntry
import app.scrollback.data.source.SourceType
import app.scrollback.remote.HttpException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Jellyfin music client. Username/password are used once to obtain an access token. */
object JellyfinAuth {
    fun header(deviceId: String, version: String, token: String? = null): String =
        "MediaBrowser Client=\"Scrollback\", Device=\"Android\", DeviceId=\"$deviceId\", Version=\"$version\"" +
            (token?.let { ", Token=\"$it\"" } ?: "")

    data class Session(val token: String, val userId: String, val userName: String)

    fun authenticate(http: OkHttpClient, baseUrl: String, user: String, password: String, deviceId: String, version: String): Session {
        val body = JSONObject().put("Username", user).put("Pw", password).toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$baseUrl/Users/AuthenticateByName").post(body)
            .header("Authorization", header(deviceId, version)).build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401) throw HttpException(401, "Wrong username or password")
            if (!r.isSuccessful) throw HttpException(r.code, "This does not look like a Jellyfin server")
            val o = JSONObject(r.body.string())
            val u = o.getJSONObject("User")
            return Session(o.getString("AccessToken"), u.getString("Id"), u.optString("Name", user))
        }
    }

    fun authHeaders(deviceId: String, version: String, token: String) =
        mapOf("Authorization" to header(deviceId, version, token), "X-Emby-Token" to token)
}

class JellyfinClient(private val http: OkHttpClient, private val account: ServerAccount) {
    private val base = account.baseUrl
    /** The account id doubles as the stable DeviceId. */
    private val deviceId = account.id

    /** Universal audio endpoint: direct play when the container is supported, else progressive MP3. */
    fun streamUrl(id: String) = "$base/Audio/$id/universal?UserId=${account.userId}&DeviceId=$deviceId&MaxStreamingBitrate=320000" +
        "&Container=opus,webm|opus,mp3,aac,m4a|aac,m4b|aac,flac,webma,webm|webma,wav,ogg&TranscodingContainer=mp3&TranscodingProtocol=http&AudioCodec=mp3"

    fun imageUrl(id: String) = "$base/Items/$id/Images/Primary?maxHeight=600&quality=90"

    fun items(start: Int, limit: Int): JSONObject {
        val url = "$base/Items?userId=${account.userId}&IncludeItemTypes=Audio&Recursive=true&SortBy=SortName" +
            "&Fields=DateCreated,MediaSources&StartIndex=$start&Limit=$limit&EnableImages=true"
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, "Jellyfin items failed")
            return JSONObject(r.body.string())
        }
    }

    fun favorite(id: String, fav: Boolean) {
        fun send(url: String): Int {
            val b = Request.Builder().url(url)
            if (fav) b.post(ByteArray(0).toRequestBody(null)) else b.delete()
            return http.newCall(b.build()).execute().use { it.code }
        }
        // 10.9+ route first, then the legacy one.
        val code = send("$base/UserFavoriteItems/$id?userId=${account.userId}")
        if (code == 404 || code == 405) send("$base/Users/${account.userId}/FavoriteItems/$id")
    }
}

/** A Jellyfin server's audio items as a Scrollback source. Items are `jellyfin:<account>/<itemId>`. Video is a future item. */
class JellyfinMediaSource(
    override val sourceId: Long,
    private val account: ServerAccount,
    private val client: JellyfinClient,
    private val http: OkHttpClient,
) : MediaSource {
    override val type = SourceType.JELLYFIN

    override fun scan(): Sequence<ScannedEntry> = sequence {
        var start = 0
        while (true) {
            val page = client.items(start, 500)
            val items = page.optJSONArray("Items") ?: break
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                val id = it.getString("Id")
                val container = it.optString("Container", "mp3").substringBefore(',')
                val artist = it.optString("AlbumArtist").ifEmpty { null } ?: it.optJSONArray("Artists")?.optString(0)?.ifEmpty { null }
                val album = it.optString("Album").ifEmpty { null }
                val title = it.optString("Name", id)
                val hasOwnImage = it.optJSONObject("ImageTags")?.has("Primary") == true
                val imageId = if (hasOwnImage) id else it.optString("AlbumId").ifEmpty { null }?.takeIf { _ -> it.has("AlbumPrimaryImageTag") }
                yield(
                    ScannedEntry(
                        uri = "jellyfin:${account.id}/$id",
                        name = "$title.$container",
                        relativePath = listOfNotNull(artist, album).joinToString("/"),
                        mime = MediaSource.resolveMime("x.$container", null) ?: "audio/mpeg",
                        size = it.optJSONArray("MediaSources")?.optJSONObject(0)?.optLong("Size") ?: 0L,
                        dateModified = MusicDates.parseIso(it.optString("DateCreated")),
                        kind = MediaKind.AUDIO,
                        thumbUri = imageId?.let { img -> "jellyfin-img:${account.id}/$img" },
                        remoteId = id,
                        artist = artist,
                        album = album,
                        title = title,
                        trackNo = it.optInt("IndexNumber"),
                        durationMs = it.optLong("RunTimeTicks") / 10_000,
                        remoteLiked = it.optJSONObject("UserData")?.optBoolean("IsFavorite"),
                    )
                )
            }
            start += items.length()
            if (items.length() < 500 || start >= page.optInt("TotalRecordCount", start)) break
        }
    }

    override fun playback(uri: String) = PlaybackRequest(android.net.Uri.parse(uri))
    override fun byteSource(uri: String, size: Long) = HttpByteSource(http, client.streamUrl(uri.substringAfterLast('/')), size)
    override fun delete(uri: String) = false
    override fun shareUri(uri: String): android.net.Uri? = null
    override fun setRemoteLiked(remoteId: String, liked: Boolean): Boolean = runCatching { client.favorite(remoteId, liked) }.isSuccess
}
