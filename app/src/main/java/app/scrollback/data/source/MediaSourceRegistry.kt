// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.data.source

import android.content.Context
import android.net.Uri
import app.scrollback.accounts.AccountStore
import app.scrollback.data.db.SourceEntity
import app.scrollback.remote.webdav.WebDavClient

/** Builds the right [MediaSource] for a stored source row. */
class MediaSourceRegistry(
    private val context: Context,
    private val accounts: AccountStore,
    private val dav: WebDavClient,
    private val remoteFactories: Map<SourceType, (SourceEntity) -> MediaSource?> = emptyMap(),
) {
    /** Whether scans should index audio (phase 4 music). */
    @Volatile var includeAudio: Boolean = true

    fun forSource(entity: SourceEntity): MediaSource? = when (val t = SourceType.from(entity.type)) {
        SourceType.SAF_TREE -> SafMediaSource(entity.id, Uri.parse(entity.uri), context.contentResolver)
        SourceType.FILE -> RawMediaSource(entity.id, java.io.File(entity.uri), context)
        SourceType.WEBDAV -> accounts.get(entity.accountId)?.let { WebDavMediaSource(entity.id, it, entity.uri, dav, includeAudio) }
        else -> factories[t]?.invoke(entity)
    }

    /** Factories for server types registered later (Subsonic, Jellyfin). */
    val factories: MutableMap<SourceType, (SourceEntity) -> MediaSource?> = remoteFactories.toMutableMap()
}
