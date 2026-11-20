# Duplicate finder

[← Guide index](README.md)

Saved the same clip twice? The duplicate finder spots **byte-identical** files across all your
folders and helps you free up space.

## Run a scan

1. Open **Library** and tap **Duplicates**. (You can also find it under **Settings → Library →
   Duplicate finder**.)
2. Tap **Scan library**.

Scrollback compares files in three steps: first by size, then by a quick fingerprint of the
beginning and end of each file, and finally by a full hash of the content. Only files that match
completely are reported, so files that merely look similar are never grouped.

If nothing turns up you'll see **No duplicates** ("Every file in your library is unique.").

## Review and delete

Each group shows how many copies there are and their size. For every copy you can see its folder
and date; the **oldest** copy is marked.

- By default Scrollback **keeps the oldest copy** and pre-selects the others for deletion.
- Tap a copy to switch it between **Keep** and **Will be deleted**.
- When you are happy, tap **Delete N · size** at the top.
- Confirm in the **Delete N files?** dialog.

Nothing is deleted until you confirm. Deleted files are removed **permanently** from their folders.

Tap **Scan again** to run a fresh scan at any time.
