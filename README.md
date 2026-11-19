# Scrollback

A private, offline, TikTok-style viewer for the videos and pictures you've saved on your phone.

Saved TikToks pile up in a folder and gallery apps make them hard to find or re-watch. Scrollback
indexes the folders you pick and plays them in a full-screen vertical feed — shuffled or in the
order you saved them — with likes, playlists, notes, tags and search. Local folders never leave the
device; an optional Nextcloud account can stream more media and sync your library.

> Status: **v0.5** (all roadmap phases implemented) — early, personal project. Android 13+ (minSdk 33).

## Features (v1)

- **Multiple source folders** via the Storage Access Framework (persistable tree URIs), scanned
  recursively; the index lives in Room (name, path, size, dates, duration, resolution, mime, hashes).
- **Random** feed — shuffled vertical swipe with a reshuffle button.
- **Saved order** feed — by date saved, newest ↔ oldest toggle.
- **Pictures** — grid plus a swipeable full-screen viewer.
- **Library** — Liked, Playlists, History, Search.
- **TikTok-style overlay** — like (or double-tap), save to playlist, notes & tags, share via the
  Android share sheet, info; tap to pause; draggable progress line.
- **Playlists** — create, rename, delete, reorder, play as a feed.
- **Search** across file names, folders, notes and tags, with tag-chip filtering.
- **Watch history** with per-video resume position.
- **Playback speed** 0.5×–2× and **loop one / auto-advance**.
- **PIN lock** — optional app lock (PBKDF2-hashed in DataStore, re-locks after 30 s in the
  background) and per-item **hidden** flag that only shows after unlocking; optional FLAG_SECURE.
- **Duplicate finder** — groups by size → partial hash (first + last 64 KiB) → full SHA-256 on
  ties; review screen keeps the oldest copy by default and deletes via SAF after confirmation.
- v0.1–0.2 had no `INTERNET` permission; v0.3 adds it for optional servers (see below).

## File manager (v0.2)

A My Files / Nextcloud Files-style browser in the **Files** tab:

- **Home:** storage usage summary, MediaStore categories (Videos, Images, Audio, Documents,
  Downloads, APKs), recent files, locations (granted SAF folders, internal storage with All-files access).
- **Browser:** breadcrumbs, list / grid, sort by name / date / size / type (asc/desc, folders first),
  long-press multi-select, copy / move (clipboard + paste bar), rename, delete (with confirmation),
  new folder, search in the current tree, share, open with, file info, and
  **Add as Scrollback source** for any folder.
- Works over **SAF** by default (`DocumentsContract`), or over `java.io.File` when the optional
  *All files access* is granted. Category deletes go through `MediaStore.createDeleteRequest`
  (system confirmation) without it.

### About `MANAGE_EXTERNAL_STORAGE`

The manifest declares `MANAGE_EXTERNAL_STORAGE`, but it is **never required**: the app explains what it
unlocks (browsing the whole internal storage like My Files) and sends you to the system toggle only if
you opt in. Without it everything uses SAF folder grants.

- **F-Droid:** allowed, but flagged as a sensitive permission in the listing.
- **Google Play:** the permission is restricted to file-manager / backup / antivirus style apps and needs
  a declaration form; a Play build would likely ship a flavor without it (SAF only).

## Navigation (v0.5)

At most five bottom tabs, so nothing is crammed into the nav:

| Tab | What's there |
|---|---|
| **Feed** | Vertical video feed with a **Random \| Saved** segmented toggle at the top (reshuffle / newest–oldest next to it) |
| **Pictures** | Grid + full-screen viewer |
| **Music** | Songs, artists, albums, folders, playlists (hidden when Settings → Show Music is off, leaving 4 tabs) |
| **Files** | File manager |
| **Library** | Liked, Playlists, History, Search, Duplicates, Hidden items |

**Settings** (folders, servers/accounts, playback, privacy, music) open from the **gear** in the top bar of
every tab. The glass mini player floats above the nav on every tab except the feed.

## Nextcloud (v0.3)

Optional — Scrollback is fully usable offline and without any account. Settings → **Servers**:

- **Sign in with Login Flow v2:** you approve access on your Nextcloud's own page (2FA/SSO work) and
  Scrollback receives an **app password**, encrypted with an Android Keystore AES-GCM key
  (`accounts/CredentialStore`). It is never logged, never stored in the database and is added to
  requests on the wire by one OkHttp interceptor (`remote/RemoteAuth`). Revoke it any time in
  Nextcloud → Settings → Security.
- **Browse** your files over WebDAV (PROPFIND) and **add any folder as a Scrollback source**. Its videos
  and pictures appear in the same Random / Saved / Pictures feeds and are **streamed** — Media3 via
  `media3-datasource-okhttp` with a 512 MB LRU `SimpleCache`, thumbnails from Nextcloud's preview
  endpoint through Coil's OkHttp fetcher. No download needed.
- **Download** (feed → Info → Download, or from the browser) saves a copy to `Download/Scrollback`;
  downloaded items then play and share offline.
- **Library sync:** likes, hidden flags, notes, tags, watch history/resume and playlists are kept in
  `/Scrollback/scrollback-sync.json` in your Nextcloud, merged **last-write-wins per record** by
  timestamp (`sync/SyncMerge`, unit-tested). Items are matched across devices by Nextcloud path, or
  by file name + size for local files. Sync runs at app start, when the app goes to the background,
  and from "Sync now"; uploads use `If-Match` so concurrent writers retry instead of clobbering.
- **Offline:** scans of unreachable servers are skipped without marking anything missing; sync errors
  are shown on the account and never block the app. Plain `http://` is allowed only when typed
  explicitly (for LAN servers); `https://` is the default.

## Music (v0.4)

A **Music** section (hide it in Settings → Music) built on the same index, likes, playlists and tags:

- **Sources:** audio in your local folders (tags read with `MediaMetadataRetriever`, embedded cover art
  via a small Coil fetcher), Nextcloud folders (streamed over WebDAV), and music servers:
  - **Subsonic / OpenSubsonic** (Navidrome, Nextcloud Music, Airsonic, Gonic…): token + salt auth
    (`t = md5(password + salt)`, fresh salt per request), albums → songs, streaming and cover art.
    **Star/unstar** mirrors likes both ways (a like made here since the last scan wins, otherwise the
    server's star wins).
  - **Jellyfin:** username/password are used once (`/Users/AuthenticateByName`); only the access token
    is kept (encrypted). Audio items, streaming via the **universal audio endpoint** (direct play or
    progressive MP3 transcode), primary images, favourites mirrored to likes.
  - All of these are `MediaSource`s. Songs are stored as `subsonic:<account>/<id>` / `jellyfin:<account>/<id>`
    and resolved to real URLs only at request time, so tokens never reach the DB, caches or logs.
    Accounts live in **Settings → Servers**.
- **Browse:** Songs, Artists, Albums, Folders, Playlists (+ Liked songs, Recently played), search.
- **Playback:** Media3 `MediaSessionService` (`player/MusicService`) for background playback with the
  media notification, lock-screen and Bluetooth controls; shuffle, repeat (all/one), queue (play next,
  add, reorder, remove). A glass **mini player** floats above the bottom nav; the **full player** has
  artwork, seek bar, like and add-to-playlist. Videos take audio focus, so music pauses while a feed plays.
- **Future:** Jellyfin **video** libraries in the feeds; Subsonic scrobbling; lyrics.

## Stack

Kotlin 2.4 · Jetpack Compose + Material 3 (dark only) · AndroidX Media3 ExoPlayer · Room · DataStore ·
Coil 3 (+ video frames, OkHttp network) · OkHttp 5 · Haze (backdrop blur) · Navigation Compose · manual DI (`AppContainer`).
AGP 9.5 / Gradle 9.9 (wrapper included), JDK 21, compile/target SDK 37.

## Design system

Inspired by X / Grok (true-black OLED, white/grey type, monochrome line icons, near-zero accent),
iOS liquid glass, Samsung One UI (large collapsing titles, generous spacing), Telegram (spring motion
and haptics) and Claude / Obsidian (calm typography). Tokens live in `ui/theme/Tokens.kt`:

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

Components (`ui/components`): `Modifier.glass`, `Modifier.chromeGlass`, `GlassIconButton`, `FloatingGlassNav` (detached pill
bottom bar with a springy selection bubble), `SegmentedToggle`, `SettingsButton`, `GlassScreen` + `LargeTitle` + `CollapsingTopBar`
(One UI pattern), `GlassSheet`, `GlassPill`, `SectionCard`/`SettingsRow`, `LockScreen` keypad.

## Architecture

```
app.scrollback
├── core/            pure Kotlin: duplicate hashing, PIN hashing, tags, feed ordering (unit-tested)
├── data/
│   ├── db/          Room entities + DAOs (generic media kinds: video, image, audio)
│   ├── source/      MediaSource abstraction — Saf, Raw (java.io), WebDav (Nextcloud)
│   ├── repo/        LibraryRepository, DuplicateRepository
│   └── prefs/       DataStore settings
├── feature/         feed, pictures, files, library, settings (folders, duplicates), lock
├── files/           file manager backends (Raw java.io / SAF / MediaStore), sort, names, clipboard ops
├── accounts/        server accounts + Keystore-encrypted credentials, ServerManager
├── remote/          OkHttp + RemoteAuth, WebDAV, Nextcloud Login Flow v2, Subsonic + Jellyfin clients, downloads
├── player/          Media3 factory (local direct, remote via cache -> resolver -> OkHttp), MusicService, MusicPlayer, AudioArt
├── sync/            Nextcloud library sync (LWW model + engine)
└── ui/              theme tokens, glass components, navigation
```

Every feed, the player, sharing and the duplicate finder go through `MediaSource`
(`scan()`, `playback()` → URI + headers, `byteSource()`, `delete()`, `shareUri()`), so remote
sources can appear in the same feeds later.

## Network storage: SFTP and SMB (v0.7)

- **Files → Tools → Network storage → Add server.** Pick **SFTP** (host, port 22, start folder, user,
  password or private key via **Load key file…** / paste) or **SMB** (host, port 445, share, folder,
  `DOMAIN\user` or empty for guest, password). **Connect & save** tests the connection before saving.
- **SFTP host keys work on trust-on-first-use.** The first connection shows the server's `SHA256:` fingerprint
  for you to confirm. After that it is pinned, and Scrollback refuses to connect if it changes (which could mean
  a man-in-the-middle). Compare the fingerprint with `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`.
- Saved servers appear under **Files → Locations**. You can browse, create folders, rename, delete and share.
  Opening or sharing a file downloads it to the app cache first.
  **Add to feeds** (or **Add source** on a folder) streams videos and pictures straight from the server with
  seeking, through the shared 512 MB media cache.
- Passwords and keys are stored AES-GCM encrypted (Android Keystore), like the Nextcloud and music accounts.
- Libraries: [sshj](https://github.com/hierynomus/sshj) (Apache-2.0), [smbj](https://github.com/hierynomus/smbj)
  (Apache-2.0) and [Bouncy Castle](https://www.bouncycastle.org/) (MIT-style Bouncy Castle licence) for
  modern SSH ciphers (ed25519, chacha20).
- Status: SFTP is verified end to end against OpenSSH (list, seek-read, write, rename, delete, host-key
  pinning and mismatch). SMB is verified for list and stat against impacket. Reads still need confirming on a real
  Samba or Windows share. `SB_SFTP_TEST` / `SB_SMB_TEST` enable the integration tests (see `app/src/test`).

## Updates and releases (v0.6)

- **Settings → Updates → Check for update** is the only time the app contacts GitHub. There are no background checks.
  It reads the latest GitHub Release of `PouryaFA81/Scrollback` and picks the APK for this build
  and CPU (`Build.SUPPORTED_ABIS`: arm64-v8a, then armeabi-v7a, then universal). Before installing,
  it checks that the package name and signing certificate match the installed app. Then it installs
  through `PackageInstaller` (`REQUEST_INSTALL_PACKAGES`, and Android asks you to confirm).
- While the repo is private, the anonymous API returns 404. You can save a fine-grained token
  (read-only Contents) under **GitHub token**. It is AES-GCM encrypted with an Android Keystore key and only
  sent to api.github.com. Once the repo is public, no token is needed.
- **Signing.** Release APKs (`app.scrollback`) are signed with a dedicated release key in
  `keystore/` (gitignored: `release.jks` + `keystore.properties`; never committed or printed). Debug APKs
  (`app.scrollback.debug`) use this machine's debug key. The two install side by side, and each
  updates only from its own kind of asset. **Back up `keystore/`**: if it is lost, future release
  updates can't install over existing release installs.
- **Each release** attaches `scrollback-<ver>-arm64-v8a-release.apk`, `-armeabi-v7a-release.apk`,
  `-universal-release.apk`, `-universal-debug.apk` and `SHA256SUMS`.

## Build

```bash
./scripts/build-apk.sh              # debug + signed release splits → dist/ and dist/archive/ (timestamped)
./scripts/build-apk.sh --install    # same, then adb install (debug + arm64 release)
./scripts/build-apk.sh --no-release # debug only (fast)
./scripts/publish-release.sh v0.6.0 # GitHub Release for a pushed tag, with all APKs attached
./gradlew testDebugUnitTest lint
```

The release build uses R8 minify plus resource shrinking. The debug APK is ~75 MB because nothing is
stripped (e.g. the whole material-icons-extended set). The release APK is ~10 MB (v0.7; about 4 MB of that is
Bouncy Castle for modern SSH ciphers, ~5.7 MB before): R8 drops every
icon that isn't used, so the extended icon artifact costs nothing in release builds.
See [ROADMAP.md](ROADMAP.md) for what's next.

## License

**TBD** — to be chosen before the repository goes public. Until then, all rights reserved.
No third-party code is copied; SnapReel (MIT) was only a design reference. Dependencies keep their own\nlicences (AndroidX/Media3/OkHttp/Coil/sshj/smbj: Apache-2.0; Bouncy Castle: MIT-style).
