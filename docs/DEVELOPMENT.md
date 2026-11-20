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
- [Server connections: redirects, alternate addresses, Test connection (v0.14)](#server-connections-redirects-alternate-addresses-test-connection-v014)
- [Network storage: SFTP and SMB (v0.7)](#network-storage-sftp-and-smb-v07)
- [Notes and archives (v0.8)](#notes-and-archives-v08)
- [Convert with VERT (v0.9)](#convert-with-vert-v09)
- [Office (v0.10)](#office-v010)
- [Nearby share (v0.11)](#nearby-share-v011)
- [Watch: Jellyfin movies and shows (v0.12)](#watch-jellyfin-movies-and-shows-v012)
- [Scrollback Player (v0.13)](#scrollback-player-v013)
- [Release APK sizes](#release-apk-sizes)
- [Licensing and SPDX headers](#licensing-and-spdx-headers)
- [Design system](#design-system)
- [Store notes: `MANAGE_EXTERNAL_STORAGE`](#store-notes-manage_external_storage)
- [Development history](#development-history)

## Build and test

```bash
./scripts/build-apk.sh              # debug + signed release splits → dist/ and dist/archive/ (timestamped)
./scripts/build-apk.sh --install    # same, then adb install (debug + arm64-v8a release)
./scripts/build-apk.sh --no-release # debug only (fast)
./scripts/publish-release.sh v0.14.1 # GitHub Release for a pushed tag, with the APKs + SHA256SUMS
./scripts/check-private.sh          # private-detail guard (see below)
./gradlew testDebugUnitTest lint    # JVM unit tests (app/src/test) and Android lint
```

- `dist/` is git-ignored. `build-apk.sh` never overwrites older archives; it reads the version from
  `versionName` in `app/build.gradle.kts`.
- **Private-detail guard (v0.14.1):** `scripts/check-private.sh [extra-file ...]` runs `git grep` over
  tracked files (plus any files you pass, such as release notes) with the extended regexes in
  `.private-patterns`. That file is git-ignored and stays on the maintainer's machine, so the patterns
  never reach GitHub. Without it the script prints a notice and skips. `publish-release.sh` runs it on
  the release notes before creating a release.
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
  `-armeabi-v7a-release.apk`, `-universal-release.apk` and `SHA256SUMS`. v0.6.0 to v0.8.0 also
  attached a `-universal-debug.apk`; releases from v0.9.0 on don't.
- Unit tests live in `app/src/test`. `SB_SFTP_TEST` / `SB_SMB_TEST` enable the SFTP and SMB
  integration tests against a real server.

### In-app updates (v0.6)

- `update/Updater.kt` + `feature/settings/UpdatesSection.kt`. It runs only when the user taps
  **Check for update**: no background polling, no scheduled job.
- Reads `https://api.github.com/repos/PouryaFA81/Scrollback/releases/latest` and picks the asset
  with `GitHubReleases.pickApk`: for release builds the first `-<abi>-release.apk` in
  `Build.SUPPORTED_ABIS` order, then `-universal-release.apk`; for debug builds
  `-universal-debug.apk` (no longer attached since v0.9.0). Version comparison is in `AppVersion` (unit-tested in `UpdateLogicTest`).
- Before installing, it checks that the package name and signing certificates of the downloaded APK
  match the installed app, then installs through `PackageInstaller` (`REQUEST_INSTALL_PACKAGES`;
  Android asks the user to confirm).
- While the repo is private, the anonymous API returns 404. An optional fine-grained token
  (read-only Contents) is stored AES-GCM encrypted with an Android Keystore key in
  `CredentialStore` and only sent to api.github.com.

## Stack

Kotlin 2.4 · Jetpack Compose + Material 3 (dark only) · AndroidX Media3 ExoPlayer + MediaSession ·
libVLC 3.7.7 (Scrollback Player) · Room · DataStore · Coil 3 (+ video frames, OkHttp network) ·
OkHttp 5 · Haze (backdrop blur) · sshj + smbj + Bouncy Castle (SFTP/SMB) · Apache Commons
Compress + XZ for Java (archives) · NanoHTTPD (Nearby share) · Navigation Compose · manual DI
(`AppContainer`).
AGP 9.5 / Gradle 9.9 (wrapper included), compile/target SDK 37. The README on main said JDK 21;
the Gradle files set Java source/target compatibility to 17 and declare no toolchain. Dependency versions live in `gradle/libs.versions.toml`.

There are no analytics, crash-reporting or advertising SDKs in the dependency list.

## Architecture

```
app.scrollback
├── core/            pure Kotlin: duplicate hashing, PIN hashing, tags, feed ordering, music merge (unit-tested)
├── data/
│   ├── db/          Room entities + DAOs (generic media kinds: video, image, audio)
│   ├── source/      MediaSource abstraction — Saf, Raw (java.io), WebDav (Nextcloud), registry
│   ├── repo/        LibraryRepository, DuplicateRepository
│   └── prefs/       DataStore settings
├── feature/         feed, pictures, files, library, music, servers, settings, lock, notes, office,
│                    convert, nearby, watch, about (open-source licenses)
├── files/           file manager backends (Raw java.io / SAF / MediaStore), sort, names, clipboard ops, archives
├── accounts/        server accounts + Keystore-encrypted credentials, ServerManager, music servers
├── remote/          OkHttp + RemoteAuth, redirects, ServerProbe, WebDAV, Nextcloud Login Flow v2,
│                    Subsonic + Jellyfin clients (music and video), SFTP/SMB (remote/fs), downloads
├── player/          Media3 factory (local direct, remote via cache -> resolver -> OkHttp), MusicService, MusicPlayer, AudioArt
├── fullplayer/      Scrollback Player: libVLC engine, activity, service, loopback stream, play queue, prefs
├── watch/           Jellyfin Watch models
├── nearby/          LocalSend v2: protocol, identity, discovery, server, client, manager, service
├── notes/           Markdown parsing for the notes editor
├── office/          OOXML (DOCX/XLSX/PPTX) and CSV parsers
├── convert/         VERT / vertd client and service
├── update/          GitHub Releases check and installer
├── sync/            Nextcloud library sync (LWW model + engine)
└── ui/              theme tokens, glass components, swipe tabs, navigation
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
| **Library** | Watch (Jellyfin), Liked, Playlists, History, Search, Duplicates, Hidden items |

Since v0.8, swiping left or right on Library and Music moves between their chip sub-tabs
(`ui/components/SwipeTabs.kt`). The feed doesn't do this, because horizontal drags there belong to
seeking and picture zoom.

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
- **Tap to open** (v0.8–v0.13): `.md`/`.txt` files open in the notes editor, documents in Office,
  archives in Archives, videos in Scrollback Player (the folder's other videos become the
  playlist); anything else goes to Android's Open with. The selection bar adds **Send nearby**,
  **Compress**, **Play**, **Open with** and **Convert**.
- **Files → Tools** (one card, so the tab bar stays at five): Network storage, Notes, Archives,
  Nearby share, Office and Convert (VERT).
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
- **Login Flow v2 address (v0.14):** the login and poll URLs are kept on the address the server
  really answered on, even when Nextcloud reports another scheme or host (its `overwrite.cli.url`,
  often a LAN or VPN name). That other host is saved as the alternate address.

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
- **Hide duplicates across servers (v0.14,** `core/music/MusicMerge.kt`**):** when one music folder
  is served by several servers (for example a Subsonic server and a Jellyfin server), the toggle in Settings →
  Music or Settings → Servers shows each song once. Songs from *different sources* match on
  normalised title (accents and punctuation folded, a leading "the" dropped; the file name without
  a track number when there's no title tag), compatible artist (same main artist, or missing /
  "Various Artists") and album, length within 2 s, and track/disc number when both copies have
  them. When either copy lacks full tags, a known length on both sides or an identical file size
  is required. Each song then shows once, from the **preferred source** (ordered on the Servers
  screen), with a small "N sources" label; long-press it (or ⋯ → **Play from…**) to play another
  copy. A like applies to every copy (and is mirrored to each server that supports stars or
  favourites). It's a **view-only merge**: nothing is deleted or changed in the database, on the
  phone or on any server.
- **Future:** Subsonic scrobbling; lyrics.

## Server connections: redirects, alternate addresses, Test connection (v0.14)

- **Own redirect follower** (`remote/Redirects.kt`, `RedirectInterceptor`). OkHttp's built-in
  follower is switched off because it dropped the `Authorization` header on every scheme, host or
  port change (so an `http://` address that redirects to `https://` got 401s) and turned 301/302
  POSTs into GETs (Login Flow v2 start/poll then failed).
  - 301, 302, 303, 307 and 308 are followed, at most 10 hops; then the call fails with "Too many
    redirects".
  - 307/308 always keep method and body; WebDAV verbs (PROPFIND, PROPPATCH, MKCOL, COPY, MOVE,
    LOCK, UNLOCK, REPORT, SEARCH, PUT, DELETE, PATCH) keep them for every code; a 301/302 POST
    keeps them only when the path and query stay the same, otherwise becomes a GET; 303 becomes GET.
  - Credential headers (`Authorization`, `X-Emby-Token`, `X-Emby-Authorization`,
    `X-MediaBrowser-Token`, `Cookie`) survive a hop only to the **same host without an https → http
    downgrade**, or to an address configured for that server (its primary or alternate address).
    Otherwise they are stripped. Configured-server credentials are re-applied per hop by
    `RemoteAuth`.
- **Canonical address at sign-in** (`remote/ServerProbe.kt`). Adding a server first follows its
  redirects (Nextcloud `status.php`, Subsonic `/rest/ping.view`, Jellyfin `/System/Info/Public`)
  and saves the address the server really answers on.
- **Alternate address** (`RemoteAuth.fallback` in `remote/Http.kt`). Each Nextcloud, Subsonic or
  Jellyfin account can have a second base URL. Requests go to the address that worked last; on a
  connection failure (or a 502/503/504 from a proxy for read-only requests) they are retried on the
  other one, and the preference is remembered.
- **Test connection** (`ServerManager.testConnection`, `feature/servers/ConnectionUi.kt`). Checks the
  main and the alternate address in turn, lists the redirect chain in plain words (`Explain`),
  says whether the server is what it should be and whether the saved sign-in works there, and can
  offer to **Use** the host a redirect chain ends on as the alternate address. Each probe has its
  own short timeouts (connect 8 s, read 12 s, whole call 20 s) so a VPN that drops packets doesn't
  hang on the 60 s default read timeout. Passwords, tokens and query strings are never shown.

## Network storage: SFTP and SMB (v0.7)

- **Files → Tools → Network storage → Add server.** **SFTP** (host, port 22, start folder, user,
  password or private key via **Load key file…** / paste, optional key passphrase) or **SMB2/3**
  (host, port 445, share, folder, `DOMAIN\user` or empty for guest, password). **Connect & save**
  tests the connection before saving. Code in `remote/fs` (`SftpFs`, `SmbFs`, `RemoteStorage`) and
  `feature/files/RemoteStorageScreen.kt`.
- **SFTP host keys work on trust-on-first-use.** The first connection shows the server's `SHA256:`
  fingerprint for the user to confirm (**Trust this server?**). After that it is pinned, and the
  connection is refused if it changes. Compare it with `ssh-keygen -lf <host key>.pub` on the server.
- Saved servers appear under **Files → Locations**: browse, create folders, rename, delete and
  share. Opening or sharing a file downloads it to the app cache first. **Add to feeds** (or **Add
  source** on a folder) streams videos and pictures with seeking through the shared 512 MB media
  cache.
- Passwords and keys are stored AES-GCM encrypted (Android Keystore), like the other accounts.
- Libraries: [sshj](https://github.com/hierynomus/sshj) (Apache-2.0),
  [smbj](https://github.com/hierynomus/smbj) (Apache-2.0) and
  [Bouncy Castle](https://www.bouncycastle.org/) (MIT-style licence) for modern SSH ciphers
  (ed25519, chacha20).
- Status at release: SFTP verified end to end against OpenSSH (list, seek-read, write, rename,
  delete, host-key pinning and mismatch). SMB verified for list and stat; reads still need
  confirming on a real Samba or Windows share.

## Notes and archives (v0.8)

- **Notes** (`feature/notes`, `notes/Markdown.kt`): Files → Tools → Notes, or tap any `.md` / `.txt`
  file (under 2 MB) in Files.
  - Edit | Preview toggle. The preview covers headings, bold/italic/strike, code blocks, quotes,
    lists and task lists, tables, links, `[[wikilinks]]` and `#tags`. Front matter is shown dimmed
    and never changed.
  - A formatting toolbar (heading, bold, italic, strikethrough, list, task, quote, code, link).
  - Autosave 1.2 s after typing stops, and again on leaving. A word count.
  - Works on local folders, SAF folders and SFTP/SMB servers. The notes list uses a folder the user
    picks (an Obsidian vault works) or the app's own notes folder.
- **Archives** (`files/Archives.kt`, `feature/files/ArchiveUi.kt`): Files → Tools → Archives, or tap
  an archive in Files.
  - Extract ZIP, TAR, TAR.GZ/BZ2/XZ, 7z and single GZ/BZ2/XZ files, either here or into a new folder.
  - Select files and tap **Compress** to make a ZIP.
  - Safe by default: existing files are never overwritten (names get " (1)"), entries that escape
    the folder (zip-slip) are refused, symlinks and devices are skipped, and extraction stops on
    suspicious ratios or sizes (zip bombs). Password-protected archives aren't supported yet.
  - Libraries: Apache Commons Compress (Apache-2.0) and XZ for Java (0BSD).

## Convert with VERT (v0.9)

- **Files → Tools → Convert (VERT)**, or select one file in Files and tap **Convert**
  (`convert/`, `feature/convert/ConvertScreen.kt`).
- **Video** converts on a **vertd** server: MP4, WebM, MKV, MOV, AVI, GIF, animated WebP/APNG, M4V
  or 3GP, at Fast, Balanced or Quality. The app uploads the file, follows the job over vertd's
  websocket, downloads the result to `Download/Scrollback/`, then confirms so the server deletes its
  copy.
- **Images, audio and documents** open in the VERT web app, which converts them in the browser.
- **Server (v0.14.1):** the VERT server dialog in `ConvertScreen.kt` offers two choices.
  **vert.sh** (default) sets `VertConfig.DEFAULT_INSTANCE` (https://vert.sh) and
  `DEFAULT_VERTD` (https://vertd.vert.sh), run by the VERT team. **Custom** clears both fields for
  the user's own VERT URL and vertd URL (self-hosted VERT shows its vertd URL in its settings); it is
  selected whenever the saved instance isn't vert.sh, so a URL saved by an older version is kept.
  `VertService.save()` normalises the URLs (`VertConfig.normalize`, adds `https://` when no scheme
  is given), falls back to vert.sh when the VERT URL is blank, and stores them in the app's private
  preferences on the device only. A blank vertd URL turns video conversion off. There is no built-in
  preset for any personal server. An optional extra header (`Name: value`) supports a vertd behind
  an auth proxy; it is stored encrypted and only sent to vertd.
- VERT and vertd are separate GPL-3.0 projects by the VERT team (https://github.com/VERT-sh).
  Scrollback only talks to their HTTP API and includes none of their code.

## Office (v0.10)

- **Files → Tools → Office**, tap a document in Files, or pick Scrollback in another app's
  **Open with** (`feature/office`, `office/`).
- **PDF:** Android's `PdfRenderer`. Swipe between pages, pinch or double-tap to zoom, tap the page
  counter to jump. **Search** with highlighted matches needs Android 15+ and a text layer.
  Password-protected PDFs aren't supported yet.
- **DOCX:** paragraphs, headings, bold and italic, bulleted and numbered lists, simple tables;
  selectable text.
- **XLSX:** every sheet as a grid (shared strings, inline strings, numbers, booleans, cached formula
  results; number and date formats aren't applied). Up to 5,000 rows × 200 columns.
- **PPTX:** slide titles, text, pictures and speaker notes, in slide order.
- **CSV / TSV:** a table editor with **Save** and **Save as…**; comma, semicolon and tab separators
  are detected.
- **Why no Apache POI:** OOXML files are zips of XML. The viewers read only the parts they need with
  `XmlPullParser`, so there are no new runtime dependencies; the suite adds about 145 KB per APK.
  Old binary `.doc`/`.xls`/`.ppt` aren't supported.

## Nearby share (v0.11)

LocalSend-compatible file sharing on the local network (`nearby/`, `feature/nearby`). It works with
the official [LocalSend](https://localsend.org) apps and with RSend.

- Entry points: **Files → Tools → Nearby share**, **Send nearby** in the Files and category
  selection bars, **Send nearby** in the feed's share sheet (a chooser action on Android 14+), and
  **Nearby share** as a target in other apps' share sheets (`NearbyShareTarget` activity alias).
- **Discovery:** multicast announcements on `224.0.0.167:53317` (with a Wi-Fi multicast lock),
  answered with `POST /register`, plus an HTTP(S) scan of the private /24 subnet for networks that
  drop multicast, and **Add by address**.
- **Server:** HTTPS on port 53317 with a self-signed certificate (RSA-2048) kept in an app-private
  PKCS#12. The fingerprint is the SHA-256 of the DER certificate. It answers
  `/api/localsend/v2/info`, `register`, `prepare-upload`, `upload` and `cancel`, on
  [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) (BSD-3-Clause).
- **Client:** OkHttp, with the peer's certificate pinned to the fingerprint it announced.
- **Receiving:** Accept/Decline (or **Quick save**), an optional **Receive PIN** (after 5 wrong tries a
  sender is locked out for a minute), and the save folder `Download/Scrollback` or a folder the user
  picks. The SHA-256 is checked when the sender includes it.
- **Battery and privacy:** the server and discovery run only while the Nearby share screen is open
  or a transfer is active, in a `dataSync` foreground service with a progress notification.
- **Permissions:** `CHANGE_WIFI_MULTICAST_STATE`, `ACCESS_WIFI_STATE`, `FOREGROUND_SERVICE_DATA_SYNC`,
  and on Android 17+ `ACCESS_LOCAL_NETWORK` (requested the first time Nearby share opens). Apps
  targeting API 37 can't open or accept LAN connections without it.
- **Credits:** the protocol logic is a Kotlin port of the `lsend` package of RSend
  (`PouryaFA81/rsend`, same author). It follows the public LocalSend v2 protocol for
  interoperability; LocalSend ([localsend/localsend](https://github.com/localsend/localsend),
  Apache-2.0) is a separate project and **no LocalSend code is included**.

## Watch: Jellyfin movies and shows (v0.12)

- **Library → Watch** (`watch/`, `feature/watch`, `remote/jellyfin`). Uses the Jellyfin account from
  Settings → Servers; with several, **Switch server** (⇄).
- **Home:** Continue watching, Next up, Recently added movies and shows, Favorites. **Movies**,
  **Shows** and **Collections** are poster grids loaded 60 at a time; **Search** covers movies,
  shows, episodes and collections.
- **Details:** backdrop, year, runtime, rating, genres, overview, director/writer and cast; seasons
  and episodes with progress; **Mark played/unplayed** and **Favorite** saved on the server.
- **Playback (Media3):** a device profile (H.264/HEVC/VP9/AV1, AAC/MP3/Opus/FLAC/AC3/EAC3,
  MP4/MKV/WebM) is sent with `PlaybackInfo`. Direct play (`/Videos/{id}/stream?static=true`) when
  the server says the phone can play it, otherwise Jellyfin's HLS transcode; if decoding fails, a
  transcode is requested automatically. The top bar shows **Direct play** or **Transcoding**.
- **Tracks:** embedded tracks switch on the phone, external text subtitles are side-loaded, and
  picture subtitles that need it are burned in by the server.
- **Resume and reporting:** `/Sessions/Playing`, progress every 10 s and on pause/track changes, and
  `/Sessions/Playing/Stopped`.
- Auth goes in headers, never in URLs (`api_key` is stripped from server-made URLs).
- Status at release: tested with unit tests against a fake server (MockWebServer) and a local stub
  server, not yet against a real Jellyfin.

## Scrollback Player (v0.13)

A long-form player, separate from the swipe feed (`fullplayer/`), built on **libVLC**
(`org.videolan.android:libvlc-all` 3.7.7): HEVC, VP9, AV1, MPEG-2, VC-1, AVI/WMV/FLV/TS, DTS/AC3/
TrueHD audio and ASS/SSA subtitles with their own styling (libass).

- **Entry points:** "Open with → Scrollback Player" for `video/*` (plus MKV/MP4/HLS MIME types);
  tapping a video in Files (the folder's other videos become the playlist); **Play** in the Files
  selection bar; **Play in full player** in the feed's Info sheet. Local, SAF, Nextcloud and
  SFTP/SMB files work (remote bytes go through `LoopbackStream`).
- **Automatic fallback:** when the feed's Media3 player hits a decoder error, the video opens in
  Scrollback Player instead, once per video.
- **Gestures** (`PlayerMath`, unit-tested): left side brightness, right side volume, sideways seek,
  double-tap left/right thirds ±10 s, pinch to zoom.
- **Picture:** aspect modes (Fit, Stretch, Crop, 16:9, 4:3, Original), zoom, rotation lock, frame
  capture to `Pictures/Scrollback`.
- **Tracks:** audio and subtitle tracks, external subtitle files, audio and subtitle delay;
  subtitles turn on automatically when a file has them (can be switched off). Subtitle scale,
  colour and background box (`PlayerPrefs`).
- **Playback:** speeds 0.25× to 4×, A-B repeat, previous/next, resume, HW/SW decoding toggle
  (hardware by default), screen lock, picture-in-picture, background audio with a media
  notification (`FullPlayerService`).
- **Decoder label (0.15):** the top bar's HW/SW comes from `DecoderInfo`: libVLC's video fourcc is
  mapped to a MediaCodec MIME type and checked against the phone's hardware decoders (format and
  size), so formats libVLC decodes in software (MPEG-2, VC-1, AV1 without an AV1 chip) show SW.
- **x86:** x86/x86_64 libraries are left out of release APKs; on those devices the player shows a
  notice. For an x86_64 emulator (screenshots, UI testing), build with
  `./gradlew assembleDebug -Psb.x86=true`, which keeps x86_64 and adds an `x86_64` split. The
  emulator's GPU-backed H.264 decoder often fails under SwiftShader, so use MPEG-4 Part 2 or VP9
  demo clips there.
- **Why libVLC:** a published Maven AAR (no NDK build), libass built in, MediaCodec and FFmpeg
  decoding, A-B repeat and delays built in. libmpv was the alternative. Media3 stays for the feed,
  Watch and music because it is lighter and starts faster.
- **License:** libVLC and its bundled libraries are LGPL-2.1-or-later, used unmodified as
  dynamically linked shared libraries (`libvlc.so`, `libvlcjni.so`) from the official AAR, so they
  can be replaced. Source: <https://code.videolan.org/videolan/vlc-android>.

## Release APK sizes

The release build uses R8 plus resource shrinking; the debug APK is much larger because nothing is
stripped. Sizes of the v0.14.1 release assets on GitHub (1 MiB = 1,048,576 bytes):

| APK | Bytes | About |
|---|---|---|
| arm64-v8a | 35,582,652 | 33.9 MiB |
| armeabi-v7a | 31,886,426 | 30.4 MiB |
| universal | 56,039,961 | 53.4 MiB |

libVLC (v0.13) added about 20–24 MiB per APK. Before that, the release APKs were about 9.7–10.7 MiB
(v0.7 to v0.12; about 4 MB of that is Bouncy Castle for SSH) and about 5.5 MiB in v0.6.

## Licensing and SPDX headers

- Scrollback is **GPL-3.0-or-later**, Copyright (C) 2026 Pourya FA: [LICENSE](../LICENSE),
  [NOTICE](../NOTICE), [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md). The full GPL text ships
  in the app (`assets/licenses/GPL-3.0.txt`) and is shown under **Settings → About → Open-source
  licenses** (`feature/about/OpenSourceLicenses.kt`), with the third-party list.
- Every Kotlin source file starts with:

  ```kotlin
  // SPDX-License-Identifier: GPL-3.0-or-later
  // Copyright (C) 2026 Pourya FA
  ```

  Please keep this header on new files.

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
7. **v0.7.0:** SFTP + SMB network storage under Files → Tools.
8. **v0.8.0:** notes editor and archives; swipe between Library and Music sub-tabs.
9. **v0.9.0:** Convert with VERT.
10. **v0.10.0:** Office viewers and CSV editor.
11. **v0.11.0:** Nearby share (LocalSend v2, ported from RSend).
12. **v0.12.0:** Watch (Jellyfin movies and shows).
13. **v0.13.0:** Scrollback Player (libVLC).
14. **v0.14.0:** own redirect follower, alternate addresses, Test connection, hide duplicates
    across servers, GPL-3.0-or-later.
15. **v0.14.1:** privacy clean-up: VERT server is vert.sh or Custom (no personal preset), generic
    docs and test data, `scripts/check-private.sh`.

No third-party code is copied; SnapReel (MIT) was only a design reference.
