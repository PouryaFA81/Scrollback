// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.feature.feed

import android.net.Uri
import app.scrollback.data.db.MediaEntity
import app.scrollback.data.repo.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What a feed shows. Every feed (tabs, playlists, search results, pictures) is the same pager. */
sealed interface FeedSpec {
    val title: String

    data class Random(val seed: Long) : FeedSpec { override val title = "Random" }
    /** [folder]: v0.15 folder chooser, "" = all folders (see [app.scrollback.core.FolderFilter]). */
    data class Saved(val newestFirst: Boolean, val folder: String = "") : FeedSpec { override val title = "Saved order" }
    data class Pictures(val newestFirst: Boolean) : FeedSpec { override val title = "Pictures" }
    data object Liked : FeedSpec { override val title = "Liked" }
    data object History : FeedSpec { override val title = "History" }
    data object Hidden : FeedSpec { override val title = "Hidden" }
    data class Playlist(val id: Long, override val title: String = "Playlist") : FeedSpec
    data class Search(val query: String, val tagIds: List<Long>) : FeedSpec { override val title = "Results" }

    fun flow(repo: LibraryRepository, showHidden: Boolean): Flow<List<MediaEntity>> = when (this) {
        is Random -> repo.videos(showHidden)
        is Saved -> app.scrollback.core.FolderFilter.decode(folder).let { f ->
            if (f == null) repo.videosByDate(newestFirst, showHidden)
            else repo.videosByDate(newestFirst, showHidden).map { l -> l.filter { f.matches(it.sourceId, it.relativePath) } }
        }
        is Pictures -> repo.images(newestFirst, showHidden)
        Liked -> repo.liked(showHidden)
        History -> repo.history(showHidden)
        Hidden -> repo.hidden()
        is Playlist -> repo.playlistItems(id, showHidden)
        is Search -> repo.search(query, tagIds, showHidden)
    }

    /**
     * v0.15: where the Feed tab remembers its place. Random has one slot (the seed is stored with
     * the position); Saved has one per order and folder. Other feeds open at the item you tapped.
     */
    val positionKey: String? get() = when (this) {
        is Random -> "random"
        is Saved -> "saved_${if (newestFirst) "new" else "old"}_$folder"
        else -> null
    }

    /** Encodes this spec as a navigation route argument. */
    fun toRoute(startId: Long): String {
        val (kind, arg) = when (this) {
            is Random -> "random" to seed.toString()
            is Saved -> "saved" to "$newestFirst|$folder"
            is Pictures -> "pictures" to newestFirst.toString()
            Liked -> "liked" to ""
            History -> "history" to ""
            Hidden -> "hidden" to ""
            is Playlist -> "playlist" to "$id|$title"
            is Search -> "search" to "${tagIds.joinToString(",")}|$query"
        }
        return "feed/$kind?arg=${Uri.encode(arg)}&start=$startId"
    }

    companion object {
        fun fromRoute(kind: String, arg: String): FeedSpec = when (kind) {
            "random" -> Random(arg.toLongOrNull() ?: 0L)
            "saved" -> Saved(arg.substringBefore('|').toBooleanStrictOrNull() ?: true, arg.substringAfter('|', ""))
            "pictures" -> Pictures(arg.toBooleanStrictOrNull() ?: true)
            "history" -> History
            "hidden" -> Hidden
            "playlist" -> Playlist(arg.substringBefore('|').toLongOrNull() ?: 0L, arg.substringAfter('|', "Playlist"))
            "search" -> Search(
                arg.substringAfter('|', ""),
                arg.substringBefore('|').split(',').mapNotNull { it.toLongOrNull() },
            )
            else -> Liked
        }
    }
}
