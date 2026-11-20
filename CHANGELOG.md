# Changelog

All notable changes to Scrollback are listed here, newest first. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and Scrollback uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). While the version is below 1.0, things
may still change between minor versions.

Ready-to-paste release notes for each version are in [docs/releases](docs/releases).

## [Unreleased]

### Coming soon

See [ROADMAP.md](ROADMAP.md) for the full plan. Next up (0.15, polish):

- Swipe between sub-tabs in more places: the Feed's Random | Saved and the Watch sections
- Scrollback Player's top bar shows whether the hardware or software decoder is really in use
- Keeping the download size in check

## [0.14.0] - 2026-11-20

### Added

- **Alternate address for each server.** Nextcloud, Subsonic / OpenSubsonic and Jellyfin servers
  can have a second address, for example a LAN address at home and a VPN address away. Scrollback
  uses the address that worked last and switches automatically when it can't connect. Set it under
  **Settings → Servers → (your server) → Alternate address**.
- **Test connection** for Nextcloud, Subsonic / OpenSubsonic and Jellyfin servers. It checks each
  address, lists any redirects, and explains in plain words what went wrong (redirect loops, names
  that can't be found, timeouts, certificate problems, a rejected sign-in). The reachability
  check for each address gives up after 20 seconds instead of hanging on a VPN that drops traffic. If redirects end on another address, you can **Use** it with one tap.
- **Hide duplicates across servers** (music). When two servers share one music folder, turn it on in
  **Settings → Music** or **Settings → Servers** to show each song once. Merged songs show a small
  "2 sources" label; long-press (or **More → Play from…**) to pick a copy. Choose which source plays
  first on the Servers screen. A like applies to every copy. This only changes what you see:
  nothing is deleted anywhere.
- **Open-source license.** Scrollback is now free software under the **GPL-3.0-or-later**. The
  repository has [LICENSE](LICENSE), [NOTICE](NOTICE) and
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), and the app has **Settings → About →
  Open-source licenses**.

### Changed

- **Redirects keep your sign-in.** Scrollback now follows server redirects itself. Your sign-in is
  kept when a server moves you from http to https or to another path on the same server. It isn't
  passed on to a different host, or from https down to plain http, unless that address is one you
  set up for the server.
- Adding a server now saves the address the server really answers on, after following its
  redirects. Nextcloud sign-in stays on the address that worked.
- Long second lines in list rows now end with an ellipsis instead of being cut off.

### Notes

- If your phone's VPN sends all traffic through the tunnel, your home servers may be unreachable.
  Turn on LAN bypass or split tunneling in the VPN app, or set the server's alternate address.
- Downloads: three signed APKs (arm64-v8a, armeabi-v7a, universal) plus `SHA256SUMS`.

## [0.13.0] - 2026-11-20

### Added

- **Scrollback Player**, a full movie player built on libVLC. It plays almost any video, including
  HEVC, VP9, AV1, MPEG-2, VC-1, AVI, WMV and MKV, with ASS/SSA subtitles in their original styling.
  - Opens from **Open with → Scrollback Player** for any video, from a tap on a video in **Files**
    (the rest of the folder becomes the playlist), from **Play** in the Files selection bar, and from
    **Play in full player** in a feed video's Info sheet.
  - If the feed can't play a video's format, it opens in Scrollback Player automatically.
  - Swipe for brightness (left), volume (right) and seeking (sideways); double-tap the sides to skip
    10 seconds; pinch to zoom.
  - Aspect modes (Fit, Stretch, Crop, 16:9, 4:3, Original), rotation lock and frame capture to
    `Pictures/Scrollback`.
  - Audio and subtitle tracks, external subtitle files, audio and subtitle delay, and subtitle size,
    colour and background box.
  - Speed from 0.25× to 4×, A-B repeat, previous and next, resume, a hardware/software decoding
    switch, screen lock, picture-in-picture and background audio.
  - Works with local files, Nextcloud and SFTP/SMB servers.
- Friendlier error messages for Jellyfin.

### Changed

- The APKs are larger, because they now include libVLC: about 34 MiB (arm64-v8a), 30 MiB
  (armeabi-v7a) and 53 MiB (universal).
- Tapping a video in Files now opens it in Scrollback Player. Use **Open with** in the selection
  bar for another app.

### Notes

- libVLC is LGPL-2.1-or-later and is included unmodified as separate shared libraries.
- Scrollback Player isn't available on x86 devices.

## [0.12.0] - 2026-11-20

### Added

- **Watch**, at the top of the **Library** tab: browse and play movies and shows from your own
  Jellyfin server.
  - Uses the Jellyfin account from **Settings → Servers**, including one you added for music. With
    more than one, switch with the ⇄ button.
  - **Home** shows Continue watching, Next up, Recently added and Favorites. **Movies**, **Shows**
    and **Collections** are poster grids; swipe between them. **Search** finds movies, shows,
    episodes and collections.
  - Details pages with backdrop, year, runtime, rating, genres, overview and cast. Shows list
    seasons and episodes with progress.
  - **Mark played / unplayed** and **Favorite** are saved on your server.
  - Plays the original file when your phone can, and asks Jellyfin to convert it when it can't. The
    top bar shows **Direct play** or **Transcoding**.
  - Audio and subtitle tracks, including external subtitle files.
  - Resumes where you left off and reports progress back to Jellyfin.

### Notes

- Watch was tested with automated tests and a simulated Jellyfin server, not yet with a real one.

## [0.11.0] - 2026-11-20

### Added

- **Nearby share.** Send and receive files over Wi-Fi with phones and computers running LocalSend
  or RSend. No internet or account needed.
  - Open it from **Files → Tools → Nearby share**, tap **Send nearby** on selected files in Files,
    or pick **Nearby share** in any app's share sheet.
  - Devices on the same Wi-Fi show up on their own; use **Add by address** if one doesn't.
  - Incoming files ask you to **Accept** or **Decline**, unless you turn on **Quick save**.
  - Received files go to `Download/Scrollback` unless you choose another folder. You can set a
    device name and an optional receive PIN.
  - Transfers are encrypted (HTTPS). Scrollback only listens while the Nearby share screen is open or
    a transfer is running, and shows a notification while it works.

### Changed

- CSV and TSV files now appear under **Documents** in Files, and recent documents open in
  Scrollback.

### Notes

- On Android 17, the first time you open Nearby share, Scrollback asks for the **Nearby devices**
  (local network) permission.
- Nearby share follows the public LocalSend v2 protocol. It is a Kotlin port of RSend by the same
  author, and contains no LocalSend code.

## [0.10.0] - 2026-11-19

### Added

- **Office**: **Files → Tools → Office**, a tap on a document in Files, or **Open with Scrollback**
  from other apps.
  - **PDF**: swipe pages, pinch or double-tap to zoom, jump to a page, and search with highlighted
    matches (Android 15 and newer, for PDFs with a text layer).
  - **Word (DOCX)**: headings, bold and italic, lists and tables, with selectable text.
  - **Excel (XLSX)**: every sheet as a grid, with cached formula results.
  - **PowerPoint (PPTX)**: slide titles, text, pictures and speaker notes.
  - **CSV / TSV**: edit cells, insert and delete rows, add columns, **Save** or **Save as…**.
  - Text and Markdown files open in the notes editor.

### Notes

- Word, Excel and PowerPoint files are view-only. Old `.doc`, `.xls` and `.ppt` files aren't
  supported.

## [0.9.0] - 2026-11-19

### Added

- **Convert (VERT)**: **Files → Tools → Convert (VERT)**, or **Convert** on a selected file in Files.
  - Videos convert on a vertd server to MP4, WebM, MKV, MOV, AVI, GIF, WebP, APNG, M4V or 3GP, at
    Fast, Balanced or Quality. You see live progress, the result is saved to
    `Download/Scrollback/`, and the server copy is then deleted.
  - Images, audio and documents open in the VERT web app, which converts them in your browser.
  - The default server is vert.sh. You can choose another VERT instance, add an optional
    encrypted extra header, and **Test** the connection.
  - Clear messages when you're offline or the server can't be reached; conversions can be
    cancelled.

## [0.8.0] - 2026-11-19

### Added

- **Notes**: a Markdown and plain-text editor with **Edit** and **Preview**, a formatting toolbar,
  autosave and a word count. Tap any `.md` or `.txt` file in Files, or use **Files → Tools → Notes**
  and pick a folder, such as an Obsidian vault.
- **Archives**: extract ZIP, TAR, TAR.GZ/BZ2/XZ, 7z and GZ/BZ2/XZ files, here or into a new folder.
  Select files and tap **Compress** to make a ZIP. Existing files are never overwritten, and unsafe
  archives are refused. Works in local, Android-picker and SFTP/SMB folders.
- Swipe left or right to move between the **Library** and **Music** sub-tabs.

## [0.7.0] - 2026-11-19

### Added

- **Network storage**: **Files → Tools → Network storage** adds **SFTP** servers (password or
  private key) and **SMB2/3** shares.
  - Browse, create folders, rename, delete and share files on the server. Opening a file downloads it
    to the app's cache first.
  - **Add to feeds** streams the server's videos and pictures into your feeds, with seeking.
  - SFTP host keys are confirmed the first time and then pinned, so Scrollback refuses to connect if
    a server's key changes.
  - Passwords and keys are stored encrypted with an Android Keystore key.

### Notes

- SFTP was tested end to end. SMB listing was tested, but reading files still needs testing on a
  real Samba or Windows share.

## [0.6.0] - 2026-11-19

### Added

- **In-app updates.** **Settings → Updates → Check for update** looks for a new version on GitHub
  Releases, but only when you tap it. There are no background checks.
  - Picks the APK that matches your phone's processor.
  - Checks that the download is Scrollback and is signed with the same key before installing.
  - Installs through Android, which asks you to confirm.
- **Optional GitHub token** under **Settings → Updates**, needed only while the repository is
  private. It is encrypted on your phone and only sent to GitHub's API.
- **Signed release APKs** on the [Releases page](https://github.com/PouryaFA81/Scrollback/releases):
  **arm64-v8a**, **armeabi-v7a** and **universal**, plus a universal debug APK and a `SHA256SUMS`
  file to check your download.
- **[Roadmap](ROADMAP.md)** with the planned versions in order of priority.

### Changed

- Release APKs are much smaller than debug builds, because unused code and resources are removed.
- **Settings → About** now reads: "Offline-first and private. Nothing leaves this device unless you
  add a server or tap Check for update."

### Notes

- New permission: **Install unknown apps** (`REQUEST_INSTALL_PACKAGES`), used only to install
  updates you ask for.
- Release builds use the app id `app.scrollback`; debug builds use `app.scrollback.debug`. They are
  separate apps with separate data, so installing the release APK doesn't replace a debug install.

## [0.5.0] - 2026-11-19

### Changed

- **Simpler navigation.** The bottom bar now has at most five tabs: **Feed**, **Pictures**,
  **Music**, **Files** and **Library**. When Music is turned off, there are four.
- **One Feed tab.** Random and Saved are now a **Random | Saved** switch at the top of the Feed
  tab, with the reshuffle or newest/oldest button right next to it.
- **Settings moved to a gear** at the top of the screen, instead of being a tab.
- The music mini player floats above the bottom bar on every tab except the Feed.

### Added

- **Duplicates** is now also in the Library.
- **Hidden items** in the Library is always listed when you have a PIN. When locked, tapping it
  asks for your PIN.

## [0.4.0] - 2026-11-19

### Added

- **Music tab** with Songs, Artists, Albums, Folders and Playlists, plus Liked songs, Recently
  played and search.
- **Background playback** with a media notification and lock-screen controls.
- A **mini player**, a **full player** with artwork, seek bar, like and add-to-playlist, and a
  **queue** (play next, add to queue, reorder, remove). Shuffle and repeat (all or one).
- Music from your **local folders** (with tags and embedded cover art) and from **Nextcloud**
  folders.
- **Subsonic / OpenSubsonic** servers, such as Navidrome, Nextcloud Music, Airsonic and Gonic.
  Stars on the server and likes in Scrollback stay in sync.
- **Jellyfin** music libraries. Favourites and likes stay in sync. Only an access token is kept,
  never your password.
- **Show Music** setting to hide the Music tab.
- Videos pause your music while they play.

### Notes

- Music and music servers are new and still in early testing.

## [0.3.0] - 2026-11-19

### Added

- **Nextcloud** in **Settings → Servers**. Sign in on your Nextcloud's own login page; Scrollback
  receives an app password that is stored encrypted on your phone and can be revoked at any time.
- **Browse your Nextcloud** and add any folder as a Scrollback source. Its videos and pictures
  **stream** into the same feeds, with a cache for smoother replays.
- **Download** a copy of a Nextcloud item to **Download/Scrollback** for offline playing and
  sharing.
- **Library sync**: likes, hidden items, notes, tags, history and playlists are kept in sync through
  one file in your Nextcloud. The most recent change wins.

### Changed

- Scrollback now has the **internet** and **network state** permissions. They are only used for the
  servers you add; the app stays fully usable offline.

### Notes

- Nextcloud support is new and still in early testing.

## [0.2.0] - 2026-11-19

### Added

- **Files tab**, a built-in file manager: storage summary, categories, recent files, breadcrumbs,
  list or grid view, sorting, multi-select, copy, move, rename, delete, new folder, search, share,
  open with, file info, and **Add source** to add any folder to Scrollback.
- Optional **All files access** to browse your whole internal storage. Scrollback works fully
  without it, using the folders you grant.

### Changed

- The bottom navigation bar now looks the same on every tab and over videos.

### Notes

- The file manager is new and still in early testing.

## [0.1.0] - 2026-11-19

First version. This release was not tagged in the repository.

### Added

- **Folders**: add as many folders as you like through Android's folder picker; subfolders are
  scanned too.
- **Random** feed with reshuffle, and **Saved** feed sorted newest or oldest first.
- **Pictures** grid with a full-screen viewer.
- **Library** with **Liked**, **Playlists** (create, rename, delete, reorder, play), **History**
  with resume, and **Search** across names, folders, notes and tags, with tag chips.
- Feed buttons for **like** (or double-tap), **save to playlist**, **notes and tags**, **share**
  and **info**, plus **hide** and **delete** in the Info sheet.
- **Playback speed** from 0.5× to 2×, and **loop each video** or **auto-advance**.
- **PIN lock** that re-locks after 30 seconds in the background, **hidden items**, and **Block
  screenshots**.
- **Duplicate finder** that finds byte-identical files and deletes extra copies only after you
  confirm.
- Works completely offline: this version had no internet permission.

[Unreleased]: https://github.com/PouryaFA81/Scrollback/compare/v0.14.0...HEAD
[0.14.0]: https://github.com/PouryaFA81/Scrollback/compare/v0.13.0...v0.14.0
[0.13.0]: https://github.com/PouryaFA81/Scrollback/compare/v0.12.0...v0.13.0
[0.12.0]: https://github.com/PouryaFA81/Scrollback/compare/v0.11.0...v0.12.0
[0.11.0]: https://github.com/PouryaFA81/Scrollback/compare/v0.10.0...v0.11.0
[0.10.0]: https://github.com/PouryaFA81/Scrollback/compare/v0.9.0...v0.10.0
[0.9.0]: https://github.com/PouryaFA81/Scrollback/compare/v0.8.0...v0.9.0
[0.8.0]: https://github.com/PouryaFA81/Scrollback/compare/v0.7.0...v0.8.0
[0.7.0]: https://github.com/PouryaFA81/Scrollback/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/PouryaFA81/Scrollback/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/PouryaFA81/Scrollback/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/PouryaFA81/Scrollback/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/PouryaFA81/Scrollback/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/PouryaFA81/Scrollback/compare/d83cf5a...v0.2.0
[0.1.0]: https://github.com/PouryaFA81/Scrollback/commits/d83cf5a
