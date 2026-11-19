# Scrollback roadmap

Scrollback is an offline-first, TikTok-style player for your own media: a swipe feed for videos and
pictures, a music player, a file manager and optional self-hosted servers (Nextcloud, Subsonic,
Jellyfin, SFTP, SMB). This roadmap is ordered by priority. Every phase ships as its own tagged
version with a signed GitHub Release.

_Research (Nov 2026) looked at Aves, Fossify, Material Files, Solid Explorer, CX File Explorer,
Symfonium, Finamp, NewPipe and TikTok._

## Shipped

| Version | What |
|---|---|
| 0.1 | Vertical feed, folders through SAF, saved items, playlists, PIN and hidden items |
| 0.2 | File manager: categories, browser, copy, move, rename, delete, share |
| 0.3 | Nextcloud: WebDAV streaming, downloads, library sync |
| 0.4 | Music player, Subsonic/OpenSubsonic and Jellyfin, background playback |
| 0.5 | IA rework: five tabs, Settings behind the gear, One UI glass chrome |
| 0.6 | In-app updates (on tap only), dedicated release key, R8 + ABI splits (~5.7 MB per APK) |
| 0.7 | SFTP + SMB network storage under Files → Tools, TOFU host-key pinning, streaming into feeds |
| 0.8 | Notes (Markdown editor + preview, Obsidian-friendly) and Archives (extract zip/tar/gz/bz2/xz/7z, compress to zip); swipe between Library and Music sub-tabs |
| 0.10 | Office: PDF viewer (swipe, zoom, jump, search), DOCX/XLSX/PPTX viewers via XmlPullParser (no POI, +145 KB), CSV/TSV editor, Open with |
| 0.11 | Nearby share: LocalSend v2 (multicast + subnet scan, HTTPS self-signed + fingerprint pinning, PIN, quick save), ported from RSend; CSV in Documents; recent strip opens in Scrollback |
| 0.9 | Convert with VERT: video via vertd (upload → websocket job → download → confirm), other types in the VERT web app; vert.sh default, self-hosted preset |

## Phase A: File-manager add-ons (0.6–0.10, in progress)

_Release order (Nov 2026): 0.8 Notes+Archives · 0.9 VERT · 0.10 Office · 0.11 Nearby share · 0.12 Jellyfin video · 0.13 movie player._

The Files tab gets one **Tools** card (Remote, Notes, Archives, Convert, Office) so the tab bar stays at five.

| Version | Add-on | Notes |
|---|---|---|
| 0.6.0 ✅ | **In-app updates + signed releases** | Runs only when you tap "Check for update". Reads GitHub Releases, verifies the package name and signing key, installs with PackageInstaller. Optional encrypted token while the repo is private. |
| 0.7.0 ✅ | **SFTP + SMB** | sshj (password or key, host key pinned on first use) and smbj (SMB2/3). Browse, copy both ways, stream into the feeds through the media-source layer. Credentials stay encrypted in the Keystore. SMB reads still need confirming on real Samba/Windows. |
| 0.8.0 ✅ | **Notes + Archives** (merged) | Markdown/plain-text editor with preview for .md and .txt. Obsidian-friendly: wikilinks, front matter left untouched, autosave. |
| ~~0.9.0~~ | **Archives**: shipped in 0.8.0 | Zip create/extract; tar, tar.gz and 7z extract (Commons Compress plus XZ). Guards against zip-slip and zip bombs. |
| 0.9.0 ✅ | **Convert (VERT)** | Video goes through the vertd API (upload, then websocket job, then download). Images, audio and docs open in the VERT web app, which converts them client-side. Default https://vert.sh, with a preset for vert.example.com. |
| 0.10.0 ✅ | **Office lite** | PDF via PdfRenderer; text and tables from DOCX/XLSX/PPTX via zip+XML (no POI, to keep the APK small); CSV table editor. |

## Phase A2: Watch (0.12–0.13)

| Version | What | Notes |
|---|---|---|
| 0.13.0 | **Full movie player** | A long-form player, separate from the swipe feed. Gestures: brightness/volume/seek swipes, double-tap seek, pinch zoom. Aspect and crop modes. Subtitles: embedded plus external SRT/ASS, with styling and delay. Audio track and audio delay. HW/SW decoding toggle, speed, A-B repeat, PiP, background audio, resume, lock controls. "Open with Scrollback" for video files. The feed keeps Media3 and hands codecs it can't play to this player. |
| 0.12.0 | **Jellyfin video (Watch)** | Movies, Shows → Seasons → Episodes and Collections, with posters. Continue watching and resume. Played state and progress reported back (`/Sessions/Playing`, `/Progress`, `/Stopped`). Direct play when the device can decode it, otherwise Jellyfin HLS transcoding. Subtitles (embedded, external, burn-in fallback). Lives as a **Watch** section in Library (and a Library segment), not a sixth tab. |

**Player backend decision (to confirm while building 0.13):**

| Option | Codecs | Size (arm64 only) | License | Fit |
|---|---|---|---|---|
| Media3 + FFmpeg decoder extension | Adds audio (AC3/EAC3/DTS/TrueHD, etc.); video is still mostly MediaCodec | +2–4 MB | LGPL FFmpeg build (Apache module) | Lightest. Keeps one player stack, but there is no ASS renderer and no software HEVC/AV1 fallback beyond the device's decoders. |
| **libmpv (mpv-android libs)** | Everything FFmpeg decodes, plus libass subtitles, HW + SW decoding, A-B loop, audio and sub delay built in | ~+25–30 MB | mpv is LGPL-2.1+ when built without GPL parts. mpv-android's prebuilt FFmpeg may pull in GPL parts, so build or verify an LGPL configuration. | Closest to MX/VLC quality, and best for ASS subtitles. |
| libVLC (org.videolan.android:libvlc-all) | Everything, plus libass and casting | ~+30–35 MB | LGPL-2.1 | Mature Maven artifact and simple API. Slightly heavier, and less control over rendering. |

Plan: use **libVLC** via Maven (LGPL, published AAR, no NDK build on this machine), with an ABI split so arm64-v8a builds carry only arm64 libraries. Media3 stays for the feed and music. LGPL keeps the repository free to pick any license, as long as the shared libraries stay dynamically linked and replaceable. Revisit libmpv if libVLC's subtitle styling or size is a problem.

## Phase A2.5: Nearby share (0.11) ✅ shipped 0.11.0

**LocalSend-compatible sharing**, based on Pourya's RSend (`PouryaFA81/rsend`, whose Android core is
the pure-JDK `lsend` package). It follows LocalSend protocol v2: multicast discovery on
224.0.0.167:53317, plus HTTPS `/api/localsend/v2/register`, `prepare-upload`, `upload` and `cancel`.
The certificate is self-signed, the fingerprint is the SHA-256 of the DER cert in lowercase hex, and a PIN is optional.
Scrollback, RSend and official LocalSend can then exchange files.
- "Nearby share" from file-manager multi-select and from the share action.
- Receive screen: accept or decline, choose a save location, a quick-save toggle and a progress view.
- The server runs only while the Nearby screen is open. There is no always-on background server.
- Licensing: the LocalSend app is Apache-2.0. The `localsend/protocol` spec repo has no license file,
  so it is implemented from the public spec for interoperability and no LocalSend code is copied.
  RSend code is Pourya's own. Both are credited in the README.

## Phase A3: Polish (0.14)

1. **Swipe between sub-tabs.** A HorizontalPager synced with the segmented/chip tabs: Library
   (Liked, Playlists, History, Search…), the Feed's Random|Saved, Music (Songs, Artists, Albums, Folders) and
   the Watch sections. It is deliberately left out of the vertical feed, the video player, file lists with swipe actions,
   the bottom nav and image zoom, where it would fight existing gestures.
2. **Size discipline.** R8 plus resource shrinking, ABI splits (arm64-v8a, armeabi-v7a, universal) since
   0.6.0, and a per-release size table. Native players (libVLC) go in per-ABI APKs only.

## Phase B: Feed and gallery quality (next)

Ideas from TikTok, Aves and NewPipe:
1. **Resume and watch history.** Continue where you left off in each feed. Optionally a "not interested" signal that down-ranks a folder or creator.
2. **Smart feeds / dynamic albums** (Aves). Saved filter combinations such as type, folder, date range, duration, rating, tags or "unwatched", pinned as a feed.
3. **Tags and ratings** on media, with a quick picker on long-press.
4. **Map view** for geotagged pictures (Aves): OSM raster tiles, opt-in network.
5. **Picture-in-picture and background audio for videos** (NewPipe).
6. **Double-tap to like** and a heart animation. A comments/notes overlay per item, stored locally.
7. **Slow-motion and speed ramps.** Per-item speed memory.
8. **Trash / recycle bin** with a 30-day restore (CX, Aves, Fossify). Safer than instant delete.

## Phase C: Music depth (Symfonium, Finamp)

1. **Offline cache rules.** Cache a playlist or album for offline use, auto-cache favourites, a playback cache for flaky connections.
2. **Gapless playback, ReplayGain, sleep timer**, and a lyrics view (synced LRC from the file, a sidecar or OpenSubsonic `getLyricsBySongId`).
3. **Multiple queues**, and an audiobook mode with per-book resume and speed.
4. **Android Auto** (MediaLibraryService browse tree) and home-screen widgets.
5. **Jellyfin Quick Connect**, plus OpenSubsonic API keys and transcoding.
6. **Instant mix / "radio"** from a song (Jellyfin InstantMix, Subsonic getSimilarSongs2).

## Phase D: File-manager power (Solid Explorer, Material Files, CX)

1. **Dual-pane on wide screens / DeX** with drag between panes (local ↔ SFTP ↔ SMB ↔ Nextcloud).
2. **Background transfer queue** with resume, conflict handling (skip, replace, keep both) and progress notifications.
3. **Storage analyzer**: a large-files/folders treemap that reuses the duplicate finder.
4. **Batch rename** with patterns (date, counter, regex) and a preview.
5. **Bookmarks, recent locations and home-screen shortcuts.**
6. **WebDAV/FTP generic accounts** and a LAN share via a built-in web server (Solid's "web file sharing").
7. **Encrypted vault folder** (AES-GCM, biometric unlock), building on the existing PIN.

## Phase E: Platform and distribution

1. **Make the repo public** and choose a license (GPL-3.0 fits Aves and Material Files and keeps it F-Droid friendly). The anonymous update check works once it is public.
2. **F-Droid / IzzyOnDroid** build flavour without MANAGE_EXTERNAL_STORAGE (SAF only), plus reproducible builds.
3. **Baseline profiles + R8** tuning for startup. Keep the release APK under about 20 MB.
4. **Localisation.** Persian first (RTL already supported), and Persian calendar dates (Aves added this).
5. **Backup and restore** of settings, playlists, accounts (no secrets) and likes as a JSON export.
6. **Crash-free metrics without telemetry**: a local crash log with a "share log" button.

## Principles

- Offline first. The network is used only for servers you add, or when you tap something.
- No secrets in the DB, caches or logs. Credentials stay Keystore-encrypted and are applied on the wire.
- Each phase is its own version: bump, build, test, archived APK, tag, GitHub Release, wiki update.
