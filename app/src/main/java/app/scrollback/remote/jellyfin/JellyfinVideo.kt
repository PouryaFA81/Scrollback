// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.remote.jellyfin

import org.json.JSONArray
import org.json.JSONObject

/*
 * v0.12 Watch: Jellyfin movies and shows. Pure parsing + URL logic (unit-tested on the JVM).
 * Field access goes through [str]/[int] helpers because Android's org.json and the JVM one differ
 * on JSON null (Android's optString returns "null").
 */

internal fun JSONObject.str(key: String): String? =
    if (!has(key) || isNull(key)) null else opt(key)?.toString()?.takeIf { it.isNotEmpty() }
internal fun JSONObject.int(key: String): Int? = if (!has(key) || isNull(key)) null else (opt(key) as? Number)?.toInt()
internal fun JSONObject.long(key: String): Long? = if (!has(key) || isNull(key)) null else (opt(key) as? Number)?.toLong()
internal fun JSONObject.dbl(key: String): Double? = if (!has(key) || isNull(key)) null else (opt(key) as? Number)?.toDouble()
internal fun JSONObject.bool(key: String): Boolean = if (!has(key) || isNull(key)) false else optBoolean(key)
internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it)?.toString()?.takeIf { s -> s.isNotEmpty() } }

/** 1 tick = 100 ns. */
object Ticks {
    const val PER_MS = 10_000L
    fun toMs(ticks: Long) = ticks / PER_MS
    fun fromMs(ms: Long) = ms * PER_MS
}

enum class JfType { Movie, Series, Season, Episode, BoxSet, Video, Folder, Other;
    companion object { fun from(s: String?) = entries.firstOrNull { it.name == s } ?: Other }
}

data class JfUserData(
    val positionTicks: Long = 0,
    val played: Boolean = false,
    val favorite: Boolean = false,
    val playedPercentage: Double? = null,
    val unplayedCount: Int? = null,
)

data class JfPerson(val id: String, val name: String, val role: String?, val type: String?, val imageTag: String?)

data class JfItem(
    val id: String,
    val name: String,
    val type: JfType,
    val overview: String? = null,
    val year: Int? = null,
    val runtimeTicks: Long? = null,
    val communityRating: Double? = null,
    val criticRating: Int? = null,
    val officialRating: String? = null,
    val genres: List<String> = emptyList(),
    val people: List<JfPerson> = emptyList(),
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seasonId: String? = null,
    val seasonName: String? = null,
    val indexNumber: Int? = null,
    val parentIndexNumber: Int? = null,
    val childCount: Int? = null,
    val primaryTag: String? = null,
    val backdropTags: List<String> = emptyList(),
    val thumbTag: String? = null,
    val seriesPrimaryTag: String? = null,
    val parentBackdropItemId: String? = null,
    val parentBackdropTags: List<String> = emptyList(),
    val parentThumbItemId: String? = null,
    val parentThumbTag: String? = null,
    val userData: JfUserData = JfUserData(),
    val mediaSources: List<JfMediaSource> = emptyList(),
) {
    val runtimeMs: Long? get() = runtimeTicks?.let(Ticks::toMs)
    val positionMs: Long get() = Ticks.toMs(userData.positionTicks)
    val playable: Boolean get() = type == JfType.Movie || type == JfType.Episode || type == JfType.Video
    /** 0..1 progress for "Continue watching" bars. */
    val progress: Float get() {
        val rt = runtimeTicks ?: return (userData.playedPercentage ?: 0.0).toFloat() / 100f
        return if (rt <= 0) 0f else (userData.positionTicks.toFloat() / rt).coerceIn(0f, 1f)
    }
    /** "S1:E3" for episodes. */
    val episodeLabel: String? get() =
        if (type == JfType.Episode && indexNumber != null) (parentIndexNumber?.let { "S$it:" } ?: "") + "E$indexNumber" else null
}

data class JfPage(val items: List<JfItem>, val total: Int)

enum class StreamType { Video, Audio, Subtitle, Other }

data class JfStream(
    val index: Int,
    val type: StreamType,
    val codec: String?,
    val language: String?,
    val title: String?,
    val displayTitle: String?,
    val isDefault: Boolean,
    val isForced: Boolean,
    val isExternal: Boolean,
    val isTextSubtitle: Boolean,
    val deliveryMethod: String?,
    val deliveryUrl: String?,
) {
    val label: String get() = displayTitle ?: listOfNotNull(title, language, codec?.uppercase()).joinToString(" · ").ifEmpty { "Track ${index}" }
}

data class JfMediaSource(
    val id: String,
    val container: String?,
    val supportsDirectPlay: Boolean,
    val supportsDirectStream: Boolean,
    val supportsTranscoding: Boolean,
    val transcodingUrl: String?,
    val transcodingSubProtocol: String?,
    val bitrate: Long?,
    val streams: List<JfStream>,
    val defaultAudioIndex: Int?,
    val defaultSubtitleIndex: Int?,
) {
    val audio get() = streams.filter { it.type == StreamType.Audio }
    val subtitles get() = streams.filter { it.type == StreamType.Subtitle }
}

data class JfPlaybackInfo(val playSessionId: String?, val sources: List<JfMediaSource>, val errorCode: String?)

object JfParse {
    const val ITEM_FIELDS = "Overview,Genres,People,ProductionYear,ChildCount,MediaSources,MediaStreams,PrimaryImageAspectRatio,ParentId"

    fun userData(o: JSONObject?): JfUserData = if (o == null) JfUserData() else JfUserData(
        positionTicks = o.long("PlaybackPositionTicks") ?: 0,
        played = o.bool("Played"),
        favorite = o.bool("IsFavorite"),
        playedPercentage = o.dbl("PlayedPercentage"),
        unplayedCount = o.int("UnplayedItemCount"),
    )

    fun item(o: JSONObject): JfItem {
        val tags = o.optJSONObject("ImageTags")
        return JfItem(
            id = o.getString("Id"),
            name = o.str("Name") ?: "Untitled",
            type = JfType.from(o.str("Type")),
            overview = o.str("Overview"),
            year = o.int("ProductionYear"),
            runtimeTicks = o.long("RunTimeTicks"),
            communityRating = o.dbl("CommunityRating"),
            criticRating = o.int("CriticRating"),
            officialRating = o.str("OfficialRating"),
            genres = o.optJSONArray("Genres").strings(),
            people = o.optJSONArray("People").objects().mapNotNull { p ->
                val id = p.str("Id") ?: return@mapNotNull null
                JfPerson(id, p.str("Name") ?: "", p.str("Role"), p.str("Type"), p.str("PrimaryImageTag"))
            },
            seriesId = o.str("SeriesId"),
            seriesName = o.str("SeriesName"),
            seasonId = o.str("SeasonId"),
            seasonName = o.str("SeasonName"),
            indexNumber = o.int("IndexNumber"),
            parentIndexNumber = o.int("ParentIndexNumber"),
            childCount = o.int("ChildCount"),
            primaryTag = tags?.str("Primary"),
            backdropTags = o.optJSONArray("BackdropImageTags").strings(),
            thumbTag = tags?.str("Thumb"),
            seriesPrimaryTag = o.str("SeriesPrimaryImageTag"),
            parentBackdropItemId = o.str("ParentBackdropItemId"),
            parentBackdropTags = o.optJSONArray("ParentBackdropImageTags").strings(),
            parentThumbItemId = o.str("ParentThumbItemId"),
            parentThumbTag = o.str("ParentThumbImageTag"),
            userData = userData(o.optJSONObject("UserData")),
            mediaSources = o.optJSONArray("MediaSources").objects().map(::mediaSource),
        )
    }

    fun page(o: JSONObject): JfPage {
        val items = o.optJSONArray("Items").objects().map(::item)
        return JfPage(items, o.int("TotalRecordCount") ?: items.size)
    }

    /** Some endpoints (Latest) return a bare array. */
    fun array(a: JSONArray): List<JfItem> = a.objects().map(::item)

    fun stream(o: JSONObject) = JfStream(
        index = o.int("Index") ?: -1,
        type = when (o.str("Type")) { "Video" -> StreamType.Video; "Audio" -> StreamType.Audio; "Subtitle" -> StreamType.Subtitle; else -> StreamType.Other },
        codec = o.str("Codec"),
        language = o.str("Language"),
        title = o.str("Title"),
        displayTitle = o.str("DisplayTitle"),
        isDefault = o.bool("IsDefault"),
        isForced = o.bool("IsForced"),
        isExternal = o.bool("IsExternal"),
        isTextSubtitle = o.bool("IsTextSubtitleStream"),
        deliveryMethod = o.str("DeliveryMethod"),
        deliveryUrl = o.str("DeliveryUrl"),
    )

    fun mediaSource(o: JSONObject) = JfMediaSource(
        id = o.str("Id") ?: "",
        container = o.str("Container"),
        supportsDirectPlay = o.bool("SupportsDirectPlay"),
        supportsDirectStream = o.bool("SupportsDirectStream"),
        supportsTranscoding = o.bool("SupportsTranscoding"),
        transcodingUrl = o.str("TranscodingUrl"),
        transcodingSubProtocol = o.str("TranscodingSubProtocol"),
        bitrate = o.long("Bitrate"),
        streams = o.optJSONArray("MediaStreams").objects().map(::stream),
        defaultAudioIndex = o.int("DefaultAudioStreamIndex"),
        defaultSubtitleIndex = o.int("DefaultSubtitleStreamIndex"),
    )

    fun playbackInfo(o: JSONObject) = JfPlaybackInfo(
        playSessionId = o.str("PlaySessionId"),
        sources = o.optJSONArray("MediaSources").objects().map(::mediaSource),
        errorCode = o.str("ErrorCode"),
    )
}

/**
 * What this phone can play natively with Media3 (hardware-accelerated codecs common on Android 13+).
 * Anything else is transcoded by Jellyfin to HLS (H.264 + AAC in MPEG-TS), which Media3 always plays.
 */
object DeviceProfile {
    const val DEFAULT_MAX_BITRATE = 40_000_000

    fun build(maxBitrate: Int = DEFAULT_MAX_BITRATE): JSONObject {
        fun direct(container: String, video: String, audio: String) = JSONObject()
            .put("Type", "Video").put("Container", container).put("VideoCodec", video).put("AudioCodec", audio)
        val v = "h264,hevc,vp9,av1,mpeg4"
        val a = "aac,mp3,opus,flac,vorbis,ac3,eac3,alac,pcm_s16le"
        val subs = JSONArray()
        for (f in listOf("srt", "subrip", "vtt", "webvtt", "ass", "ssa", "ttml")) subs.put(JSONObject().put("Format", f).put("Method", "External"))
        for (f in listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "mov_text", "dvbsub", "pgssub")) subs.put(JSONObject().put("Format", f).put("Method", "Embed"))
        for (f in listOf("pgssub", "dvdsub", "dvbsub", "pgs")) subs.put(JSONObject().put("Format", f).put("Method", "Encode"))
        return JSONObject()
            .put("Name", "Scrollback")
            .put("MaxStreamingBitrate", maxBitrate)
            .put("MaxStaticBitrate", 200_000_000)
            .put("MusicStreamingTranscodingBitrate", 320_000)
            .put("DirectPlayProfiles", JSONArray()
                .put(direct("mp4,m4v,mov", v, a))
                .put(direct("mkv,webm", v, a))
                .put(direct("ts,mpegts", "h264,hevc,mpeg2video", "aac,mp3,ac3,eac3"))
                .put(JSONObject().put("Type", "Audio").put("Container", "mp3,aac,m4a,flac,ogg,opus,wav")))
            .put("TranscodingProfiles", JSONArray()
                .put(JSONObject().put("Type", "Video").put("Container", "ts").put("Protocol", "hls")
                    .put("VideoCodec", "h264,hevc").put("AudioCodec", "aac,mp3,ac3").put("Context", "Streaming")
                    .put("MaxAudioChannels", "6").put("MinSegments", 1).put("BreakOnNonKeyFrames", true))
                .put(JSONObject().put("Type", "Audio").put("Container", "mp3").put("Protocol", "http").put("AudioCodec", "mp3").put("Context", "Streaming")))
            .put("ContainerProfiles", JSONArray())
            .put("CodecProfiles", JSONArray())
            .put("SubtitleProfiles", subs)
    }
}

enum class PlayMethod { DirectPlay, DirectStream, Transcode }

data class StreamChoice(
    val url: String,
    val method: PlayMethod,
    /** Media3 MIME hint: HLS for transcodes, the container type for direct play (null = sniff). */
    val mimeType: String?,
    val mediaSourceId: String,
)

data class ExternalSub(val streamIndex: Int, val url: String, val mimeType: String, val language: String?, val label: String, val isDefault: Boolean, val isForced: Boolean)

object StreamSelector {
    /**
     * Direct play (the original file, `static=true`) when the server says this profile can play it,
     * otherwise the server's HLS transcoding URL. Auth travels in headers, so `api_key` is stripped
     * from server-made URLs to keep tokens out of cache keys and logs.
     */
    fun select(baseUrl: String, itemId: String, source: JfMediaSource, playSessionId: String?, deviceId: String): StreamChoice? {
        val base = baseUrl.trimEnd('/')
        if (source.supportsDirectPlay || source.supportsDirectStream) {
            val url = buildString {
                append("$base/Videos/$itemId/stream")
                source.container?.substringBefore(',')?.let { append(".").append(it) }
                append("?static=true&mediaSourceId=").append(enc(source.id))
                append("&deviceId=").append(enc(deviceId))
                playSessionId?.let { append("&playSessionId=").append(enc(it)) }
            }
            return StreamChoice(url, if (source.supportsDirectPlay) PlayMethod.DirectPlay else PlayMethod.DirectStream, containerMime(source.container), source.id)
        }
        val t = source.transcodingUrl ?: return null
        val abs = if (t.startsWith("http://") || t.startsWith("https://")) t else base + (if (t.startsWith("/")) t else "/$t")
        val hls = source.transcodingSubProtocol.equals("hls", true) || abs.substringBefore('?').endsWith(".m3u8")
        return StreamChoice(stripApiKey(abs), PlayMethod.Transcode, if (hls) "application/x-mpegURL" else null, source.id)
    }

    fun stripApiKey(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val kept = url.substring(q + 1).split('&').filter { p ->
            val k = p.substringBefore('=').lowercase()
            k != "api_key" && k != "apikey"
        }
        return url.substring(0, q) + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
    }

    fun containerMime(container: String?): String? = when (container?.substringBefore(',')?.lowercase()) {
        "mp4", "m4v", "mov" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts", "mpegts" -> "video/mp2t"
        else -> null
    }

    /** Text subtitles the server delivers as separate files (external files, or extracted on demand). */
    fun externalSubs(baseUrl: String, itemId: String, source: JfMediaSource): List<ExternalSub> {
        val base = baseUrl.trimEnd('/')
        return source.subtitles.filter { it.deliveryMethod.equals("External", true) || (it.deliveryMethod == null && it.isExternal && it.isTextSubtitle) }
            .mapNotNull { s ->
                val fmt = subFormat(s.codec) ?: return@mapNotNull null
                val url = s.deliveryUrl?.let { u -> stripApiKey(if (u.startsWith("http")) u else base + (if (u.startsWith("/")) u else "/$u")) }
                    ?: "$base/Videos/$itemId/${source.id}/Subtitles/${s.index}/0/Stream.${fmt.first}"
                ExternalSub(s.index, url, fmt.second, s.language, s.label, s.isDefault, s.isForced)
            }
    }

    /** codec -> (file extension, Media3 MIME) */
    fun subFormat(codec: String?): Pair<String, String>? = when (codec?.lowercase()) {
        "srt", "subrip" -> "srt" to "application/x-subrip"
        "vtt", "webvtt" -> "vtt" to "text/vtt"
        "ass", "ssa" -> "ass" to "text/x-ssa"
        "ttml" -> "ttml" to "application/ttml+xml"
        else -> null
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}

/**
 * Maps Jellyfin stream indexes to Media3 track groups when playing the original file: Media3 lists
 * embedded audio/text tracks in container order, which is the order Jellyfin reports them in.
 * External subtitles are side-loaded with the id `jf-sub-<index>`.
 */
object TrackMapping {
    const val SUB_ID_PREFIX = "jf-sub-"
    fun subId(streamIndex: Int) = "$SUB_ID_PREFIX$streamIndex"

    fun audioOrdinal(source: JfMediaSource, streamIndex: Int): Int? =
        source.audio.indexOfFirst { it.index == streamIndex }.takeIf { it >= 0 }

    private fun JfStream.embedded() = !isExternal && !deliveryMethod.equals("External", true)

    fun embeddedSubOrdinal(source: JfMediaSource, streamIndex: Int): Int? {
        val s = source.subtitles.firstOrNull { it.index == streamIndex } ?: return null
        if (!s.embedded()) return null
        return source.subtitles.filter { it.embedded() }.indexOf(s)
    }

    /** True when this subtitle needs the server (burn-in/transcode) instead of a client-side track. */
    fun needsServer(source: JfMediaSource, streamIndex: Int, method: PlayMethod): Boolean {
        val s = source.subtitles.firstOrNull { it.index == streamIndex } ?: return false
        if (s.deliveryMethod.equals("External", true)) return false
        return method == PlayMethod.Transcode || s.deliveryMethod.equals("Encode", true)
    }
}
