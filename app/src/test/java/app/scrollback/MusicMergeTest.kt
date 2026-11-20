// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback

import app.scrollback.accounts.AccountType
import app.scrollback.accounts.ServerAccount
import app.scrollback.core.music.MergedBrowse
import app.scrollback.core.music.MusicGroups
import app.scrollback.core.music.MusicMatch
import app.scrollback.data.db.MediaEntity
import app.scrollback.remote.music.JellyfinClient
import app.scrollback.remote.music.SubsonicClient
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** v0.14 "Hide duplicates across servers": matcher, merge and like propagation (stubs only). */
class MusicMergeTest {
    private var nextId = 1L
    private fun song(
        source: Long, title: String?, artist: String? = "Daft Punk", album: String? = "Discovery",
        dur: Long = 320_000, track: Int = 1, disc: Int = 0, name: String = "${title ?: "x"}.flac", size: Long = 0, liked: Boolean = false,
    ) = MediaEntity(
        id = nextId++, sourceId = source, uri = "u${nextId}", name = name, relativePath = "", mime = "audio/flac", kind = "audio",
        size = size, dateModified = 0, dateAdded = 0, durationMs = dur, title = title, artist = artist, album = album,
        trackNo = track, discNo = disc, liked = liked, likedAt = if (liked) 5 else 0,
    )

    @Test fun normalisation() {
        assertEquals("beyonce", MusicMatch.norm("Beyoncé"))
        assertEquals("simon and garfunkel", MusicMatch.norm("Simon & Garfunkel"))
        assertEquals("beatles", MusicMatch.norm("The Beatles"))
        assertEquals("one more time", MusicMatch.norm("  One   More Time! "))
        assertEquals("daft punk", MusicMatch.primaryArtist("Daft Punk feat. Romanthony"))
        assertEquals("song", MusicMatch.titleKey(song(1, null, name = "03 - Song.mp3")))
        assertEquals("1979", MusicMatch.titleKey(song(1, null, name = "1979.mp3")))
    }

    @Test fun sameSongOnTwoServersMatches() {
        val nc = song(1, "One More Time", dur = 320_400)
        val nd = song(2, "One more time", dur = 321_900)
        assertTrue(MusicMatch.sameTrack(nc, nd))
        // Featuring credits and Various Artists compilations still match.
        assertTrue(MusicMatch.sameTrack(song(1, "Song", artist = "A feat. B"), song(2, "Song", artist = "A")))
        assertTrue(MusicMatch.sameTrack(song(1, "Song", artist = "Various Artists"), song(2, "Song", artist = "Real Artist")))
    }

    @Test fun differentRecordingsDoNotMatch() {
        assertFalse("same source", MusicMatch.sameTrack(song(1, "A"), song(1, "A")))
        assertFalse("duration > 2 s", MusicMatch.sameTrack(song(1, "A", dur = 200_000), song(2, "A", dur = 202_500)))
        assertFalse("track number", MusicMatch.sameTrack(song(1, "A", track = 1), song(2, "A", track = 2)))
        assertFalse("disc number", MusicMatch.sameTrack(song(1, "A", disc = 1), song(2, "A", disc = 2)))
        assertFalse("album", MusicMatch.sameTrack(song(1, "A", album = "Live"), song(2, "A", album = "Studio")))
        assertFalse("artist", MusicMatch.sameTrack(song(1, "Intro", artist = "X"), song(2, "Intro", artist = "Y")))
    }

    @Test fun missingTagsNeedExtraEvidence() {
        // Untagged file vs tagged copy: OK when durations agree...
        assertTrue(MusicMatch.sameTrack(song(1, null, artist = null, album = null, name = "01 - Aerodynamic.flac"), song(2, "Aerodynamic")))
        // ...or the files are byte-identical in size; without either, keep both.
        assertTrue(MusicMatch.sameTrack(song(1, "A", artist = null, album = null, dur = 0, size = 1234), song(2, "A", dur = 0, size = 1234)))
        assertFalse(MusicMatch.sameTrack(song(1, "A", artist = null, album = null, dur = 0), song(2, "A", dur = 0)))
        // Unknown track numbers don't block a match.
        assertTrue(MusicMatch.sameTrack(song(1, "A", track = 0), song(2, "A", track = 4)))
    }

    @Test fun mergeShowsEachSongOnceInPreferredSourceAndPosition() {
        val a1 = song(1, "Aerodynamic", track = 2)
        val b1 = song(2, "Aerodynamic", track = 2)
        val a2 = song(1, "Digital Love", track = 3)
        val b2 = song(2, "Digital Love", track = 3, liked = true)
        val only = song(2, "Bonus", track = 9)
        val all = listOf(a1, b1, a2, b2, only)
        val preferNavidrome = MusicGroups.build(all, order = listOf(2L, 1L))
        assertEquals(2, preferNavidrome.mergedSongs)
        assertEquals(2, preferNavidrome.hiddenCopies)
        val shown = preferNavidrome.apply(all)
        assertEquals(listOf(b1.id, b2.id, only.id), shown.map { it.id })
        val preferNextcloud = MusicGroups.build(all, order = listOf(1L, 2L)).apply(all)
        assertEquals(listOf(a1.id, a2.id, only.id), preferNextcloud.map { it.id })
        // Liked if any copy is liked.
        assertTrue(preferNextcloud[1].liked)
        assertFalse(preferNextcloud[0].liked)
        // A list that only contains one copy keeps it.
        assertEquals(listOf(a1.id), preferNavidrome.apply(listOf(a1)).map { it.id })
        assertEquals(listOf(b1.id, a1.id), preferNavidrome.copiesOf(a1.id).map { it.id })
        assertEquals(setOf(a1.id, b1.id), preferNavidrome.likeTargets(a1.id).toSet())
        assertEquals(listOf(only.id), preferNavidrome.likeTargets(only.id))
        // The original list is untouched (view-only merge).
        assertEquals(5, all.size)
    }

    @Test fun mergedArtistAndAlbumRowsCountOnce() {
        val songs = listOf(
            song(1, "A", artist = "Daft Punk", album = "Discovery"), song(2, "A", artist = "daft punk", album = "Discovery"),
            song(1, "B", artist = "Daft Punk", album = "Homework", track = 2), song(2, "C", artist = "Air", album = "Moon Safari", track = 3),
        )
        val merged = MusicGroups.build(songs, emptyList()).apply(songs)
        assertEquals(3, merged.size)
        val artists = MergedBrowse.artists(merged)
        assertEquals(listOf("Air", "Daft Punk"), artists.map { it.artist })
        assertEquals(2, artists[1].songs)
        assertEquals(2, artists[1].albums)
        assertEquals(listOf("Discovery", "Homework", "Moon Safari"), MergedBrowse.albums(merged).map { it.album })
        assertEquals(2, MergedBrowse.artistSongs(merged, "DAFT PUNK").size)
        assertEquals(1, MergedBrowse.albumSongs(merged, "discovery").size)
    }

    /** A like on a merged song reaches every copy's server: Subsonic star + Jellyfin favourite (stub servers only). */
    @Test fun likeAppliesToEveryCopyOnStubServers() {
        val seen = CopyOnWriteArrayList<RecordedRequest>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return when {
                    request.url.encodedPath.startsWith("/rest/star") -> MockResponse.Builder().code(200).body("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""").build()
                    request.url.encodedPath.startsWith("/UserFavoriteItems/") -> MockResponse.Builder().code(200).body("{}").build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        server.use {
            val base = server.url("/").toString().trimEnd('/')
            val http = OkHttpClient()
            val sub = SubsonicClient(http, ServerAccount(type = AccountType.SUBSONIC, baseUrl = base, loginName = "u"), "pw")
            val jf = JellyfinClient(http, ServerAccount(type = AccountType.JELLYFIN, baseUrl = base, loginName = "u", userId = "uid"))
            val navidrome = song(1, "Song").copy(remoteId = "sub-1")
            val jellyfin = song(2, "Song").copy(remoteId = "jf-1")
            val groups = MusicGroups.build(listOf(navidrome, jellyfin), emptyList())
            val byId = mapOf(navidrome.id to { sub.star("sub-1", true) }, jellyfin.id to { jf.favorite("jf-1", true) })
            groups.likeTargets(navidrome.id).forEach { byId.getValue(it)() }
            assertTrue(seen.any { it.url.encodedPath == "/rest/star.view" && it.url.queryParameter("id") == "sub-1" })
            assertTrue(seen.any { it.method == "POST" && it.url.encodedPath == "/UserFavoriteItems/jf-1" })
        }
    }
}
