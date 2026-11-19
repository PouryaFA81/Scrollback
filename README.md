# Scrollback

A private, offline, TikTok-style viewer for the videos and pictures you've saved on your phone.

Saved TikToks pile up in a folder and gallery apps make them hard to find or re-watch. Scrollback
indexes the folders you pick and plays them in a full-screen vertical feed — shuffled or in the
order you saved them — with likes, playlists, notes, tags and search. Nothing leaves the device.

> Status: **v0.2 (phase 2)** — early, personal project. Android 13+ (minSdk 33).

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
- No `INTERNET` permission in v1.

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

## Stack

Kotlin 2.4 · Jetpack Compose + Material 3 (dark only) · AndroidX Media3 ExoPlayer · Room · DataStore ·
Coil 3 (+ video frames) · Haze (backdrop blur) · Navigation Compose · manual DI (`AppContainer`).
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

Components (`ui/components`): `Modifier.glass`, `GlassIconButton`, `FloatingGlassNav` (detached pill
bottom bar with a springy selection bubble), `GlassScreen` + `LargeTitle` + `CollapsingTopBar`
(One UI pattern), `GlassSheet`, `GlassPill`, `SectionCard`/`SettingsRow`, `LockScreen` keypad.

## Architecture

```
app.scrollback
├── core/            pure Kotlin: duplicate hashing, PIN hashing, tags, feed ordering (unit-tested)
├── data/
│   ├── db/          Room entities + DAOs (generic media kinds: video, image, audio)
│   ├── source/      MediaSource abstraction — SafMediaSource now; WebDAV later
│   ├── repo/        LibraryRepository, DuplicateRepository
│   └── prefs/       DataStore settings
├── feature/         feed, pictures, files, library, settings (folders, duplicates), lock
├── files/           file manager backends (Raw java.io / SAF / MediaStore), sort, names, clipboard ops
├── sync/            phase 3 placeholder (Nextcloud)
└── ui/              theme tokens, glass components, navigation
```

Every feed, the player, sharing and the duplicate finder go through `MediaSource`
(`scan()`, `playback()` → URI + headers, `byteSource()`, `delete()`, `shareUri()`), so remote
sources can appear in the same feeds later.

## Roadmap

1. **Phase 1 — v0.1:** local offline viewer as above. ✅
2. **Phase 2 — built-in file manager:** One UI "My Files"-style browser (copy/move/rename/delete,
   folders, multi-select, storage overview) over SAF, in `files/`. ✅ v0.2.0
3. **Phase 3 — Nextcloud:** `WebDavMediaSource` (PROPFIND scan) that **streams** remote videos in the
   same feeds without downloading first (Media3 `DefaultHttpDataSource` with auth headers), plus
   sync of likes/notes/tags/playlists and optional offline copies. Login Flow v2 app password,
   stored encrypted.
4. **Phase 4 — music player:** Media3 `MediaSessionService` for background playback and lock-screen
   controls, reusing folders, likes, playlists and tags (the index already has an `audio` kind);
   optional tab toggled in Settings; Nextcloud audio streaming later.

## Build

```bash
./scripts/build-apk.sh            # build + dist/scrollback-debug.apk + dist/archive/ backup
./scripts/build-apk.sh --install  # same, then adb install -r
./gradlew testDebugUnitTest lint
```

Install on a phone: enable USB debugging and run `adb install -r dist/scrollback-debug.apk`, or copy
the APK to the phone and open it (allow "install unknown apps" for your file manager).

## License

**TBD** — to be chosen before the repository goes public. Until then, all rights reserved.
No third-party code is copied; SnapReel (MIT) was only a design reference.
