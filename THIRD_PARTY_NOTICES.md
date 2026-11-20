# Third-party notices

Scrollback is licensed under the GNU General Public License v3.0 or later (see [LICENSE](LICENSE)).
Copyright (C) 2026 Pourya FA. Scrollback's own code is original and was written for this project.

The release APKs bundle the libraries below. Each one keeps its own license. All of them are
compatible with distribution under GPLv3. This list was audited for v0.14.0 against
`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`. The app shows the same list
under **Settings → About → Open-source licenses**.

| Component | License | Link |
|---|---|---|
| **libVLC for Android** (`org.videolan.android:libvlc-all` 3.7.7) | LGPL-2.1-or-later | https://code.videolan.org/videolan/vlc-android |
| AndroidX / Jetpack (core, activity, lifecycle, navigation, Room, DataStore, SQLite, media, emoji2, startup, profileinstaller…) | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| Jetpack Compose (runtime, UI, foundation, animation, Material 3, material-icons-extended) | Apache-2.0 | https://developer.android.com/jetpack/compose |
| AndroidX Media3 (ExoPlayer, HLS, session, OkHttp data source) 1.11.1 | Apache-2.0 | https://github.com/androidx/media |
| Kotlin standard library, kotlinx.coroutines (incl. Guava interop) | Apache-2.0 | https://github.com/JetBrains/kotlin |
| OkHttp 5 and Okio | Apache-2.0 | https://square.github.io/okhttp/ |
| Coil 3 (compose, network-okhttp, video) | Apache-2.0 | https://github.com/coil-kt/coil |
| Haze (glass blur) | Apache-2.0 | https://github.com/chrisbanes/haze |
| Accompanist drawable painter | Apache-2.0 | https://github.com/google/accompanist |
| sshj (SFTP) | Apache-2.0 | https://github.com/hierynomus/sshj |
| smbj, asn-one (SMB2/3) | Apache-2.0 | https://github.com/hierynomus/smbj |
| Bouncy Castle (bcprov, bcpkix, bcutil) | MIT (Bouncy Castle Licence) | https://www.bouncycastle.org/licence.html |
| Apache Commons Compress, Lang 3, IO, Codec | Apache-2.0 | https://commons.apache.org/ |
| XZ for Java | 0BSD | https://tukaani.org/xz/java.html |
| NanoHTTPD | BSD-3-Clause | https://github.com/NanoHttpd/nanohttpd |
| MBassador (used by smbj) | MIT | https://github.com/bennidi/mbassador |
| SLF4J API | MIT | https://www.slf4j.org/ |
| Guava, failureaccess, listenablefuture | Apache-2.0 | https://github.com/google/guava |
| JSR-305, Error Prone annotations, J2ObjC annotations, JSpecify, JetBrains annotations | Apache-2.0 | https://github.com/google/error-prone |
| Checker Framework qualifiers | MIT | https://checkerframework.org/ |

## libVLC (LGPL-2.1-or-later)

Scrollback Player uses libVLC from the VideoLAN project, unmodified, as published on Maven Central.
Its native code ships as separate shared libraries (`libvlc.so`, `libc++_shared.so` and others in `lib/<abi>/`).
Scrollback links to them dynamically, so they can be replaced. Source code:

- libVLC for Android: https://code.videolan.org/videolan/vlc-android (tag 3.7.7 of `libvlc-all`)
- VLC: https://code.videolan.org/videolan/vlc and https://www.videolan.org/vlc/download-sources.html

Some modules bundled in libVLC builds are GPL-2.0-or-later. That is also compatible, because Scrollback is
distributed under GPL-3.0-or-later.

## Not bundled, only referenced

- **LocalSend** (Apache-2.0): Nearby share speaks the published LocalSend v2 protocol. No LocalSend source code is included.
- **RSend** (Pourya FA): Nearby share's Kotlin protocol code was ported from RSend's `lsend` package by the same author.
- **VERT** (vert.sh): opened in the browser or called over its HTTP API. No VERT code is included.
- **SnapReel** (MIT): visual design reference only. No code is included.

There are no bundled fonts or third-party image assets. Icons come from Material Symbols (Apache-2.0) through
`material-icons-extended`.
