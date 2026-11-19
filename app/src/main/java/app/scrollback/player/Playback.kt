package app.scrollback.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.scrollback.remote.RemoteAuth
import okhttp3.OkHttpClient
import java.io.File

/**
 * Builds players that can open local content:// files and stream remote media.
 * Remote reads go: cache (keyed by the stable DB URI) -> resolve custom schemes -> OkHttp (+auth).
 * Local reads skip the cache entirely.
 */
@OptIn(UnstableApi::class)
class Playback(private val context: Context, http: OkHttpClient, private val auth: RemoteAuth) {
    val cache: SimpleCache by lazy {
        SimpleCache(File(context.cacheDir, "media"), LeastRecentlyUsedCacheEvictor(512L * 1024 * 1024), StandaloneDatabaseProvider(context))
    }

    private val remoteUpstream: DataSource.Factory = ResolvingDataSource.Factory(OkHttpDataSource.Factory(http)) { spec ->
        spec.withUri(Uri.parse(auth.resolve(spec.uri.toString())))
    }

    private val remote: DataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(remoteUpstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private val local: DataSource.Factory = DefaultDataSource.Factory(context)

    val dataSourceFactory: DataSource.Factory = DataSource.Factory { Routing(local.createDataSource(), remote.createDataSource(), auth) }

    /** Feed player. Takes audio focus, so background music pauses while a video plays. */
    fun newPlayer(): ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        .build()

    /** Picks the local or remote chain per opened URI. */
    private class Routing(private val local: DataSource, private val remote: DataSource, private val auth: RemoteAuth) : DataSource {
        private var current: DataSource? = null
        override fun addTransferListener(transferListener: TransferListener) {
            local.addTransferListener(transferListener); remote.addTransferListener(transferListener)
        }
        override fun open(dataSpec: DataSpec): Long {
            val ds = if (auth.isRemote(dataSpec.uri.toString())) remote else local
            current = ds
            return ds.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current!!.read(buffer, offset, length)
        override fun getUri(): Uri? = current?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()
        override fun close() { current?.close(); current = null }
    }
}
