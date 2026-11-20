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
| 0.9 | Convert with VERT: video via vertd (upload → websocket job → download → confirm), other types in the VERT web app; vert.sh default, custom self-hosted instance |
| 0.10 | Office: PDF viewer (swipe, zoom, jump, search), DOCX/XLSX/PPTX viewers via XmlPullParser (no POI, +145 KB), CSV/TSV editor, Open with |
| 0.11 | Nearby share: LocalSend v2 (multicast + subnet scan, HTTPS self-signed + fingerprint pinning, PIN, quick save), ported from RSend; CSV in Documents; recent strip opens in Scrollback |
| 0.12 | Watch: Jellyfin movies, shows → seasons → episodes, collections, Continue watching / Next up, details, search; Media3 direct play with HLS-transcode fallback (PlaybackInfo + device profile); audio/subtitle tracks (embedded, external, burn-in); resume and progress reporting; played/favorite sync |
| 0.14 | Server connections and licensing: own redirect follower (sign-in survives http→https and same-server hops, WebDAV verbs keep method), canonical address at sign-in, Login Flow v2 kept on the working address, per-server alternate address with automatic fallback, Test connection; music "Hide duplicates across servers" (view-only merge, preferred source, Play from…); GPL-3.0-or-later, third-party notices, in-app licenses |
| 0.13 | Scrollback Player (libVLC 3.7.7): Open with for video/*, Files and feed integration, automatic fallback from Media3, gestures, aspect/zoom, audio/subtitle tracks, external subs, delays, subtitle styling, speed 0.25–4×, A-B repeat, HW/SW toggle, screen lock, PiP, background audio, resume, folder playlist. +20–24 MB per release APK |
| 0.14.1 | Privacy clean-up: no personal domains or setup details in the repo; VERT server picker is vert.sh or **Custom** (your own VERT/vertd URL, stored only on the device); `scripts/check-private.sh` guard |

## Phase A: File-manager add-ons (0.6–0.10) ✅

_Release order (Nov 2026): 0.8 Notes+Archives · 0.9 VERT · 0.10 Office · 0.11 Nearby share · 0.12 Jellyfin video · 0.13 movie player._

The Files tab gets one **Tools** card (Remote, Notes, Archives, Convert, Office) so the tab bar stays at five.

| Version | Add-on | Notes |
|---|---|---|
| 0.6.0 ✅ | **In-app updates + signed releases** | Runs only when you tap "Check for update". Reads GitHub Releases, verifies the package name and signing key, installs with PackageInstaller. Optional encrypted token while the repo is private. |
| 0.7.0 ✅ | **SFTP + SMB** | sshj (password or key, host key pinned on first use) and smbj (SMB2/3). Browse, copy both ways, stream into the feeds through the media-source layer. Credentials stay encrypted in the Keystore. SMB reads still need confirming on real Samba/Windows. |
| 0.8.0 ✅ | **Notes + Archives** (merged) | Markdown/plain-text editor with preview for .md and .txt. Obsidian-friendly: wikilinks, front matter left untouched, autosave. |
| ~~0.9.0~~ | **Archives**: shipped in 0.8.0 | Zip create/extract; tar, tar.gz and 7z extract (Commons Compress plus XZ). Guards against zip-slip and zip bombs. |
| 0.9.0 ✅ | **Convert (VERT)** | Video goes through the vertd API (upload, then websocket job, then download). Images, audio and docs open in the VERT web app, which converts them client-side. Default https://vert.sh; a custom self-hosted VERT/vertd URL can be entered and is stored only on the device. |
| 0.10.0 ✅ | **Office lite** | PDF via PdfRenderer; text and tables from DOCX/XLSX/PPTX via zip+XML (no POI, to keep the APK small); CSV table editor. |

## Phase A2: Watch (0.12–0.13)

| Version | What | Notes |
|---|---|---|
| 0.12.0 ✅ | **Jellyfin video (Watch)** | Movies, Shows → Seasons → Episodes and Collections, with posters. Continue watching and resume. Played state and progress reported back (`/Sessions/Playing`, `/Progress`, `/Stopped`). Direct play when the device can decode it, otherwise Jellyfin HLS transcoding. Subtitles (embedded, external, burn-in fallback). Lives as a **Watch** entry at the top of Library (its own screen with Home / Movies / Shows / Collections pills), not a sixth tab. Tested against a fake server only (unit tests with MockWebServer and a local stub on the phone); not yet against a real Jellyfin. |
| 0.13.0 ✅ | **Full movie player** | A long-form player, separate from the swipe feed. Gestures: brightness/volume/seek swipes, double-tap seek, pinch zoom. Aspect and crop modes. Subtitles: embedded plus external SRT/ASS, with styling and delay. Audio track and audio delay. HW/SW decoding toggle, speed, A-B repeat, PiP, background audio, resume, lock controls. "Open with Scrollback" for video files. The feed keeps Media3 and hands codecs it can't play to this player. |

**Player backend decision (confirmed in 0.13: libVLC):**

| Option | Codecs | Size (arm64 only) | License | Fit |
|---|---|---|---|---|
| Media3 + FFmpeg decoder extension | Adds audio (AC3/EAC3/DTS/TrueHD, etc.); video is still mostly MediaCodec | +2–4 MB | LGPL FFmpeg build (Apache module) | Lightest. Keeps one player stack, but there is no ASS renderer and no software HEVC/AV1 fallback beyond the device's decoders. |
| **libmpv (mpv-android libs)** | Everything FFmpeg decodes, plus libass subtitles, HW + SW decoding, A-B loop, audio and sub delay built in | ~+25–30 MB | mpv is LGPL-2.1+ when built without GPL parts. mpv-android's prebuilt FFmpeg may pull in GPL parts, so build or verify an LGPL configuration. | Closest to MX/VLC quality, and best for ASS subtitles. |
| libVLC (org.videolan.android:libvlc-all) | Everything, plus libass and casting | ~+30–35 MB | LGPL-2.1 | Mature Maven artifact and simple API. Slightly heavier, and less control over rendering. |

Plan: use **libVLC** via Maven (LGPL, published AAR, no NDK build on this machine), with an ABI split so arm64-v8a builds carry only arm64 libraries. Media3 stays for the feed and music. LGPL keeps the repository free to pick any license, as long as the shared libraries stay dynamically linked and replaceable. Revisit libmpv if libVLC's subtitle styling or size is a problem.

**Result (0.13.0):** libVLC `libvlc-all` 3.7.7, LGPL-2.1+, dynamically linked and unmodified. Measured release APK sizes: arm64-v8a 10.6 → 33.9 MB, armeabi-v7a 10.6 → 30.3 MB, universal 10.7 → 53.4 MB (x86/x86_64 excluded). **The current roadmap (phases A–A2) is complete**; 0.14 went to connections, duplicates and the license, so A3 polish moves to 0.15.

## Phase A2.5: Nearby share (0.11) ✅ shipped 0.11.0

**LocalSend-compatible sharing**, based on Pourya's RSend (`PouryaFA81/rsend`, whose Android core is
the pure-JDK `lsend` package). It follows LocalSend protocol v2: multicast discovery on
224.0.0.167:53317, plus HTTPS `/api/localsend/v2/register`, `prepare-upload`, `upload` and `cancel`.
The certificate is self-signed, the fingerprint is the SHA-256 of the DER cert in lowercase hex, and a PIN is optional.
Scrollback, RSend and official LocalSend can then exchange files.
- "Nearby share" from file-manager multi-select and from the share action.
- Receive screen: accept or decline, choose a save location, a quick-save toggle and a progress view.
- The server runs only while the Nearby screen is open or a transfer is active. There is no always-on background server.
- Verified on a Galaxy A54 (Android 17) against the official LocalSend 1.18.2 desktop app: discovery, phone → desktop and desktop → phone transfers.
- Licensing: the LocalSend app is Apache-2.0. The `localsend/protocol` spec repo has no license file,
  so it is implemented from the public spec for interoperability and no LocalSend code is copied.
  RSend code is Pourya's own. Both are credited in the README.

## Phase A2.6: Connections, duplicates and license (0.14) ✅ shipped 0.14.0

Self-hosted servers (Nextcloud, Nextcloud Music, Navidrome, Jellyfin) can have trouble with redirects,
especially over VPN, and a Nextcloud Music and a Navidrome often share one music folder.
- **Redirects:** OkHttp's built-in follower is replaced (see README). It used to drop the sign-in on
  http→https and on host changes, and to turn 301/302 POSTs into GETs.
- **Alternate address + Test connection** per server. Login Flow v2 URLs stay on the address that worked.
- **Hide duplicates across servers:** a view-level merge with a preferred-source order. Nothing is deleted anywhere.
- **License:** GPL-3.0-or-later, SPDX headers, THIRD_PARTY_NOTICES.md and Settings → About → Open-source licenses.
- **Checked on the A54 (2026-11-20):** a Jellyfin and a Navidrome library that share one music folder are
  indexed, and with Hide duplicates on each shared song shows once with a "2 sources" tag.
  All six codec clips play in Scrollback Player (H.264, HEVC, VP9 on Exynos hardware decoders; AV1 and MPEG-2 in software),
  and ASS subtitles render, both embedded and sidecar.
- Still to confirm: sign-in and browsing over a full-tunnel VPN. A VPN that routes even LAN traffic through the
  tunnel can't reach a home server, so Test connection times out there. It needs a LAN bypass / split tunnel in the
  VPN app plus the server's LAN address as the alternate address, or a retest with the VPN off.

## 0.15: Feed comfort and Android 17 (done)

**Result (0.15.0):** auto scroll (videos at the end, pictures after 3/5/10 s; pauses while touching,
holding or with a sheet open), press-and-hold 2× in the feed, Scrollback Player and Watch, keep screen on
while playing, a Saved-order folder chooser, a persisted feed position per tab (seed, item and playback
time survive lock, PIN re-lock and process death; the shuffle is now stable when videos are added),
the local-network permission requested for every server connection, the real HW/SW decoder label,
and SECURITY.md with a contact address.

## Phase A3: Polish (0.16)

1. **Swipe between sub-tabs.** A HorizontalPager synced with the segmented/chip tabs: Library
   (Liked, Playlists, History, Search…), the Feed's Random|Saved, Music (Songs, Artists, Albums, Folders) and
   the Watch sections. It is deliberately left out of the vertical feed, the video player, file lists with swipe actions,
   the bottom nav and image zoom, where it would fight existing gestures.
2. **Size discipline.** R8 plus resource shrinking, ABI splits (arm64-v8a, armeabi-v7a, universal) since
   0.6.0, and a per-release size table. Native players (libVLC) go in per-ABI APKs only.
3. ~~**Player decoder label.**~~ Done in 0.15: the label checks the phone's hardware decoders for the
   video's format and size, so AV1 and MPEG-2 on the A54 show SW.

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

1. **Make the repo public.** The license is chosen: GPL-3.0-or-later (since 0.14). Run `scripts/check-private.sh` first; older history may still need a clean-up. The anonymous update check works once it is public.
2. **F-Droid / IzzyOnDroid** build flavour without MANAGE_EXTERNAL_STORAGE (SAF only), plus reproducible builds.
3. **Baseline profiles + R8** tuning for startup. Keep the release APK under about 20 MB.
4. **Localisation.** Persian first (RTL already supported), and Persian calendar dates (Aves added this).
5. **Backup and restore** of settings, playlists, accounts (no secrets) and likes as a JSON export.
6. **Crash-free metrics without telemetry**: a local crash log with a "share log" button.

## Principles

- Offline first. The network is used only for servers you add, or when you tap something.
- No secrets in the DB, caches or logs. Credentials stay Keystore-encrypted and are applied on the wire.
- Each phase is its own version: bump, build, test, archived APK, tag, GitHub Release, wiki update.
