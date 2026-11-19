package app.scrollback.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.Buffer

/** Embedded cover art of local audio files, exposed to Coil as `audioart:<encoded file uri>`. */
object AudioArt {
    const val SCHEME = "audioart"

    fun uriFor(fileUri: String) = "$SCHEME:" + Uri.encode(fileUri)

    fun fileUriOf(artUri: String): String = Uri.decode(artUri.removePrefix("$SCHEME:"))

    fun read(context: Context, fileUri: String): ByteArray? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, Uri.parse(fileUri))
            r.embeddedPicture
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    class Factory : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.scheme == SCHEME) Fetcher { fetch(data.toString(), options) } else null

        private fun fetch(art: String, options: Options): FetchResult? {
            val bytes = read(options.context, fileUriOf(art)) ?: return null
            return SourceFetchResult(
                source = ImageSource(Buffer().write(bytes), options.fileSystem),
                mimeType = null,
                dataSource = DataSource.DISK,
            )
        }
    }
}
