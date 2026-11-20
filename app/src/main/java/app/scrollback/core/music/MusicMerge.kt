// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.core.music

import app.scrollback.data.db.AlbumRow
import app.scrollback.data.db.ArtistRow
import app.scrollback.data.db.MediaEntity
import java.text.Normalizer
import kotlin.math.abs

/**
 * "Hide duplicates across servers" (v0.14): the same song indexed from several sources (for
 * example one music folder served by both Nextcloud Music and Navidrome) is shown once.
 *
 * This is a view-level merge only. Nothing is deleted or changed in the database, on the phone or
 * on any server; every copy stays indexed and playable ("Play from…").
 */
object MusicMatch {
    private val marks = Regex("\\p{M}+")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")
    private val trackPrefix = Regex("^\\s*\\d{1,3}(\\s*[-._)]\\s*|\\s+)")
    private val artistSplit = Regex("(?i)\\s+(feat\\.?|ft\\.?|featuring|with|vs\\.?|x)\\s+|\\s*[,;/&×+]\\s*")
    private val various = setOf("various artists", "various", "va", "verschiedene interpreten", "unknown artist", "unknown")

    /** Lower-case, accents removed, punctuation folded to spaces, "&" = "and", leading "the " dropped. */
    fun norm(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val t = Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFKD).replace(marks, "")
            .replace("&", " and ").replace(nonWord, " ").trim().replace(Regex("\\s+"), " ")
        return t.removePrefix("the ").trim()
    }

    /** Title tag, else the file name without extension and leading track number ("03 - Song.flac" -> "song"). */
    fun titleKey(m: MediaEntity): String {
        val t = m.title?.takeIf { it.isNotBlank() } ?: m.name.substringBeforeLast('.').replace(trackPrefix, "")
        return norm(t)
    }

    fun primaryArtist(s: String?): String = norm(s?.split(artistSplit)?.firstOrNull { it.isNotBlank() })

    private fun isVarious(s: String?) = norm(s) in various

    /** Missing, "Various Artists", equal, or the same main artist (before "feat.", "&", ","…). */
    fun artistsCompatible(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank() || isVarious(a) || isVarious(b)) return true
        return norm(a) == norm(b) || primaryArtist(a) == primaryArtist(b)
    }

    fun albumsCompatible(a: String?, b: String?): Boolean =
        a.isNullOrBlank() || b.isNullOrBlank() || norm(a) == norm(b)

    private fun hasFullTags(m: MediaEntity) = !m.artist.isNullOrBlank() && !m.album.isNullOrBlank() && !isVarious(m.artist)

    /** Whether [a] and [b] (from different sources) are the same recording. */
    fun sameTrack(a: MediaEntity, b: MediaEntity): Boolean {
        if (a.sourceId == b.sourceId || a.kind != b.kind) return false
        val ta = titleKey(a)
        if (ta.isEmpty() || ta != titleKey(b)) return false
        val durationsKnown = a.durationMs > 0 && b.durationMs > 0
        if (durationsKnown && abs(a.durationMs - b.durationMs) > DURATION_TOLERANCE_MS) return false
        if (a.trackNo > 0 && b.trackNo > 0 && a.trackNo != b.trackNo) return false
        if (a.discNo > 0 && b.discNo > 0 && a.discNo != b.discNo) return false
        if (!artistsCompatible(a.artist, b.artist) || !albumsCompatible(a.album, b.album)) return false
        if (hasFullTags(a) && hasFullTags(b)) return true
        // Tags missing on one side: only merge with extra evidence (same length, or byte-identical size).
        return durationsKnown || (a.size > 0 && a.size == b.size)
    }

    const val DURATION_TOLERANCE_MS = 2_000L
}

/**
 * Groups of copies of the same song, each ordered by the user's preferred source order.
 * Built once from all songs and then applied to any song list, so every screen agrees.
 */
class MusicGroups private constructor(
    val groups: List<List<MediaEntity>>,
    private val groupOf: Map<Long, Int>,
    private val rank: (MediaEntity) -> Int,
) {
    /** All copies of [id]'s song, preferred first; empty when the song has a single copy. */
    fun copiesOf(id: Long): List<MediaEntity> = groupOf[id]?.let { groups[it] }.orEmpty()

    /** Number of songs that have more than one copy, and how many rows are hidden by merging. */
    val mergedSongs: Int get() = groups.size
    val hiddenCopies: Int get() = groups.sumOf { it.size - 1 }

    /**
     * Shows each song once: the preferred copy present in [list], at the position of the song's first
     * copy in [list]. A merged song counts as liked when any copy is liked.
     */
    fun apply(list: List<MediaEntity>): List<MediaEntity> {
        if (groups.isEmpty()) return list
        val out = ArrayList<MediaEntity>(list.size)
        val slot = HashMap<Int, Int>()
        for (m in list) {
            val g = groupOf[m.id]
            if (g == null) { out += m; continue }
            val i = slot[g]
            if (i == null) { slot[g] = out.size; out += m }
            else if (rank(m) < rank(out[i])) out[i] = m
        }
        for ((g, i) in slot) {
            val copies = groups[g]
            val liked = copies.any { it.liked }
            if (liked != out[i].liked) out[i] = out[i].copy(liked = liked, likedAt = copies.maxOf { it.likedAt })
        }
        return out
    }

    companion object {
        val EMPTY = MusicGroups(emptyList(), emptyMap()) { 0 }

        /** Rank of a source in [order] (source ids, preferred first); unknown sources after, oldest first. */
        fun ranker(order: List<Long>): (MediaEntity) -> Int = { m ->
            val i = order.indexOf(m.sourceId)
            if (i >= 0) i else order.size + m.sourceId.toInt().coerceAtLeast(0)
        }

        fun build(songs: List<MediaEntity>, order: List<Long>): MusicGroups {
            val rank = ranker(order)
            val clusters = ArrayList<MutableList<MediaEntity>>()
            for ((_, bucket) in songs.groupBy { MusicMatch.titleKey(it) }) {
                if (bucket.size < 2 || bucket.all { it.sourceId == bucket[0].sourceId }) continue
                val local = ArrayList<MutableList<MediaEntity>>()
                for (m in bucket) {
                    val c = local.firstOrNull { cl -> cl.none { it.sourceId == m.sourceId } && cl.all { MusicMatch.sameTrack(it, m) } }
                    if (c != null) c += m else local += mutableListOf(m)
                }
                local.filter { it.size > 1 }.forEach { clusters += it }
            }
            val groups = clusters.map { c -> c.sortedWith(compareBy<MediaEntity>({ rank(it) }, { it.id })) }
            val groupOf = HashMap<Long, Int>()
            groups.forEachIndexed { gi, g -> g.forEach { groupOf[it.id] = gi } }
            return MusicGroups(groups, groupOf, rank)
        }
    }
}

/** Artist and album rows computed from an already-merged song list (counts each song once). */
object MergedBrowse {
    private const val UNKNOWN_ARTIST = "Unknown artist"
    private const val UNKNOWN_ALBUM = "Unknown album"

    private fun artistOf(m: MediaEntity) = m.artist?.takeIf { it.isNotBlank() } ?: UNKNOWN_ARTIST
    private fun albumOf(m: MediaEntity) = m.album?.takeIf { it.isNotBlank() } ?: UNKNOWN_ALBUM

    /** Display spelling: the most common one in the group. */
    private fun display(names: List<String>) = names.groupingBy { it }.eachCount().maxWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).key

    fun artists(songs: List<MediaEntity>): List<ArtistRow> =
        songs.groupBy { MusicMatch.norm(artistOf(it)) }.values.map { g ->
            ArtistRow(
                artist = display(g.map(::artistOf)),
                songs = g.size,
                albums = g.map { MusicMatch.norm(albumOf(it)) }.distinct().size,
                cover = g.mapNotNull { it.thumbUri }.maxOrNull(),
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist })

    fun albums(songs: List<MediaEntity>): List<AlbumRow> =
        songs.groupBy { MusicMatch.norm(albumOf(it)) }.values.map { g ->
            AlbumRow(
                album = display(g.map(::albumOf)),
                artist = g.mapNotNull { it.artist?.takeIf { a -> a.isNotBlank() } }.takeIf { it.isNotEmpty() }?.let(::display),
                songs = g.size,
                cover = g.mapNotNull { it.thumbUri }.maxOrNull(),
                year = g.maxOf { it.dateModified },
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.album })

    fun artistSongs(songs: List<MediaEntity>, artist: String): List<MediaEntity> {
        val key = MusicMatch.norm(artist)
        return songs.filter { MusicMatch.norm(artistOf(it)) == key }
            .sortedWith(compareBy<MediaEntity, String>(String.CASE_INSENSITIVE_ORDER) { albumOf(it) }.thenBy { it.discNo }.thenBy { it.trackNo }.thenBy { it.name })
    }

    fun albumSongs(songs: List<MediaEntity>, album: String): List<MediaEntity> {
        val key = MusicMatch.norm(album)
        return songs.filter { MusicMatch.norm(albumOf(it)) == key }.sortedWith(compareBy<MediaEntity> { it.discNo }.thenBy { it.trackNo }.thenBy { it.name })
    }
}
