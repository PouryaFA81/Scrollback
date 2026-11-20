// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.launch

class ScrollbackApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // sshj needs the full BouncyCastle provider; Android ships a stripped one under the same name.
        // Appended last so platform providers (AndroidKeyStore, Conscrypt) keep priority.
        runCatching {
            java.security.Security.removeProvider("BC")
            java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        }
        container = AppContainer(this)
        container.appScope.launch { container.library.rescanAll(); container.syncSoon(force = true) }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
                add(app.scrollback.player.AudioArt.Factory())
                add(app.scrollback.remote.fs.RemoteFsFetcher(container.remoteStorage))
                add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = { container.http }))
            }
            .crossfade(true)
            .build()
}
