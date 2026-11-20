# Scrollback

A private, offline, TikTok-style viewer for the videos and pictures you've saved on your phone.

Saved TikToks pile up in a folder and gallery apps make them hard to find or re-watch. Scrollback
indexes the folders you pick and plays them in a full-screen vertical feed — shuffled or in the
order you saved them — with likes, playlists, notes, tags and search. Local folders never leave the
device; an optional Nextcloud account can stream more media and sync your library.

> Status: **v0.14** — early, personal project. Android 13+ (minSdk 33). License: GPL-3.0-or-later.

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

## Server connections: redirects, VPN and alternate addresses (v0.14)

- **Redirects keep working.** Scrollback follows 301/302/303/307/308 itself (at most 10 hops). WebDAV verbs
  (PROPFIND, PUT, MKCOL, MOVE…) and 307/308 keep their method and body. Your sign-in stays attached when a
  server only upgrades http → https or moves to another path, and is re-applied to any address you configured
  for that server. It is **never** sent to another host you didn't configure, nor over an https → http downgrade.
  Before 0.14, OkHttp's built-in follower dropped the sign-in on every scheme or host change (so an `http://`
  address that redirects to `https://` got 401s), and turned 301/302 POSTs into GETs.
- **Sign-in resolves the real address.** Adding a server first follows its redirects (Nextcloud
  `status.php`, Subsonic `/rest/ping.view`, Jellyfin `/System/Info/Public`) and saves the address the server
  really answers on. Nextcloud Login Flow v2 login and poll URLs are kept on that address, even when Nextcloud
  reports another scheme or host (its `overwrite.cli.url`, often a LAN or VPN name). That other host is saved
  as the alternate address.
- **Alternate address** (server → Alternate address): for example your LAN address at home and the
  VPN/Tailscale address away. Requests go to the address that worked last and switch automatically when it
  can't connect (or a proxy answers 502/503/504 to a read).
- **Test connection** (server → Test connection) checks each address and lists the redirect chain, in plain
  words. It says whether the server is what it should be and whether your saved sign-in works there. If
  redirects end on another host, you can **Use** that address with one tap. No passwords, tokens or query
  strings are shown.

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
- **Hide duplicates across servers (v0.14):** when one music folder is served by several servers (for
  example Nextcloud Music and Navidrome), turn on **Settings → Servers → Hide duplicates across servers**
  (also in Settings → Music). Songs are matched by normalised title + artist + album, length within 2 s,
  and track/disc number when both copies have them (untagged files need a matching length or file size).
  Each song then shows once, from your **preferred source** (reorder the sources on the same screen), with a
  small "2 sources" chip; long-press it (or ⋯ → **Play from…**) to play another copy. Albums and artists are
  merged too. A like applies to every copy, and a song counts as liked if any copy is. It's a **view-only
  merge**: nothing is deleted or changed on the phone or on any server.
- **Future:** Subsonic scrobbling; lyrics.

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

## Scrollback Player: full movie player (v0.13)

A long-form player like VLC or MX Player, separate from the swipe feed. It is built on **libVLC**
(`org.videolan.android:libvlc-all` 3.7.7), so it plays practically any format: HEVC, VP9, AV1, MPEG-2, VC-1,
AVI/WMV/FLV/TS, DTS/AC3/TrueHD audio, and ASS/SSA subtitles with their own styling (libass).

- **Where it opens**: "Open with → Scrollback Player" for any video file (plus MKV/MP4/HLS MIME types);
  tapping a video in **Files** (the other videos in that folder become the playlist); **Play** in the Files
  selection bar (with **Open with** for other apps); **Play in full player** in the feed's info sheet.
  Local, SAF, Nextcloud and SFTP/SMB files all work (remote bytes go through a local loopback stream).
- **Automatic fallback**: when the feed's Media3 player can't decode a video (MPEG-2, VC-1, DTS, AVI/WMV…), it
  opens in Scrollback Player instead, once per video.
- **Gestures**: swipe on the left for brightness, on the right for volume, sideways to seek; double-tap left or
  right to skip 10 s; pinch to zoom; tap to show or hide the glass controls.
- **Picture**: aspect modes (Fit, Stretch, Crop, 16:9, 4:3, Original), zoom, rotation lock, and frame capture to
  `Pictures/Scrollback`.
- **Tracks**: audio and subtitle track choice, load an external subtitle file, audio delay and subtitle delay.
  Subtitles turn on automatically when a file has them (can be switched off).
- **Subtitle style**: size, colour, outline or background box (ASS/SSA keep their own styling).
- **Playback**: speed 0.25–4×, A-B repeat, previous/next in the folder playlist, resume where you left off,
  hardware/software decoding toggle (hardware by default; the top bar shows HW or SW), screen lock,
  picture-in-picture, and background audio with a media notification.
- **Why libVLC**: a published Maven AAR (no NDK build), a mature Android API, libass built in, hardware
  (MediaCodec) and software (FFmpeg) decoding, A-B repeat and delays built in. libmpv was the alternative (similar
  size, but it would need a custom LGPL-only FFmpeg build). Media3 stays for the feed, Watch and music because it
  is lighter and starts faster.
- **License**: libVLC and its bundled libraries are **LGPL-2.1-or-later**. Scrollback uses them unmodified, as
  dynamically linked shared libraries (`libvlc.so`, `libvlcjni.so`) from the official AAR, so they can be
  replaced, and Scrollback itself stays free to pick any license. Source: <https://code.videolan.org/videolan/vlc-android>.
- **Size**: libVLC adds about 20–24 MB per release APK (arm64-v8a 10.6 → 33.9 MB, armeabi-v7a 10.6 → 30.3 MB,
  universal 10.7 → 53.4 MB). x86/x86_64 libraries are left out; on those devices the player shows a notice.

## Watch: Jellyfin movies and shows (v0.12)

- **Library → Watch.** It uses the Jellyfin account from **Settings → Servers** (the same sign-in as Jellyfin music; only the access token is stored, encrypted). With several Jellyfin accounts, switch with the ⇄ button.
- **Home**: Continue watching and Next up (tap to resume), Recently added movies and shows, Favorites. **Movies**, **Shows** and **Collections** are poster grids that load 60 at a time. Swipe left/right to move between them. **Search** covers movies, shows, episodes and collections.
- **Details**: backdrop, year, runtime, rating, genres, overview, director/writer and cast. Shows list seasons and episodes with progress, plus a "Play S1:E3" button for Next up. Collections list their movies. **Mark played/unplayed** and **favorite** are saved on the server.
- **Playback (Media3)**: Scrollback sends Jellyfin a device profile (H.264/HEVC/VP9/AV1, AAC/MP3/Opus/FLAC/AC3/EAC3, MP4/MKV/WebM). If the server says the phone can play the file, it plays the original (`/Videos/{id}/stream?static=true`). Otherwise it uses Jellyfin's **HLS transcode** from `PlaybackInfo`. If the phone fails to decode a file it was told it could play, Scrollback asks for a transcode automatically. The top bar shows Direct play or Transcoding.
- **Audio and subtitles**: pick from Jellyfin's list. Embedded tracks switch on the phone, external text subtitles (SRT/VTT/ASS) are side-loaded, and image subtitles that need it (PGS/DVD while transcoding) are burned in by the server.
- **Resume and reporting**: starts at Jellyfin's resume point (or From start). Reports `/Sessions/Playing`, progress every 10 s and on pause/track changes, and `/Sessions/Playing/Stopped`, so the resume point and played state stay in sync with other Jellyfin apps.
- Auth goes in request headers, never in URLs (`api_key` is stripped from server-made URLs), so tokens stay out of caches and logs. Images load through the same client (Coil + OkHttp), so they carry the auth headers too.
- **Not yet tested against a real Jellyfin server**: covered by unit tests with a fake server (MockWebServer) and a local stub server on the phone.

## Nearby share (v0.11)

LocalSend-compatible file sharing on the local network. It works with the official
[LocalSend](https://localsend.org) apps and with Pourya's own **RSend**.

- **Files → Tools → Nearby share**, **Send nearby** in the Files and category multi-select bars, and **Send nearby** in
  the feed's share sheet (a chooser action on Android 14+). Scrollback also shows up as **Nearby share** in other apps' share sheets.
- **Discovery**: multicast announcements on `224.0.0.167:53317` (with a Wi-Fi multicast lock), answered with `POST /register`.
  There is also an HTTP(S) scan of your private /24 subnet for networks that drop multicast, and **Add by address**.
- **Server**: HTTPS on port 53317 with a self-signed certificate (RSA-2048, CN "LocalSend User"), kept in an app-private PKCS#12.
  The fingerprint is the SHA-256 of the DER certificate. The server answers `/api/localsend/v2/info`, `register`, `prepare-upload`, `upload`
  and `cancel`, and runs on [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) (BSD-3-Clause). The whole feature adds about 115 KB to each APK.
- **Client**: OkHttp. The peer's certificate is pinned to the fingerprint it announced. Our certificate is offered as a client certificate.
- **Receiving**: an Accept/Decline dialog (or **Quick save**), an optional **PIN** (5 wrong tries lock out for a minute), and the save location
  `Download/Scrollback` (or a folder you pick). The SHA-256 is checked when the sender includes it, and text messages show in a dialog.
- **Battery and privacy**: the server and discovery run only while the Nearby share screen is open or a transfer is active.
  Transfers run in a `dataSync` foreground service with a progress notification. There is no always-on background server.
- Permissions: `CHANGE_WIFI_MULTICAST_STATE`, `ACCESS_WIFI_STATE`, `FOREGROUND_SERVICE_DATA_SYNC`, and on Android 17+
  **`ACCESS_LOCAL_NETWORK`** (asked the first time you open Nearby share, shown as "Nearby devices"). Apps targeting API 37 can't
  open or accept LAN connections without it. Once granted, it also covers SFTP/SMB and servers on your LAN.

**Credits and licences**
- **RSend** (Pourya's app, `PouryaFA81/rsend`): the protocol logic is a Kotlin port of its Java `lsend` package
  (server, sender, discovery, self-signed identity, name sanitising). It is the same author's code, used with permission.
- **LocalSend** ([localsend/localsend](https://github.com/localsend/localsend), Apache-2.0): the protocol and the app
  this interoperates with. The [protocol spec](https://github.com/localsend/protocol) repository has no licence file. Scrollback
  implements the published v2 protocol for interoperability and contains **no LocalSend source code**.
  "LocalSend" is the name of that project; Scrollback isn't affiliated with it.

## Office (v0.10)

- **Files → Tools → Office**, tap a document in Files (folders and the Documents category), or pick Scrollback in another app's **Open with**.
- **PDF**: Android's built-in `PdfRenderer`. Swipe between pages, pinch or double-tap to zoom, tap the page counter to jump to a page.
  **Search** with highlighted matches works on Android 15+ for PDFs that have a text layer. Password-protected PDFs aren't supported yet.
- **Word (DOCX)**: paragraphs, headings, bold and italic, bulleted and numbered lists, and simple tables. Text can be selected.
- **Excel (XLSX)**: every sheet as a grid with shared strings, inline strings, numbers and booleans. Shows cached formula
  results; number and date formats aren't applied. The grid shows up to 5,000 rows × 200 columns.
- **PowerPoint (PPTX)**: slide titles, text, pictures and speaker notes, in slide order.
- **CSV / TSV**: a table editor. Tap a cell to edit it, long-press a row to insert or delete, and add rows and columns. **Save**
  writes the file back; if the location is read-only, **Save as** makes a copy. Comma, semicolon and tab separators are detected.
- **Text and Markdown** open in the Notes editor.
- **Why no Apache POI:** OOXML files are zips of XML. The viewers read only the parts they need with the platform's
  `XmlPullParser`, so there are no new runtime dependencies. The whole suite adds about 145 KB to each APK; POI would add
  several MB even after R8. Older binary `.doc`/`.xls`/`.ppt` files aren't supported. They open in another app through the open-in icon.

## Convert with VERT (v0.9)

- **Files → Tools → Convert (VERT)**, or select a file in Files and tap **Convert**.
- **Video** (mp4, mkv, mov, avi, webm, …) converts on a **vertd** server. You choose MP4, WebM, MKV, MOV, AVI, GIF,
  animated WebP/APNG, M4V or 3GP, at Fast, Balanced or Quality speed. The app uploads the file, follows
  the job's progress over vertd's websocket, downloads the result to `Download/Scrollback/`, and then confirms
  so the server deletes its copy.
- **Images, audio and documents** open in the VERT web app, which converts them inside the browser, so nothing
  is uploaded.
- **Server:** the default is https://vert.sh with vertd https://vertd.vert.sh (run by the VERT team). There is a preset for
  **https://vert.example.com**; set its vertd URL there, because self-hosted VERT shows it in its settings. You can add an optional
  extra header (`Name: value`) for a vertd behind an auth proxy. It is stored encrypted and only sent to vertd.
- VERT and vertd are separate GPL-3.0 projects by the VERT team (https://github.com/VERT-sh). Scrollback
  only talks to their public HTTP API and includes none of their code.

## Notes and archives (v0.8)

- **Notes** (Files → Tools → Notes, or tap any `.md` / `.txt` file in Files): a Markdown and plain-text editor.
  - Edit | Preview toggle. The preview covers headings, bold/italic/strike, code blocks, quotes, lists and
    task lists, tables, links, `[[wikilinks]]` and `#tags`. Front matter is shown dimmed and never changed.
  - A formatting toolbar for headings, bold, italic, strike, lists, tasks, quotes, code and links.
  - Autosave 1.2 s after you stop typing, and again when you leave. A word count.
  - Works on local folders, SAF folders and SFTP/SMB servers.
  - The notes list uses a folder you pick (an **Obsidian vault works**) or the app's own Notes folder.
- **Archives** (Files → Tools → Archives, or tap an archive in Files):
  - Extract ZIP, TAR, TAR.GZ/BZ2/XZ, 7z and single GZ/BZ2/XZ files, either "here" or into a new folder.
  - Select files and tap **Compress** to make a ZIP.
  - Safe by default: existing files are never overwritten (names get " (1)"), entries that escape the
    folder (zip-slip) are refused, symlinks and devices are skipped, and extraction stops on suspicious
    ratios or sizes (zip bombs). Password-protected archives aren't supported yet.
  - Libraries: Apache Commons Compress (Apache-2.0) and XZ for Java (0BSD).
- **Swipe sub-tabs:** swipe left/right on Library and Music to move between their chips.
  The feed doesn't do this, because horizontal drags there belong to seeking and picture zoom.

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
Since v0.13 the release APKs include libVLC: about 34 MB (arm64-v8a), 30 MB (armeabi-v7a) and 53 MB (universal).
See [ROADMAP.md](ROADMAP.md) for what's next.

## License

Scrollback is free software, released under the **GNU General Public License v3.0 or later**
(`GPL-3.0-or-later`). See [LICENSE](LICENSE) for the full text and [NOTICE](NOTICE).
Copyright (C) 2026 Pourya FA.

**Credits**

- **Scrollback is original code**, written from scratch for this project by Pourya FA. No third-party app
  code is copied; SnapReel (MIT) was only a visual design reference.
- **Nearby share** is a Kotlin implementation of the open **LocalSend v2 protocol**. It was ported from
  the `lsend` package of Pourya's own **RSend** app (same author), and contains **no LocalSend source code**.
  It interoperates with the official LocalSend apps; Scrollback isn't affiliated with LocalSend.
- **Third-party libraries keep their own licenses.** They are all compatible with GPLv3: Apache-2.0
  (AndroidX, Jetpack Compose, Media3, Kotlin, OkHttp, Coil, Haze, sshj, smbj, Apache Commons, Guava), MIT
  (Bouncy Castle, MBassador, SLF4J, Checker Framework), BSD-3-Clause (NanoHTTPD), 0BSD (XZ for Java) and
  LGPL-2.1-or-later (**libVLC**, shipped unmodified as dynamically linked, replaceable `.so` libraries;
  source at [code.videolan.org](https://code.videolan.org/videolan/vlc-android)). The full list with links
  is in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and in the app under **Settings → About →
  Open-source licenses**.

Every source file carries an `SPDX-License-Identifier: GPL-3.0-or-later` header.
