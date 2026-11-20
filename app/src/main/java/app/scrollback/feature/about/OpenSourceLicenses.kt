// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.feature.about

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.scrollback.ui.components.GlassScreen
import app.scrollback.ui.components.LargeTitle
import app.scrollback.ui.components.RowDivider
import app.scrollback.ui.components.SectionCard
import app.scrollback.ui.components.SettingsRow
import app.scrollback.ui.components.collapsedState
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbSpace

/** One bundled third-party component. Keep in sync with THIRD_PARTY_NOTICES.md. */
data class Notice(val name: String, val license: String, val url: String, val note: String? = null)

object OpenSourceLicenses {
    val notices = listOf(
        Notice("libVLC for Android (org.videolan.android:libvlc-all)", "LGPL-2.1-or-later", "https://code.videolan.org/videolan/vlc-android",
            "Dynamically linked shared libraries (.so), replaceable. Source: https://code.videolan.org/videolan/vlc and https://www.videolan.org/vlc/download-sources.html"),
        Notice("AndroidX and Jetpack Compose (core, activity, lifecycle, navigation, room, datastore, media, compose UI/foundation/material3, icons)", "Apache-2.0", "https://developer.android.com/jetpack/androidx"),
        Notice("AndroidX Media3 (ExoPlayer, HLS, session, OkHttp data source)", "Apache-2.0", "https://github.com/androidx/media"),
        Notice("Kotlin standard library and kotlinx.coroutines", "Apache-2.0", "https://github.com/JetBrains/kotlin"),
        Notice("OkHttp and Okio", "Apache-2.0", "https://square.github.io/okhttp/"),
        Notice("Coil (images, video frames)", "Apache-2.0", "https://github.com/coil-kt/coil"),
        Notice("Haze (glass blur)", "Apache-2.0", "https://github.com/chrisbanes/haze"),
        Notice("Accompanist drawable painter", "Apache-2.0", "https://github.com/google/accompanist"),
        Notice("sshj (SFTP)", "Apache-2.0", "https://github.com/hierynomus/sshj"),
        Notice("smbj and asn-one (SMB2/3)", "Apache-2.0", "https://github.com/hierynomus/smbj"),
        Notice("Bouncy Castle (bcprov, bcpkix, bcutil)", "MIT (Bouncy Castle Licence)", "https://www.bouncycastle.org/licence.html"),
        Notice("Apache Commons Compress, Lang, IO and Codec", "Apache-2.0", "https://commons.apache.org/"),
        Notice("XZ for Java", "0BSD", "https://tukaani.org/xz/java.html"),
        Notice("NanoHTTPD", "BSD-3-Clause", "https://github.com/NanoHttpd/nanohttpd"),
        Notice("MBassador (event bus used by smbj)", "MIT", "https://github.com/bennidi/mbassador"),
        Notice("SLF4J API", "MIT", "https://www.slf4j.org/"),
        Notice("Guava, failureaccess, listenablefuture", "Apache-2.0", "https://github.com/google/guava"),
        Notice("Annotations: JSR-305, Error Prone, J2ObjC, JSpecify, JetBrains", "Apache-2.0", "https://github.com/google/error-prone"),
        Notice("Checker Framework qualifiers", "MIT", "https://checkerframework.org/"),
    )
}

/** Settings → About → Open-source licenses. */
@Composable
fun OpenSourceLicensesScreen(onBack: () -> Unit) {
    val list = rememberLazyListState()
    val collapsed by list.collapsedState()
    val uri = LocalUriHandler.current
    val ctx = LocalContext.current
    var showGpl by remember { mutableStateOf(false) }
    val gpl = remember(showGpl) { if (showGpl) runCatching { ctx.assets.open("licenses/GPL-3.0.txt").bufferedReader().readText() }.getOrDefault("") else "" }
    GlassScreen(title = "Open-source licenses", collapsed = collapsed, onBack = onBack) { mod ->
        LazyColumn(mod, state = list, contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 48.dp)) {
            item {
                LargeTitle(
                    "Licenses",
                    "Scrollback is free software under the GNU General Public License v3 or later. Copyright (C) 2026 Pourya FA. " +
                        "It is original code written from scratch for this project. Nearby share is a fresh implementation of the open LocalSend v2 protocol " +
                        "(inspired by Pourya's RSend app; no LocalSend or RSend code is copied). The libraries below keep their own licenses.",
                )
            }
            item {
                SectionCard(title = "Scrollback") {
                    SettingsRow("GNU GPL v3.0 or later", subtitle = if (showGpl) "Tap to hide the full text" else "Tap to read the full license text", icon = Icons.Outlined.Gavel, onClick = { showGpl = !showGpl })
                    RowDivider()
                    SettingsRow("Source code", subtitle = "github.com/PouryaFA81/Scrollback", icon = Icons.Outlined.Description, onClick = { runCatching { uri.openUri("https://github.com/PouryaFA81/Scrollback") } })
                }
            }
            if (showGpl) item {
                Text(gpl, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = SbColors.TextSecondary, modifier = Modifier.padding(horizontal = SbSpace.xl, vertical = SbSpace.m))
            }
            item { Text("THIRD-PARTY", style = MaterialTheme.typography.labelSmall, color = SbColors.TextTertiary, modifier = Modifier.padding(start = SbSpace.xl, top = SbSpace.l, bottom = SbSpace.s)) }
            items(OpenSourceLicenses.notices) { n ->
                SectionCard {
                    SettingsRow(n.name, subtitle = listOfNotNull(n.license, n.note).joinToString(" · "), onClick = { runCatching { uri.openUri(n.url) } })
                }
            }
        }
    }
}
