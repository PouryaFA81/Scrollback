// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.feature.feed

import android.net.Uri
import app.scrollback.data.db.MediaEntity
import app.scrollback.data.repo.LibraryRepository
import kotlinx.coroutines.flow.Flow

/** What a feed shows. Every feed (tabs, playlists, search results, pictures) is the same pager. */
sealed interface FeedSpec {
    val title: String

    data class Random(val seed: Long) : FeedSpec { override val title = "Random" }
    data class Saved(val newestFirst: Boolean) : FeedSpec { override val title = "Saved order" }
    data class Pictures(val newestFirst: Boolean) : FeedSpec { override val title = "Pictures" }
    data object Liked : FeedSpec { override val title = "Liked" }
    data object History : FeedSpec { override val title = "History" }
    data object Hidden : FeedSpec { override val title = "Hidden" }
    data class Playlist(val id: Long, override val title: String = "Playlist") : FeedSpec
    data class Search(val query: String, val tagIds: List<Long>) : FeedSpec { override val title = "Results" }

    fun flow(repo: LibraryRepository, showHidden: Boolean): Flow<List<MediaEntity>> = when (this) {
        is Random -> repo.videos(showHidden)
        is Saved -> repo.videosByDate(newestFirst, showHidden)
        is Pictures -> repo.images(newestFirst, showHidden)
        Liked -> repo.liked(showHidden)
        History -> repo.history(showHidden)
        Hidden -> repo.hidden()
        is Playlist -> repo.playlistItems(id, showHidden)
        is Search -> repo.search(query, tagIds, showHidden)
    }

    /** Encodes this spec as a navigation route argument. */
    fun toRoute(startId: Long): String {
        val (kind, arg) = when (this) {
            is Random -> "random" to seed.toString()
            is Saved -> "saved" to newestFirst.toString()
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
            "saved" -> Saved(arg.toBooleanStrictOrNull() ?: true)
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
