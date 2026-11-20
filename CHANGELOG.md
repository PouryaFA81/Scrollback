# Changelog

All notable changes to Scrollback are listed here, newest first. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and Scrollback uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). While the version is below 1.0, things
may still change between minor versions.

Ready-to-paste release notes for each version are in [docs/releases](docs/releases).

## [Unreleased]

### Coming soon

- APK downloads on the Releases page, with signed release builds
- Jellyfin videos in the feeds
- Scrobbling for Subsonic servers
- Lyrics
- A Google Play-friendly version without All files access
- File conversion with VERT
- In-app updates

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

[Unreleased]: https://github.com/PouryaFA81/Scrollback/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/PouryaFA81/Scrollback/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/PouryaFA81/Scrollback/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/PouryaFA81/Scrollback/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/PouryaFA81/Scrollback/compare/d83cf5a...v0.2.0
[0.1.0]: https://github.com/PouryaFA81/Scrollback/commits/d83cf5a
