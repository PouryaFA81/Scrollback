package app.scrollback

import app.scrollback.remote.HttpException
import app.scrollback.remote.jellyfin.DeviceProfile
import app.scrollback.remote.jellyfin.JellyfinVideoApi
import app.scrollback.remote.jellyfin.JfParse
import app.scrollback.remote.jellyfin.JfType
import app.scrollback.remote.jellyfin.PlayMethod
import app.scrollback.remote.jellyfin.StreamSelector
import app.scrollback.remote.jellyfin.StreamType
import app.scrollback.remote.jellyfin.Ticks
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class JellyfinVideoTest {
    private val movie = """
        {"Id":"m1","Name":"Blade Runner","Type":"Movie","ProductionYear":1982,"RunTimeTicks":70200000000,
         "CommunityRating":7.9,"OfficialRating":"R","Overview":"A blade runner must pursue replicants.",
         "Genres":["Sci-Fi","Thriller"],"People":[{"Id":"p1","Name":"Harrison Ford","Role":"Deckard","Type":"Actor","PrimaryImageTag":"pt"},{"Name":"No id"}],
         "ImageTags":{"Primary":"ptag"},"BackdropImageTags":["btag"],
         "UserData":{"PlaybackPositionTicks":35100000000,"Played":false,"IsFavorite":true,"PlayedPercentage":50.0},
         "SeriesId":null,"Overview2":null}
    """.trimIndent()

    private val episode = """
        {"Id":"e3","Name":"Pilot","Type":"Episode","SeriesId":"s1","SeriesName":"Show","SeasonId":"se1","IndexNumber":3,"ParentIndexNumber":1,
         "ImageTags":{"Primary":"still"},"SeriesPrimaryImageTag":"spt","ParentBackdropItemId":"s1","ParentBackdropImageTags":["sb"],
         "UserData":{"Played":true}}
    """.trimIndent()

    private val playbackDirect = """
        {"PlaySessionId":"ps1","MediaSources":[{"Id":"src1","Container":"mkv","SupportsDirectPlay":true,"SupportsDirectStream":true,"SupportsTranscoding":true,
          "DefaultAudioStreamIndex":1,"DefaultSubtitleStreamIndex":3,
          "MediaStreams":[
            {"Index":0,"Type":"Video","Codec":"hevc"},
            {"Index":1,"Type":"Audio","Codec":"eac3","Language":"eng","DisplayTitle":"English - EAC3 - 5.1","IsDefault":true},
            {"Index":2,"Type":"Subtitle","Codec":"subrip","Language":"eng","IsTextSubtitleStream":true,"IsExternal":false,"DeliveryMethod":"Embed"},
            {"Index":3,"Type":"Subtitle","Codec":"srt","Language":"fas","IsTextSubtitleStream":true,"IsExternal":true,"DeliveryMethod":"External",
             "DeliveryUrl":"/Videos/m1/src1/Subtitles/3/0/Stream.srt?api_key=SECRET"},
            {"Index":4,"Type":"Subtitle","Codec":"PGSSUB","IsTextSubtitleStream":false,"DeliveryMethod":"Encode"}
          ]}]}
    """.trimIndent()

    private val playbackTranscode = """
        {"PlaySessionId":"ps2","MediaSources":[{"Id":"src2","Container":"avi","SupportsDirectPlay":false,"SupportsDirectStream":false,"SupportsTranscoding":true,
          "TranscodingUrl":"/videos/m1/master.m3u8?DeviceId=d&MediaSourceId=src2&api_key=SECRET&VideoCodec=h264","TranscodingSubProtocol":"hls","MediaStreams":[]}]}
    """.trimIndent()

    @Test fun parsesMovieDetails() {
        val m = JfParse.item(JSONObject(movie))
        assertEquals(JfType.Movie, m.type)
        assertEquals(1982, m.year)
        assertEquals(117 * 60_000L, m.runtimeMs)
        assertEquals(listOf("Sci-Fi", "Thriller"), m.genres)
        assertEquals(1, m.people.size) // entries without Id are skipped
        assertEquals("Deckard", m.people[0].role)
        assertEquals("ptag", m.primaryTag)
        assertEquals(listOf("btag"), m.backdropTags)
        assertTrue(m.userData.favorite)
        assertEquals(3_510_000L, m.positionMs)
        assertEquals(0.5f, m.progress, 0.001f)
        assertNull(m.seriesId) // JSON null is not the string "null"
        assertTrue(m.playable)
    }

    @Test fun parsesEpisodeAndLabels() {
        val e = JfParse.item(JSONObject(episode))
        assertEquals("S1:E3", e.episodeLabel)
        assertTrue(e.userData.played)
        assertEquals("s1", e.seriesId)
        val api = JellyfinVideoApi(OkHttpClient(), "https://jf.example.com/", "u1", "d1", "0.12.0") { null }
        assertEquals("https://jf.example.com/Items/s1/Images/Primary?fillHeight=600&quality=90&tag=spt", api.poster(e))
        assertEquals("https://jf.example.com/Items/e3/Images/Primary?fillHeight=360&quality=90&tag=still", api.wide(e))
    }

    @Test fun ticks() {
        assertEquals(1L, Ticks.toMs(10_000))
        assertEquals(50_000_000L, Ticks.fromMs(5_000))
    }

    @Test fun directPlayUsesStaticStream() {
        val info = JfParse.playbackInfo(JSONObject(playbackDirect))
        val src = info.sources.single()
        assertEquals(1, src.audio.size)
        assertEquals(3, src.subtitles.size)
        assertEquals(StreamType.Video, src.streams[0].type)
        val c = StreamSelector.select("https://jf.example.com", "m1", src, info.playSessionId, "dev 1")!!
        assertEquals(PlayMethod.DirectPlay, c.method)
        assertEquals("video/x-matroska", c.mimeType)
        assertEquals("https://jf.example.com/Videos/m1/stream.mkv?static=true&mediaSourceId=src1&deviceId=dev+1&playSessionId=ps1", c.url)
    }

    @Test fun transcodeFallsBackToHlsWithoutToken() {
        val info = JfParse.playbackInfo(JSONObject(playbackTranscode))
        val c = StreamSelector.select("https://jf.example.com/", "m1", info.sources.single(), info.playSessionId, "d")!!
        assertEquals(PlayMethod.Transcode, c.method)
        assertEquals("application/x-mpegURL", c.mimeType)
        assertEquals("https://jf.example.com/videos/m1/master.m3u8?DeviceId=d&MediaSourceId=src2&VideoCodec=h264", c.url)
        assertFalse(c.url.contains("SECRET"))
    }

    @Test fun noPlayableSourceGivesNull() {
        val src = JfParse.mediaSource(JSONObject("""{"Id":"x","SupportsDirectPlay":false,"SupportsDirectStream":false,"SupportsTranscoding":false}"""))
        assertNull(StreamSelector.select("https://a", "i", src, null, "d"))
    }

    @Test fun externalSubtitlesOnlyTextDeliveredExternally() {
        val src = JfParse.playbackInfo(JSONObject(playbackDirect)).sources.single()
        val subs = StreamSelector.externalSubs("https://jf.example.com", "m1", src)
        assertEquals(1, subs.size)
        assertEquals(3, subs[0].streamIndex)
        assertEquals("application/x-subrip", subs[0].mimeType)
        assertEquals("https://jf.example.com/Videos/m1/src1/Subtitles/3/0/Stream.srt", subs[0].url)
        assertEquals("fas", subs[0].language)
        assertEquals("text/x-ssa", StreamSelector.subFormat("ASS")!!.second)
        assertNull(StreamSelector.subFormat("pgssub"))
    }

    @Test fun deviceProfileOffersHlsTranscodingAndExternalSubs() {
        val p = DeviceProfile.build(8_000_000)
        assertEquals(8_000_000, p.getInt("MaxStreamingBitrate"))
        val t = p.getJSONArray("TranscodingProfiles").getJSONObject(0)
        assertEquals("hls", t.getString("Protocol"))
        assertTrue(t.getString("VideoCodec").contains("h264"))
        val subs = p.getJSONArray("SubtitleProfiles")
        val external = (0 until subs.length()).map { subs.getJSONObject(it) }.filter { it.getString("Method") == "External" }.map { it.getString("Format") }
        assertTrue("srt" in external && "ass" in external)
        val direct = p.getJSONArray("DirectPlayProfiles").getJSONObject(0)
        assertTrue(direct.getString("VideoCodec").contains("hevc"))
    }

    /** Fake Jellyfin: 10.9+ routes for most calls, but only legacy routes for "played" (404 on the new one). */
    @Test fun fakeServerApiFlow() {
        val seen = CopyOnWriteArrayList<RecordedRequest>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seen += request
                    val path = request.url.encodedPath
                    val ok = { body: String -> MockResponse.Builder().code(200).body(body).build() }
                    if (request.headers["X-Emby-Token"] != "tok") return MockResponse.Builder().code(401).build()
                    return when {
                        path == "/UserItems/Resume" -> ok("""{"Items":[$movie],"TotalRecordCount":1}""")
                        path == "/Shows/NextUp" -> ok("""{"Items":[$episode],"TotalRecordCount":1}""")
                        path == "/Items" -> ok("""{"Items":[$movie],"TotalRecordCount":41}""")
                        path == "/Items/m1" -> ok(movie)
                        path == "/Items/m1/PlaybackInfo" -> ok(playbackDirect)
                        path.startsWith("/Sessions/Playing") -> MockResponse.Builder().code(204).build()
                        path == "/UserPlayedItems/m1" -> MockResponse.Builder().code(404).build()
                        path == "/Users/u1/PlayedItems/m1" -> ok("""{"Played":true,"PlaybackPositionTicks":0}""")
                        path == "/UserFavoriteItems/m1" -> ok("""{"IsFavorite":false}""")
                        else -> MockResponse.Builder().code(404).build()
                    }
                }
            }
            server.start()
            val base = server.url("/").toString()
            val api = JellyfinVideoApi(OkHttpClient(), base, "u1", "dev1", "0.12.0") { "tok" }

            val resume = api.resume()
            assertEquals("m1", resume.single().id)
            assertEquals("S1:E3", api.nextUp().single().episodeLabel)
            val page = api.items(JellyfinVideoApi.Query(types = "Movie", start = 0, limit = 1))
            assertEquals(41, page.total)
            assertEquals("Blade Runner", api.item("m1").name)

            val info = api.playbackInfo("m1", startMs = 3_510_000, audioIndex = 1, subtitleIndex = 3)
            val choice = StreamSelector.select(api.base, "m1", info.sources.single(), info.playSessionId, api.deviceId)!!
            assertTrue(choice.url.startsWith(base.trimEnd('/') + "/Videos/m1/stream.mkv?static=true"))

            val r = JellyfinVideoApi.Report("m1", "src1", info.playSessionId, 3_520_000, false, choice.method, 1, 3)
            api.reportStart(r)
            api.reportProgress(r.copy(paused = true), "Pause")
            api.reportStopped(r.copy(positionMs = 3_600_000))

            assertTrue(api.setPlayed("m1", true)!!.played)
            assertFalse(api.setFavorite("m1", false)!!.favorite)

            // Request details
            val pb = seen.first { it.url.encodedPath == "/Items/m1/PlaybackInfo" }
            assertEquals("POST", pb.method)
            val body = JSONObject(pb.body!!.utf8())
            assertEquals(35_100_000_000L, body.getLong("StartTimeTicks"))
            assertEquals(1, body.getInt("AudioStreamIndex"))
            assertEquals(3, body.getInt("SubtitleStreamIndex"))
            assertTrue(body.getJSONObject("DeviceProfile").has("TranscodingProfiles"))
            assertEquals("u1", pb.url.queryParameter("userId"))
            assertTrue(pb.headers["Authorization"]!!.contains("Token=\"tok\""))

            val items = seen.first { it.url.encodedPath == "/Items" }
            assertEquals("Movie", items.url.queryParameter("IncludeItemTypes"))
            assertEquals("1", items.url.queryParameter("Limit"))

            val started = seen.first { it.url.encodedPath == "/Sessions/Playing" }
            assertEquals(35_200_000_000L, JSONObject(started.body!!.utf8()).getLong("PositionTicks"))
            assertEquals("DirectPlay", JSONObject(started.body!!.utf8()).getString("PlayMethod"))
            val progress = JSONObject(seen.first { it.url.encodedPath == "/Sessions/Playing/Progress" }.body!!.utf8())
            assertTrue(progress.getBoolean("IsPaused"))
            assertEquals("Pause", progress.getString("EventName"))
            val stopped = JSONObject(seen.first { it.url.encodedPath == "/Sessions/Playing/Stopped" }.body!!.utf8())
            assertEquals(36_000_000_000L, stopped.getLong("PositionTicks"))
            assertEquals("ps1", stopped.getString("PlaySessionId"))

            // Played: new route 404 -> legacy route POST
            val played = seen.filter { it.url.encodedPath.contains("PlayedItems") }
            assertEquals(listOf("/UserPlayedItems/m1", "/Users/u1/PlayedItems/m1"), played.map { it.url.encodedPath })
            assertTrue(played.all { it.method == "POST" })
            assertEquals("DELETE", seen.first { it.url.encodedPath == "/UserFavoriteItems/m1" }.method)
        }
    }

    @Test fun expiredTokenIsReportedClearly() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(401).build())
            server.start()
            val api = JellyfinVideoApi(OkHttpClient(), server.url("/").toString(), "u1", "d", "0.12.0") { "old" }
            try { api.resume(); fail() } catch (e: HttpException) {
                assertEquals(401, e.code)
                assertTrue(e.message!!.contains("sign in again"))
            }
        }
    }
}
