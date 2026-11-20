# Scrollback developer notes

This page collects the technical material that used to live in the README: the stack, the
architecture, how the remote features work under the hood, and the design system. For the
user-facing docs, see the [README](../README.md) and the [user guide](guide/README.md).

- [Build and test](#build-and-test)
- [Stack](#stack)
- [Architecture](#architecture)
- [Media index and sources](#media-index-and-sources)
- [File manager internals](#file-manager-internals)
- [Nextcloud internals](#nextcloud-internals)
- [Music internals](#music-internals)
- [Design system](#design-system)
- [Store notes: `MANAGE_EXTERNAL_STORAGE`](#store-notes-manage_external_storage)
- [Development history](#development-history)

## Build and test

```bash
./scripts/build-apk.sh              # debug + signed release splits → dist/ and dist/archive/ (timestamped)
./scripts/build-apk.sh --install    # same, then adb install (debug + arm64-v8a release)
./scripts/build-apk.sh --no-release # debug only (fast)
./scripts/publish-release.sh v0.6.0 # GitHub Release for a pushed tag, with all APKs + SHA256SUMS
./gradlew testDebugUnitTest lint    # JVM unit tests (app/src/test) and Android lint
```

- `dist/` is git-ignored. `build-apk.sh` never overwrites older archives; it reads the version from
  `versionName` in `app/build.gradle.kts`.
- Application id: `app.scrollback` (debug builds use the suffix `.debug`, so `app.scrollback.debug`,
  and the version name gets `-debug`).
- `minSdk` 33 (Android 13), `targetSdk` 37, compile SDK 37.
- **ABI splits:** every build produces `arm64-v8a`, `armeabi-v7a` and `universal` APKs. For debug,
  only the universal APK is kept in `dist/`.
- **Release signing:** release APKs are signed from `keystore/` (git-ignored: `release.jks` +
  `keystore.properties`; never committed or printed). Without it, `assembleRelease` still builds,
  just unsigned, and `build-apk.sh` skips the release build. Debug APKs use the build machine's
  debug key. **Back up `keystore/`**: if it is lost, future release updates can't install over
  existing release installs.
- The release build uses R8 minification plus resource shrinking. The debug APK is much larger
  because nothing is stripped (for example the whole material-icons-extended set).
- **Each GitHub Release** attaches `scrollback-<ver>-arm64-v8a-release.apk`,
  `-armeabi-v7a-release.apk`, `-universal-release.apk`, `-universal-debug.apk` and `SHA256SUMS`.

### In-app updates (v0.6)

- `update/Updater.kt` + `feature/settings/UpdatesSection.kt`. It runs only when the user taps
  **Check for update**: no background polling, no scheduled job.
- Reads `https://api.github.com/repos/PouryaFA81/Scrollback/releases/latest` and picks the asset
  with `GitHubReleases.pickApk`: for release builds the first `-<abi>-release.apk` in
  `Build.SUPPORTED_ABIS` order, then `-universal-release.apk`; for debug builds
  `-universal-debug.apk`. Version comparison is in `AppVersion` (unit-tested in `UpdateLogicTest`).
- Before installing, it checks that the package name and signing certificates of the downloaded APK
  match the installed app, then installs through `PackageInstaller` (`REQUEST_INSTALL_PACKAGES`;
  Android asks the user to confirm).
- While the repo is private, the anonymous API returns 404. An optional fine-grained token
  (read-only Contents) is stored AES-GCM encrypted with an Android Keystore key in
  `CredentialStore` and only sent to api.github.com.

## Stack

Kotlin 2.4 · Jetpack Compose + Material 3 (dark only) · AndroidX Media3 ExoPlayer + MediaSession ·
Room · DataStore · Coil 3 (+ video frames, OkHttp network) · OkHttp 5 · Haze (backdrop blur) ·
Navigation Compose · manual DI (`AppContainer`).
AGP 9.5 / Gradle 9.9 (wrapper included), JDK 21, compile/target SDK 37. Java source/target
compatibility is 17. Dependency versions live in `gradle/libs.versions.toml`.

There are no analytics, crash-reporting or advertising SDKs in the dependency list.

## Architecture

```
app.scrollback
├── core/            pure Kotlin: duplicate hashing, PIN hashing, tags, feed ordering (unit-tested)
├── data/
│   ├── db/          Room entities + DAOs (generic media kinds: video, image, audio)
│   ├── source/      MediaSource abstraction — Saf, Raw (java.io), WebDav (Nextcloud)
│   ├── repo/        LibraryRepository, DuplicateRepository
│   └── prefs/       DataStore settings
├── feature/         feed, pictures, files, library, music, servers, settings (folders, duplicates), lock
├── files/           file manager backends (Raw java.io / SAF / MediaStore), sort, names, clipboard ops
├── accounts/        server accounts + Keystore-encrypted credentials, ServerManager, music servers
├── remote/          OkHttp + RemoteAuth, WebDAV, Nextcloud Login Flow v2, Subsonic + Jellyfin clients, downloads
├── player/          Media3 factory (local direct, remote via cache -> resolver -> OkHttp), MusicService, MusicPlayer, AudioArt
├── sync/            Nextcloud library sync (LWW model + engine)
└── ui/              theme tokens, glass components, navigation
```

Every feed, the player, sharing and the duplicate finder go through `MediaSource`
(`scan()`, `playback()` → URI + headers, `byteSource()`, `delete()`, `shareUri()`), so local and
remote sources appear in the same feeds.

### Navigation (v0.5)

At most five bottom tabs (`ui/ScrollbackApp.kt`):

| Tab | What's there |
|---|---|
| **Feed** | Vertical video feed with a **Random \| Saved** segmented toggle at the top (reshuffle / newest–oldest next to it) |
| **Pictures** | Grid + full-screen viewer |
| **Music** | Songs, artists, albums, folders, playlists (hidden when Settings → Show Music is off, leaving 4 tabs) |
| **Files** | File manager |
| **Library** | Liked, Playlists, History, Search, Duplicates, Hidden items |

Settings (folders, servers/accounts, playback, privacy, music) open from the gear in the top bar
(`LocalOpenSettings`). The glass mini player floats above the nav on every tab except the feed.

## Media index and sources

- Source folders are Storage Access Framework trees (persistable tree URIs), scanned recursively.
  With All-files access, folders added from the file manager use `java.io.File` (`RawMediaSource`).
- The index lives in Room (name, path, size, dates, duration, resolution, mime, hashes, likes,
  notes, tags, hidden flag, watch history and resume position).
- All sources are rescanned at app start and from Settings → Rescan now.
- **PIN lock:** PBKDF2 (`PBKDF2WithHmacSHA256`) hash stored in DataStore; the PIN itself is never
  stored. The app re-locks after 30 s in the background (`MainActivity`). `FLAG_SECURE` is applied
  when Block screenshots is on.
- **Duplicate finder:** groups by size → partial hash (size + first and last 64 KiB) → full
  SHA-256 only on partial-hash ties. The review screen pre-selects every copy except the oldest
  and deletes through the source (SAF / server) only after confirmation.

## File manager internals

- **Home:** storage usage summary, MediaStore categories (Videos, Images, Audio, Documents,
  Downloads, Installation files), recent files, locations (granted SAF folders, internal storage
  with All-files access).
- **Browser:** breadcrumbs, list / grid, sort by name / date / size / type (asc/desc, folders
  first), long-press multi-select, copy / move (clipboard + paste bar), rename, delete (with
  confirmation), new folder, search in the current tree, share, open with, file info, and
  **Add source** for any folder.
- Works over SAF by default (`DocumentsContract`), or over `java.io.File` when the optional All
  files access is granted. Category deletes go through `MediaStore.createDeleteRequest` (system
  confirmation) without it. Sharing uses a `FileProvider` (`${applicationId}.files`).

## Nextcloud internals

- **Login Flow v2:** the user approves access on the Nextcloud's own page (2FA/SSO work) and the
  app receives an app password, encrypted with an Android Keystore AES-GCM key
  (`CredentialStore` in `accounts/Accounts.kt`). It is never logged, never stored in the database
  and is added to requests on the wire by one OkHttp interceptor (`RemoteAuth` in
  `remote/Http.kt`).
- **Browse** over WebDAV (PROPFIND, `remote/webdav`). Any folder can be added as a source
  (`WebDavMediaSource`); its media is **streamed** — Media3 via `media3-datasource-okhttp` with a
  512 MB LRU `SimpleCache` (local files skip the cache), thumbnails from Nextcloud's preview
  endpoint through Coil's OkHttp fetcher.
- **Download** saves a copy to `Download/Scrollback` via MediaStore (no storage permission needed);
  downloaded items then play and share offline.
- **Library sync:** likes, hidden flags, notes, tags, watch history/resume and playlists are kept
  in `/Scrollback/scrollback-sync.json`, merged **last-write-wins per record** by timestamp
  (`SyncMerge` in `sync/SyncModel.kt`, unit-tested; ties go to the remote copy). Items are
  matched across devices by Nextcloud path, or by file name + size for local files. Sync runs at
  app start, when the app goes to the background, and from "Sync now"; uploads use `If-Match` so
  concurrent writers retry instead of clobbering.
- **Offline:** scans of unreachable servers are skipped without marking anything missing; sync
  errors are shown on the account and never block the app. Addresses without a scheme get
  `https://`; plain `http://` is used only when typed explicitly (for LAN servers). Note that the
  manifest sets `android:usesCleartextTraffic="true"` app-wide to allow this.

## Music internals

- **Sources:** audio in local folders (tags read with `MediaMetadataRetriever`, embedded cover art
  via a small Coil fetcher), Nextcloud folders (streamed over WebDAV), and music servers:
  - **Subsonic / OpenSubsonic** (Navidrome, Nextcloud Music, Airsonic, Gonic… all through the same
    Subsonic client; there is no Navidrome-specific client): token + salt auth
    (`t = md5(password + salt)`, fresh salt per request), albums → songs, streaming and cover art.
    **Star/unstar** mirrors likes both ways (a like made in the app since the last scan wins,
    otherwise the server's star wins).
  - **Jellyfin:** username/password are used once (`/Users/AuthenticateByName`); only the access
    token is kept (encrypted). Audio items, streaming via the universal audio endpoint (direct play
    or progressive MP3 transcode), primary images, favourites mirrored to likes.
  - All of these are `MediaSource`s. Songs are stored as `subsonic:<account>/<id>` /
    `jellyfin:<account>/<id>` and resolved to real URLs only at request time, so tokens never
    reach the database, caches or logs.
- **Playback:** Media3 `MediaSessionService` (`player/MusicService`) for background playback with
  the media notification, lock-screen and Bluetooth controls; shuffle, repeat (off → all → one),
  queue (play next, add, reorder, remove). A glass mini player floats above the bottom nav; the
  full player has artwork, seek bar, like and add-to-playlist. Videos take audio focus, so music
  pauses while a feed plays.

## Design system

Inspired by X / Grok (true-black OLED, white/grey type, monochrome line icons, near-zero accent),
iOS liquid glass, Samsung One UI (large collapsing titles, generous spacing), Telegram (spring
motion and haptics) and Claude / Obsidian (calm typography). Tokens live in `ui/theme/Tokens.kt`:

| Token | Value |
|---|---|
| Background | `#000000` (true black) · Surface `#0B0B0C` · SurfaceHigh `#151517` |
| Text | primary `#F2F2F2` · secondary `#9A9A9E` · tertiary `#5C5C61` |
| Accent | one restrained near-white `#E7E9EA`; semantic only: like `#F91880`, danger `#FF453A` |
| Chrome | bottom nav uses a **fixed** near-opaque surface `ChromeSurface #F0121214` + `ChromeHighlight #24FFFFFF` bubble, so it looks identical on every tab and over video (no backdrop sampling) |
| Glass | 28 dp backdrop blur (RenderEffect, API 31+) + 55% black tint + 12% white fill, 1 dp light gradient border, soft shadow; over video a cheap 35% black disc (SurfaceView can't be sampled, keeps the feed smooth) |
| Radii | 14 small · 20 card · 24 large · 28 sheet · pill |
| Spacing | 4 · 8 · 12 · 16 · 20 (screen) · 24 · 32 |
| Type | system sans; 34 sp semibold large titles, 16 sp body |
| Motion | spring(damping 0.78, stiffness 420); haptic ticks on toggles/tabs, confirm on like |

Components (`ui/components`): `Modifier.glass`, `Modifier.chromeGlass`, `GlassIconButton`,
`FloatingGlassNav` (detached pill bottom bar with a springy selection bubble), `SegmentedToggle`,
`SettingsButton`, `GlassScreen` + `LargeTitle` + `CollapsingTopBar` (One UI pattern), `GlassSheet`,
`GlassPill`, `SectionCard`/`SettingsRow`, `LockScreen` keypad.

## Store notes: `MANAGE_EXTERNAL_STORAGE`

The manifest declares `MANAGE_EXTERNAL_STORAGE`, but it is never required: the Files tab explains
what it unlocks (browsing the whole internal storage like My Files) and sends the user to the
system toggle only if they opt in. Without it everything uses SAF folder grants.

- **F-Droid:** allowed, but flagged as a sensitive permission in the listing.
- **Google Play:** the permission is restricted to file-manager / backup / antivirus style apps
  and needs a declaration form; a Play build would likely ship a flavour without it (SAF only).
  This flavour is on the roadmap.

## Development history

1. **Phase 1 — v0.1:** local offline viewer (feeds, pictures, library, PIN, duplicates).
2. **Phase 2 — v0.2.0:** One UI "My Files"-style file manager over SAF, in `files/`.
3. **Phase 3 — v0.3.0:** `WebDavMediaSource` (PROPFIND scan) that streams remote media in the same
   feeds, library sync, optional offline copies, Login Flow v2 app password stored encrypted.
4. **Phase 4 — v0.4.0:** music player on a Media3 `MediaSessionService`, reusing folders, likes,
   playlists and tags; Subsonic/OpenSubsonic and Jellyfin clients; optional tab toggled in Settings.
5. **v0.5.0:** navigation rework (five tabs, Settings behind the gear).
6. **v0.6.0:** in-app updates, signed release builds, ABI splits and [ROADMAP.md](../ROADMAP.md).

No third-party code is copied; SnapReel (MIT) was only a design reference.
