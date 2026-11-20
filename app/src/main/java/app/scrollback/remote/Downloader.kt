// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.remote

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import app.scrollback.data.db.MediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Saves a remote item to Download/Scrollback via MediaStore (no storage permission needed). */
class Downloader(private val context: Context, private val http: OkHttpClient, private val auth: RemoteAuth) {
    suspend fun download(item: MediaEntity, onProgress: (Float) -> Unit = {}): String =
        download(item.uri, item.name, item.mime, item.size, onProgress)

    suspend fun download(uri: String, name: String, mime: String, size: Long, onProgress: (Float) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Scrollback")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not create the file")
        try {
            http.newCall(Request.Builder().url(auth.resolve(uri)).build()).execute().use { r ->
                if (!r.isSuccessful) throw HttpException(r.code, "Download failed")
                val total = r.body.contentLength().takeIf { it > 0 } ?: size
                resolver.openOutputStream(target)!!.use { out ->
                    val input = r.body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            target.toString()
        } catch (e: Exception) {
            resolver.delete(target, null, null)
            throw e
        }
    }
}
