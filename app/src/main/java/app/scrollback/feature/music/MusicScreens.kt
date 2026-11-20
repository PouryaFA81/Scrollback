// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.feature.music

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scrollback.data.db.MediaEntity
import app.scrollback.feature.feed.PlaylistSheet
import app.scrollback.feature.library.ListRow
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.swipeTabs
import app.scrollback.ui.components.EmptyState
import app.scrollback.ui.components.FloatingNavReserve
import app.scrollback.ui.components.Fmt
import app.scrollback.ui.components.GlassPill
import app.scrollback.ui.components.GlassScreen
import app.scrollback.ui.components.GlassSheet
import app.scrollback.ui.components.LargeTitle
import app.scrollback.ui.components.MediaThumb
import app.scrollback.ui.components.SettingsRow
import app.scrollback.ui.components.SheetTitle
import app.scrollback.ui.components.collapsedState
import app.scrollback.ui.components.rememberHaptics
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbSpace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** What a song list shows. Encoded in the `music/{kind}?arg=` route. */
sealed class MusicList(val kind: String, val arg: String, val title: String) {
    class Artist(name: String) : MusicList("artist", name, name)
    class Album(name: String) : MusicList("album", name, name)
    class Folder(path: String) : MusicList("folder", path, path.substringAfterLast('/').ifEmpty { "Music" })
    object Liked : MusicList("liked", "", "Liked songs")
    object Recent : MusicList("recent", "", "Recently played")

    fun route() = "music/$kind?arg=${android.net.Uri.encode(arg)}"

    fun flow(repo: app.scrollback.data.repo.LibraryRepository, showHidden: Boolean): Flow<List<MediaEntity>> = when (this) {
        is Artist -> repo.artistSongs(arg, showHidden)
        is Album -> repo.albumSongs(arg, showHidden)
        is Folder -> repo.folderSongs(arg, showHidden)
        Liked -> repo.likedSongs(showHidden)
        Recent -> repo.recentSongs(showHidden)
    }

    companion object {
        fun from(kind: String, arg: String): MusicList = when (kind) {
            "artist" -> Artist(arg)
            "album" -> Album(arg)
            "folder" -> Folder(arg)
            "recent" -> Recent
            else -> Liked
        }
    }
}

private enum class MusicTab(val label: String) { SONGS("Songs"), ARTISTS("Artists"), ALBUMS("Albums"), FOLDERS("Folders"), PLAYLISTS("Playlists") }

val MediaEntity.songTitle: String get() = title?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.')
val MediaEntity.songSubtitle: String get() = listOfNotNull(artist?.takeIf { it.isNotBlank() }, album?.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { relativePath.ifEmpty { "Unknown artist" } }

/** The Music tab: songs, artists, albums, folders and playlists from every source. */
@Composable
fun MusicScreen(onOpenList: (MusicList) -> Unit, onOpenPlaylist: (Long) -> Unit, onOpenPlayer: () -> Unit, onOpenServers: () -> Unit) {
    val container = LocalContainer.current
    val repo = container.library
    val ctx = LocalContext.current
    val session by container.session.collectAsStateWithLifecycle()
    val hidden = session.hiddenRevealed
    var tab by rememberSaveable { mutableStateOf(MusicTab.SONGS) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val songs by remember(hidden, query) { if (query.isBlank()) repo.songs(hidden) else repo.searchSongs(query, hidden) }.collectAsStateWithLifecycle(null)
    val artists by remember(hidden) { repo.artists(hidden) }.collectAsStateWithLifecycle(emptyList())
    val albums by remember(hidden) { repo.albums(hidden) }.collectAsStateWithLifecycle(emptyList())
    val folders by remember(hidden) { repo.musicFolders(hidden) }.collectAsStateWithLifecycle(emptyList())
    val playlists by remember(hidden) { repo.playlists(hidden) }.collectAsStateWithLifecycle(emptyList())
    val list = rememberLazyListState()
    val collapsed by list.collapsedState()
    var actions by remember { mutableStateOf<MediaEntity?>(null) }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun play(items: List<MediaEntity>, index: Int, shuffle: Boolean = false) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        container.music.play(items, index, shuffle)
    }

    GlassScreen(
        title = "Music",
        collapsed = collapsed,
        actions = {
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Outlined.Search, "Search songs", tint = SbColors.TextPrimary) }
        },
    ) { mod ->
        LazyColumn(
            modifier = mod.swipeTabs(MusicTab.entries.size, tab.ordinal) { tab = MusicTab.entries[it] },
            state = list,
            contentPadding = PaddingValues(
                start = SbSpace.m, end = SbSpace.m,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + FloatingNavReserve + 80.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item { LargeTitle("Music", horizontalPadding = SbSpace.s) }
            if (searching) {
                item {
                    OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Songs, artists, albums") },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = SbSpace.s, vertical = SbSpace.s))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = SbSpace.s, vertical = SbSpace.s), horizontalArrangement = Arrangement.spacedBy(SbSpace.s)) {
                    MusicTab.entries.forEach { t -> GlassPill(t.label, selected = t == tab, onClick = { tab = t }) }
                }
            }
            val s = songs
            if (s != null && s.isEmpty() && query.isBlank()) {
                item {
                    EmptyState(Icons.Outlined.LibraryMusic, "No music yet",
                        "Songs in your folders are picked up on the next scan. You can also connect Navidrome, Nextcloud Music, Jellyfin or another Subsonic server.") {
                        GlassPill("Servers", selected = true, onClick = onOpenServers)
                    }
                }
            } else when (tab) {
                MusicTab.SONGS -> {
                    if (s != null && s.isNotEmpty()) item {
                        Row(Modifier.padding(horizontal = SbSpace.s, vertical = SbSpace.s), horizontalArrangement = Arrangement.spacedBy(SbSpace.s)) {
                            GlassPill("Play", selected = true, icon = Icons.Outlined.PlayArrow, onClick = { play(s, 0) })
                            GlassPill("Shuffle", selected = false, icon = Icons.Outlined.Shuffle, onClick = { play(s, 0, shuffle = true) })
                        }
                    }
                    songRows(s.orEmpty(), onPlay = { i -> play(s.orEmpty(), i) }, onMore = { actions = it })
                }
                MusicTab.ARTISTS -> items(artists, key = { "ar" + it.artist }) { a ->
                    ListRow(a.artist, subtitle = "${a.albums} album${if (a.albums == 1) "" else "s"} · ${a.songs} songs",
                        leading = { Cover(a.cover, Icons.Outlined.Person, round = true) }, onClick = { onOpenList(MusicList.Artist(a.artist)) })
                }
                MusicTab.ALBUMS -> items(albums, key = { "al" + it.album }) { a ->
                    ListRow(a.album, subtitle = listOfNotNull(a.artist, "${a.songs} songs").joinToString(" · "),
                        leading = { Cover(a.cover, Icons.Outlined.Album) }, onClick = { onOpenList(MusicList.Album(a.album)) })
                }
                MusicTab.FOLDERS -> items(folders, key = { "fo" + it.folder }) { f ->
                    ListRow(f.folder.substringAfterLast('/').ifEmpty { f.folder }, subtitle = "${f.folder} · ${f.songs} songs",
                        leading = { Cover(null, Icons.Outlined.Folder) }, onClick = { onOpenList(MusicList.Folder(f.folder)) })
                }
                MusicTab.PLAYLISTS -> {
                    item { ListRow("Liked songs", leading = { Cover(null, Icons.Outlined.Favorite) }, onClick = { onOpenList(MusicList.Liked) }) }
                    item { ListRow("Recently played", leading = { Cover(null, Icons.Outlined.History) }, onClick = { onOpenList(MusicList.Recent) }) }
                    items(playlists, key = { "pl" + it.id }) { p ->
                        ListRow(p.name, subtitle = "${p.count} items", leading = { Cover(null, Icons.AutoMirrored.Outlined.QueueMusic) }, onClick = { onOpenPlaylist(p.id) })
                    }
                }
            }
        }
    }
    actions?.let { SongActionsSheet(it, onDismiss = { actions = null }) }
}

fun LazyListScope.songRows(songs: List<MediaEntity>, onPlay: (Int) -> Unit, onMore: (MediaEntity) -> Unit) {
    itemsIndexed(songs, key = { _, m -> "s" + m.id }) { i, m -> SongRow(m, onClick = { onPlay(i) }, onMore = { onMore(m) }) }
}

@Composable
fun SongRow(m: MediaEntity, onClick: () -> Unit, onMore: () -> Unit) {
    val current by LocalContainer.current.music.state.collectAsStateWithLifecycle()
    val groups by LocalContainer.current.library.musicGroups.collectAsStateWithLifecycle()
    val copies = groups.copiesOf(m.id)
    var playFrom by remember { mutableStateOf(false) }
    if (playFrom) PlayFromSheet(m, onDismiss = { playFrom = false })
    val playing = current.currentId == m.id || copies.any { it.id == current.currentId }
    ListRow(
        title = m.songTitle,
        subtitle = buildString {
            append(m.songSubtitle)
            if (m.durationMs > 0) append(" · ").append(Fmt.duration(m.durationMs))
        },
        leading = {
            Box(contentAlignment = Alignment.Center) {
                MediaThumb(m, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
                if (playing) Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(SbColors.Background.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.PlayArrow, null, tint = SbColors.TextPrimary)
                }
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (copies.size > 1) {
                    Text(
                        "${copies.size} sources", style = MaterialTheme.typography.labelSmall, color = SbColors.TextTertiary,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(SbColors.SurfaceHigh).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (m.liked) Icon(Icons.Outlined.Favorite, "Liked", tint = SbColors.Like, modifier = Modifier.size(16.dp))
                IconButton(onClick = onMore) { Icon(Icons.Outlined.MoreVert, "More", tint = SbColors.TextSecondary) }
            }
        },
        onClick = onClick,
        onLongClick = if (copies.size > 1) ({ playFrom = true }) else null,
    )
}

/** "Play from…": pick which copy of a merged song to play (v0.14 hide duplicates). */
@Composable
fun PlayFromSheet(m: MediaEntity, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val groups by container.library.musicGroups.collectAsStateWithLifecycle()
    val names by remember { container.library.sourceNames() }.collectAsStateWithLifecycle(emptyMap())
    val copies = groups.copiesOf(m.id)
    GlassSheet(onDismiss) { close ->
        SheetTitle("Play from…")
        Text(m.songTitle, color = SbColors.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = SbSpace.m))
        copies.forEachIndexed { i, c ->
            SettingsRow(
                names[c.sourceId]?.removePrefix("♫ ") ?: "Source ${c.sourceId}",
                subtitle = listOfNotNull(
                    c.name.substringAfterLast('.', "").uppercase().takeIf { it.isNotEmpty() && it.length <= 5 },
                    c.durationMs.takeIf { it > 0 }?.let { Fmt.duration(it) },
                    if (i == 0) "preferred" else null,
                    if (c.liked) "liked" else null,
                ).joinToString(" · "),
                icon = Icons.Outlined.PlayArrow,
                onClick = { container.music.play(listOf(c), 0); close() },
            )
        }
    }
}

@Composable
fun Cover(model: String?, fallback: androidx.compose.ui.graphics.vector.ImageVector, round: Boolean = false, size: androidx.compose.ui.unit.Dp = 48.dp) {
    val auth = LocalContainer.current.auth
    val shape = if (round) RoundedCornerShape(50) else RoundedCornerShape(10.dp)
    Box(Modifier.size(size).clip(shape).background(app.scrollback.ui.theme.SbColors.Surface), contentAlignment = Alignment.Center) {
        Icon(fallback, null, tint = SbColors.TextTertiary)
        if (model != null) {
            val ctx = LocalContext.current
            val req = remember(model) { coil3.request.ImageRequest.Builder(ctx).data(auth.resolve(model)).memoryCacheKey(model).diskCacheKey(model).build() }
            coil3.compose.AsyncImage(req, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(size))
        }
    }
}

/** Like / play next / queue / add to playlist for one song. */
@Composable
fun SongActionsSheet(m: MediaEntity, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val haptics = rememberHaptics()
    var playlist by remember { mutableStateOf(false) }
    var playFrom by remember { mutableStateOf(false) }
    if (playlist) { PlaylistSheet(m, onDismiss = { playlist = false; onDismiss() }); return }
    if (playFrom) { PlayFromSheet(m, onDismiss = { playFrom = false; onDismiss() }); return }
    GlassSheet(onDismiss) { close ->
        SheetTitle(m.songTitle)
        Text(m.songSubtitle, color = SbColors.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = SbSpace.m))
        SettingsRow(if (m.liked) "Unlike" else "Like", icon = if (m.liked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, onClick = {
            haptics.confirm(); container.appScope.launch { container.library.setLiked(m.id, !m.liked) }; close()
        })
        SettingsRow("Play next", icon = Icons.Outlined.SkipNext, onClick = { container.music.playNext(m); close() })
        val groups by container.library.musicGroups.collectAsStateWithLifecycle()
        val copies = groups.copiesOf(m.id)
        if (copies.size > 1) SettingsRow("Play from…", subtitle = "${copies.size} sources have this song", icon = Icons.Outlined.PlayArrow, onClick = { playFrom = true })
        SettingsRow("Add to queue", icon = Icons.AutoMirrored.Outlined.QueueMusic, onClick = { container.music.enqueue(m); close() })
        SettingsRow("Add to playlist", icon = Icons.AutoMirrored.Outlined.PlaylistAdd, onClick = { playlist = true })
    }
}

/** Songs of an artist / album / folder / liked / recent. */
@Composable
fun MusicListScreen(spec: MusicList, onBack: () -> Unit, onOpenPlayer: () -> Unit) {
    val container = LocalContainer.current
    val session by container.session.collectAsStateWithLifecycle()
    val songs by remember(spec.kind, spec.arg, session.hiddenRevealed) { spec.flow(container.library, session.hiddenRevealed) }.collectAsStateWithLifecycle(emptyList())
    val list = rememberLazyListState()
    val collapsed by list.collapsedState()
    var actions by remember { mutableStateOf<MediaEntity?>(null) }
    GlassScreen(title = spec.title, collapsed = collapsed, onBack = onBack, overlay = {
        MiniPlayer(onOpen = onOpenPlayer, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = SbSpace.l), showWhenEmpty = false)
    }) { mod ->
        LazyColumn(
            modifier = mod, state = list,
            contentPadding = PaddingValues(start = SbSpace.m, end = SbSpace.m, bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 120.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                Column {
                    LargeTitle(spec.title, "${songs.size} songs · ${Fmt.duration(songs.sumOf { it.durationMs.coerceAtLeast(0) })}", horizontalPadding = SbSpace.s)
                    if (songs.isNotEmpty()) Row(Modifier.padding(horizontal = SbSpace.s, vertical = SbSpace.s), horizontalArrangement = Arrangement.spacedBy(SbSpace.s)) {
                        GlassPill("Play", selected = true, icon = Icons.Outlined.PlayArrow, onClick = { container.music.play(songs, 0) })
                        GlassPill("Shuffle", selected = false, icon = Icons.Outlined.Shuffle, onClick = { container.music.play(songs, 0, shuffle = true) })
                    }
                }
            }
            if (songs.isEmpty()) item { EmptyState(Icons.Outlined.LibraryMusic, "Nothing here", "No songs match.") }
            songRows(songs, onPlay = { container.music.play(songs, it) }, onMore = { actions = it })
        }
    }
    actions?.let { SongActionsSheet(it, onDismiss = { actions = null }) }
}

@Composable
internal fun Spacer8() = Spacer(Modifier.width(SbSpace.s))
