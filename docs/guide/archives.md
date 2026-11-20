# Archives

[← Guide index](README.md)

Since v0.8.0, Scrollback can extract archives and make ZIP files.

## Extract an archive

1. Tap an archive in [Files](file-manager.md), or open **Files → Tools → Archives** and pick one.
2. Choose where it goes: **Extract here**, or **Into a new folder next to it**.

Supported formats: **ZIP**, **TAR**, **TAR.GZ**, **TAR.BZ2**, **TAR.XZ**, **7z**, and single
**GZ**, **BZ2** and **XZ** files.

## Make a ZIP

In Files, long-press to select files and folders, then tap **Compress**. Scrollback makes a `.zip`
with everything you selected.

## Safe by default

- Existing files are **never overwritten**. If a name is taken, the new file gets a number, such as
  " (1)".
- Files inside an archive that try to escape the target folder are refused.
- Links and device files are skipped.
- Extraction stops on suspicious archives that would expand to an enormous size ("zip bombs").

Archives work in local folders, folders you picked with Android's folder picker, and on
[SFTP and SMB servers](network-storage.md).

## Not supported yet

- Password-protected archives. Scrollback tells you when an archive has a password; you can open it
  with another app instead.
