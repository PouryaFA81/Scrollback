package app.scrollback.data.source

import android.content.Context
import android.net.Uri
import app.scrollback.data.db.SourceEntity

/** Builds the right [MediaSource] for a stored source row. */
class MediaSourceRegistry(private val context: Context) {
    fun forSource(entity: SourceEntity): MediaSource = when (SourceType.from(entity.type)) {
        SourceType.SAF_TREE -> SafMediaSource(entity.id, Uri.parse(entity.uri), context.contentResolver)
        SourceType.FILE -> RawMediaSource(entity.id, java.io.File(entity.uri), context)
        else -> throw UnsupportedOperationException("${entity.type} sources are not available yet")
    }
}
