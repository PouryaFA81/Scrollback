package app.scrollback.data.source

import android.content.Context
import android.net.Uri
import app.scrollback.data.db.SourceEntity

/** Builds the right [MediaSource] for a stored source row. */
class MediaSourceRegistry(private val context: Context) {
    fun forSource(entity: SourceEntity): MediaSource = when (SourceType.from(entity.type)) {
        SourceType.SAF_TREE -> SafMediaSource(entity.id, Uri.parse(entity.uri), context.contentResolver)
        // Phase 3: WebDavMediaSource(entity, credentials) — PROPFIND scan + authenticated HTTP streaming.
        SourceType.WEBDAV -> throw UnsupportedOperationException("WebDAV sources arrive in phase 3")
    }
}
