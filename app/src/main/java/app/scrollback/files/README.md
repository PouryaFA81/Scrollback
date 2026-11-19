# Phase 2: built-in file manager (not implemented yet)

This package will hold a One UI "My Files"-style browser: browse any granted SAF tree,
copy/move/rename/delete, create folders, multi-select, storage overview.

Boundaries:
- Reads and writes go through `data.source.MediaSource` / SAF, never raw paths.
- Indexed media changes should call `LibraryRepository.rescanAll()` (or a targeted rescan).
