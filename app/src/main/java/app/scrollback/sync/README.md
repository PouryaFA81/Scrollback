# Phase 3: Nextcloud (WebDAV) streaming and sync (not implemented yet)

- `WebDavMediaSource : MediaSource` with `type = SourceType.WEBDAV`:
  - `scan()` via PROPFIND (Depth: 1, recursive), mapping `getlastmodified`, `getcontentlength`, `getcontenttype`, `getetag`.
  - `playback()` returns the https URL plus an `Authorization` header; the player builds a
    `DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)` so remote videos stream
    in the same feeds without downloading first.
  - Credentials: Nextcloud app password via Login Flow v2, stored encrypted (never in the repo).
- Sync worker (WorkManager): two-way metadata sync (likes, notes, tags, playlists) as a JSON file
  in the Nextcloud folder, and optional offline download of selected items.
