package app.scrollback.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.scrollback.data.db.MediaEntity
import app.scrollback.data.repo.LibraryRepository
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MusicState(
    val currentId: Long? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    /** Player.REPEAT_MODE_OFF / ONE / ALL */
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val queue: List<Long> = emptyList(),
    val index: Int = 0,
    val error: String? = null,
)

/** UI-side wrapper around a [MediaController] connected to [MusicService]. */
class MusicPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
) {
    private val _state = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = _state.asStateFlow()
    private var controller: MediaController? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.mediaId?.toLongOrNull()?.let { id -> scope.launch { library.markWatched(id) } }
        }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            _state.value = _state.value.copy(error = if (error.errorCode in 2000..2999) "Can't reach the server" else "Can't play this song")
        }
    }

    private suspend fun connect(): MediaController {
        controller?.let { return it }
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        val c = MediaController.Builder(context, token).buildAsync().await()
        c.addListener(listener)
        controller = c
        publish(c)
        return c
    }

    /** Re-attaches to a running service (e.g. after the activity was recreated). */
    fun attach() { scope.launch(Dispatchers.Main) { runCatching { connect() } } }

    private fun publish(p: Player) {
        val ids = (0 until p.mediaItemCount).mapNotNull { p.getMediaItemAt(it).mediaId.toLongOrNull() }
        _state.value = MusicState(
            currentId = p.currentMediaItem?.mediaId?.toLongOrNull(),
            playing = p.isPlaying,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            durationMs = p.duration.coerceAtLeast(0),
            shuffle = p.shuffleModeEnabled,
            repeat = p.repeatMode,
            queue = ids,
            index = p.currentMediaItemIndex,
            error = _state.value.error.takeIf { p.playerError != null },
        )
    }

    fun position(): Long = controller?.currentPosition ?: 0L

    private fun item(m: MediaEntity) = MediaItem.Builder().setMediaId(m.id.toString()).build()

    fun play(songs: List<MediaEntity>, start: Int = 0, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        scope.launch(Dispatchers.Main) {
            val c = connect()
            c.shuffleModeEnabled = shuffle
            val startIndex = if (shuffle && start == 0) songs.indices.random() else start.coerceIn(songs.indices)
            c.setMediaItems(songs.map(::item), startIndex, 0)
            c.prepare()
            c.play()
        }
    }

    fun playNext(song: MediaEntity) = withController { c ->
        if (c.mediaItemCount == 0) { c.setMediaItem(item(song)); c.prepare(); c.play() }
        else c.addMediaItem(c.currentMediaItemIndex + 1, item(song))
    }

    fun enqueue(song: MediaEntity) = withController { c ->
        if (c.mediaItemCount == 0) { c.setMediaItem(item(song)); c.prepare(); c.play() } else c.addMediaItem(item(song))
    }

    fun toggle() = withController { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }
    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun skipTo(index: Int) = withController { it.seekToDefaultPosition(index); it.play() }
    fun remove(index: Int) = withController { it.removeMediaItem(index) }
    fun move(from: Int, to: Int) = withController { if (to in 0 until it.mediaItemCount) it.moveMediaItem(from, to) }
    fun setShuffle(on: Boolean) = withController { it.shuffleModeEnabled = on }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
    }
    fun stop() = withController { it.stop(); it.clearMediaItems() }

    private fun withController(block: (MediaController) -> Unit) {
        scope.launch(Dispatchers.Main) { runCatching { block(connect()) } }
    }

    /** Service side: media id -> playable item with metadata and artwork. */
    suspend fun resolve(request: MediaItem): MediaItem? = withContext(Dispatchers.IO) {
        val m = request.mediaId.toLongOrNull()?.let { library.itemNow(it) } ?: return@withContext null
        val playUri = library.playback(m).uri
        val meta = MediaMetadata.Builder()
            .setTitle(m.title ?: m.name.substringBeforeLast('.'))
            .setArtist(m.artist)
            .setAlbumTitle(m.album)
            .setTrackNumber(m.trackNo.takeIf { it > 0 })
            .setIsPlayable(true)
            .setIsBrowsable(false)
        val thumb = m.thumbUri
        when {
            thumb == null -> Unit
            thumb.startsWith(AudioArt.SCHEME + ":") ->
                AudioArt.read(context, AudioArt.fileUriOf(thumb))?.let { meta.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
            else -> meta.setArtworkUri(Uri.parse(thumb))
        }
        MediaItem.Builder()
            .setMediaId(request.mediaId)
            .setUri(playUri)
            .setMimeType(m.mime)
            .setMediaMetadata(meta.build())
            .build()
    }
}
