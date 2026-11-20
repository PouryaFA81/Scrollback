// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.remote.jellyfin

import app.scrollback.remote.HttpException
import app.scrollback.remote.music.JellyfinAuth
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Jellyfin video API (movies, shows, collections, playback reporting). Uses the token from the
 * music-phase sign-in. Newer routes (10.9+, e.g. `/UserItems/Resume`) are tried first and the
 * legacy `/Users/{id}/…` routes are used when the server answers 404/405.
 */
class JellyfinVideoApi(
    private val http: OkHttpClient,
    baseUrl: String,
    val userId: String,
    val deviceId: String,
    private val appVersion: String,
    private val token: () -> String?,
) {
    val base = baseUrl.trimEnd('/')
    private val json = "application/json".toMediaType()

    private fun request(url: String): Request.Builder {
        val b = Request.Builder().url(url)
        token()?.let { t -> JellyfinAuth.authHeaders(deviceId, appVersion, t).forEach { (k, v) -> b.header(k, v) } }
        return b
    }

    private fun exec(b: Request.Builder, what: String): String? = http.newCall(b.build()).execute().use { r ->
        when {
            r.code == 404 || r.code == 405 -> null
            r.code == 401 -> throw HttpException(401, "Jellyfin sign-in expired; sign in again in Servers")
            !r.isSuccessful -> throw HttpException(r.code, "Jellyfin $what failed")
            else -> r.body.string()
        }
    }

    private fun get(what: String, vararg urls: String): String {
        for (u in urls) exec(request(u), what)?.let { return it }
        throw HttpException(404, "Jellyfin $what not found")
    }

    private fun post(url: String, body: JSONObject?, what: String): String? =
        exec(request(url).post((body?.toString() ?: "").toRequestBody(json)), what)

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")
    private val fields = "Fields=${JfParse.ITEM_FIELDS}&EnableUserData=true&EnableImageTypes=Primary,Backdrop,Thumb&ImageTypeLimit=1"

    // ---- Browsing ----

    data class Query(
        val types: String = "Movie",
        val parentId: String? = null,
        val sortBy: String = "SortName",
        val descending: Boolean = false,
        val search: String? = null,
        val filters: String? = null,
        val start: Int = 0,
        val limit: Int = 60,
        val recursive: Boolean = true,
    )

    fun items(qr: Query): JfPage {
        val params = buildString {
            append("userId=").append(userId)
            if (qr.types.isNotEmpty()) append("&IncludeItemTypes=").append(qr.types)
            qr.parentId?.let { append("&ParentId=").append(it) }
            append("&Recursive=").append(qr.recursive)
            append("&SortBy=").append(qr.sortBy).append("&SortOrder=").append(if (qr.descending) "Descending" else "Ascending")
            qr.search?.takeIf { it.isNotBlank() }?.let { append("&SearchTerm=").append(q(it.trim())) }
            qr.filters?.let { append("&Filters=").append(it) }
            append("&StartIndex=").append(qr.start).append("&Limit=").append(qr.limit).append("&").append(fields)
        }
        return JfParse.page(JSONObject(get("items", "$base/Items?$params", "$base/Users/$userId/Items?${params.removePrefix("userId=$userId&")}")))
    }

    fun resume(limit: Int = 20): List<JfItem> {
        val p = "MediaTypes=Video&Limit=$limit&$fields"
        return JfParse.page(JSONObject(get("resume", "$base/UserItems/Resume?userId=$userId&$p", "$base/Users/$userId/Items/Resume?$p"))).items
    }

    fun nextUp(limit: Int = 20, seriesId: String? = null): List<JfItem> {
        val s = seriesId?.let { "&SeriesId=$it" } ?: ""
        return JfParse.page(JSONObject(get("next up", "$base/Shows/NextUp?userId=$userId&Limit=$limit$s&$fields"))).items
    }

    fun latest(types: String, limit: Int = 20): List<JfItem> =
        JfParse.array(JSONArray(get("latest", "$base/Items/Latest?userId=$userId&IncludeItemTypes=$types&Limit=$limit&$fields", "$base/Users/$userId/Items/Latest?IncludeItemTypes=$types&Limit=$limit&$fields")))

    fun item(id: String): JfItem =
        JfParse.item(JSONObject(get("item", "$base/Items/$id?userId=$userId&$fields", "$base/Users/$userId/Items/$id?$fields")))

    fun seasons(seriesId: String): List<JfItem> =
        JfParse.page(JSONObject(get("seasons", "$base/Shows/$seriesId/Seasons?userId=$userId&$fields"))).items

    fun episodes(seriesId: String, seasonId: String?): List<JfItem> {
        val s = seasonId?.let { "&SeasonId=$it" } ?: ""
        return JfParse.page(JSONObject(get("episodes", "$base/Shows/$seriesId/Episodes?userId=$userId$s&$fields"))).items
    }

    fun search(term: String, limit: Int = 60): List<JfItem> =
        items(Query(types = "Movie,Series,Episode,BoxSet", search = term, limit = limit, sortBy = "SortName")).items

    // ---- Images (fetched through the shared OkHttp client, which adds the auth headers) ----

    fun primaryUrl(id: String, tag: String?, height: Int = 600) = "$base/Items/$id/Images/Primary?fillHeight=$height&quality=90" + (tag?.let { "&tag=$it" } ?: "")
    fun backdropUrl(id: String, tag: String?, width: Int = 1280) = "$base/Items/$id/Images/Backdrop/0?fillWidth=$width&quality=85" + (tag?.let { "&tag=$it" } ?: "")
    fun thumbUrl(id: String, tag: String?, width: Int = 640) = "$base/Items/$id/Images/Thumb?fillWidth=$width&quality=85" + (tag?.let { "&tag=$it" } ?: "")

    /** Poster: own Primary, else the series poster (episodes/seasons). */
    fun poster(item: JfItem): String? = when {
        item.primaryTag != null && item.type != JfType.Episode -> primaryUrl(item.id, item.primaryTag)
        item.seriesId != null && item.seriesPrimaryTag != null -> primaryUrl(item.seriesId, item.seriesPrimaryTag)
        item.primaryTag != null -> primaryUrl(item.id, item.primaryTag)
        else -> null
    }

    /** Wide image: own backdrop, parent's backdrop, episode still (Primary), thumb. */
    fun wide(item: JfItem): String? = when {
        item.type == JfType.Episode && item.primaryTag != null -> primaryUrl(item.id, item.primaryTag, 360)
        item.backdropTags.isNotEmpty() -> backdropUrl(item.id, item.backdropTags.first())
        item.parentBackdropItemId != null && item.parentBackdropTags.isNotEmpty() -> backdropUrl(item.parentBackdropItemId, item.parentBackdropTags.first())
        item.parentThumbItemId != null && item.parentThumbTag != null -> thumbUrl(item.parentThumbItemId, item.parentThumbTag)
        item.thumbTag != null -> thumbUrl(item.id, item.thumbTag)
        else -> poster(item)
    }

    fun personUrl(p: JfPerson) = p.imageTag?.let { primaryUrl(p.id, it, 240) }

    // ---- Playback ----

    fun playbackInfo(itemId: String, startMs: Long, audioIndex: Int?, subtitleIndex: Int?, maxBitrate: Int = DeviceProfile.DEFAULT_MAX_BITRATE, allowDirect: Boolean = true): JfPlaybackInfo {
        val body = JSONObject()
            .put("UserId", userId)
            .put("DeviceProfile", DeviceProfile.build(maxBitrate))
            .put("MaxStreamingBitrate", maxBitrate)
            .put("StartTimeTicks", Ticks.fromMs(startMs))
            .put("AutoOpenLiveStream", true)
            .put("EnableDirectPlay", allowDirect)
            .put("EnableDirectStream", allowDirect)
            .put("EnableTranscoding", true)
            .put("AllowVideoStreamCopy", true)
            .put("AllowAudioStreamCopy", true)
        audioIndex?.let { body.put("AudioStreamIndex", it) }
        subtitleIndex?.let { body.put("SubtitleStreamIndex", it) }
        val r = post("$base/Items/$itemId/PlaybackInfo?userId=$userId", body, "playback info")
            ?: throw HttpException(404, "This item can't be played")
        return JfParse.playbackInfo(JSONObject(r))
    }

    data class Report(
        val itemId: String,
        val mediaSourceId: String,
        val playSessionId: String?,
        val positionMs: Long,
        val paused: Boolean,
        val method: PlayMethod,
        val audioIndex: Int?,
        val subtitleIndex: Int?,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("ItemId", itemId).put("MediaSourceId", mediaSourceId)
            .put("PlaySessionId", playSessionId ?: JSONObject.NULL)
            .put("PositionTicks", Ticks.fromMs(positionMs)).put("IsPaused", paused)
            .put("PlayMethod", method.name).put("CanSeek", true)
            .put("AudioStreamIndex", audioIndex ?: JSONObject.NULL)
            .put("SubtitleStreamIndex", subtitleIndex ?: -1)
    }

    fun reportStart(r: Report) { post("$base/Sessions/Playing", r.toJson(), "start report") }
    fun reportProgress(r: Report, event: String = "TimeUpdate") { post("$base/Sessions/Playing/Progress", r.toJson().put("EventName", event), "progress report") }
    fun reportStopped(r: Report) { post("$base/Sessions/Playing/Stopped", r.toJson(), "stop report") }

    // ---- User data ----

    private fun toggle(newUrl: String, legacyUrl: String, on: Boolean, what: String): JfUserData? {
        fun send(u: String): String? = exec(if (on) request(u).post(ByteArray(0).toRequestBody(null)) else request(u).delete(), what)
        val body = send(newUrl) ?: send(legacyUrl) ?: throw HttpException(404, "Jellyfin $what not supported")
        return runCatching { JfParse.userData(JSONObject(body)) }.getOrNull()
    }

    fun setPlayed(id: String, played: Boolean) = toggle("$base/UserPlayedItems/$id?userId=$userId", "$base/Users/$userId/PlayedItems/$id", played, "played")
    fun setFavorite(id: String, fav: Boolean) = toggle("$base/UserFavoriteItems/$id?userId=$userId", "$base/Users/$userId/FavoriteItems/$id", fav, "favorite")
}
