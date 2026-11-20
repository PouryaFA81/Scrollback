# File manager

[← Guide index](README.md)

The **Files** tab is a built-in file manager. You can use it to tidy up your downloads, move clips
into the folders Scrollback watches, and add new folders as sources.

> The file manager is new (v0.2) and still in early testing. It can permanently delete files, so
> please double-check before you confirm a delete.

## The Files home screen

- **Storage**: how much of your internal storage is used, with a breakdown by type.
- **Categories**: Videos, Images, Audio, Documents, Downloads and Installation files.
- **Recent**: your most recent files. Documents open in Scrollback; other files open with another
  app.
- **Locations**: the folders you have given Scrollback access to, your
  [SFTP and SMB servers](network-storage.md), plus **Internal storage** if you turned on All files
  access. Tap **Add folder access** to pick another folder.
- **Tools**: [Network storage](network-storage.md), [Notes](notes-editor.md),
  [Archives](archives.md), [Nearby share](nearby-share.md), [Office](office.md) and
  [Convert (VERT)](file-conversion.md).

To see the categories and recent files, tap **Allow media access** and allow it. Scrollback only
asks for videos, images and audio.

## Browse a folder

Tap a location to open it.

- **Breadcrumbs** at the top show where you are. Tap one to jump back up.
- Tap a folder to open it. Tapping a file opens it in the right place:
  - `.md` and `.txt` files open in the [Notes editor](notes-editor.md).
  - PDF, Word, Excel, PowerPoint, CSV and TSV files open in [Office](office.md).
  - Archives (ZIP, TAR, 7z and more) open in [Archives](archives.md).
  - Videos open in [Scrollback Player](scrollback-player.md), with the folder's other videos as the
    playlist.
  - Anything else opens with another app.
- The buttons at the top let you **Search** this folder and everything below it, create a **New
  folder**, **Sort** (by Name, Date modified, Size or Type; tap the same option again to reverse the
  order) and switch the **Layout** between a list and a grid.

## Select and manage files

**Long-press** a file or folder to select it, then tap more items to add them to the selection. The
bar at the bottom offers:

| Action | What it does |
|---|---|
| **Copy** / **Move** | Puts the items on a clipboard. Open the destination folder, then tap **Paste**. |
| **Rename** | Renames a single item. |
| **Delete** | Deletes the items permanently, after you confirm. Deleting a folder deletes everything inside it. |
| **Share** | Shares files through Android's share sheet. Folders can't be shared. |
| **Send nearby** | Sends the selected files to a nearby device with [Nearby share](nearby-share.md). |
| **Compress** | Makes a ZIP of the selected files and folders. See [Archives](archives.md). |
| **Play** | Plays the selected videos in [Scrollback Player](scrollback-player.md). |
| **Open with** | Opens a single file in another app. |
| **Convert** | Converts a single file with [VERT](file-conversion.md). |
| **Info** | Shows the **Details** of a single item. |
| **Add source** | Adds the selected folder to Scrollback, so its videos, pictures and songs show up in the app. |
| **All** | Selects everything in the folder. |
| **Cancel** | Clears the selection. |

Some actions are greyed out where Android doesn't allow changes, for example in read-only folders.

Inside a **category**, you can **Copy**, **Share**, **Send nearby**, **Delete** and view **Info**. To paste copied
items, open a folder in Files and tap **Paste** there.

## Folder access vs. All files access

By default, Scrollback works only with the folders you pick through Android's folder picker. That is
enough for everything in the app, including the file manager.

If you'd rather browse your whole internal storage like a regular file manager, without picking
folders one by one, you can turn on **All files access**:

1. On the Files tab, find the **Optional** card titled **All files access**.
2. Tap **Open permission settings**.
3. Turn on the switch for Scrollback in Android's settings.

This is entirely optional. Scrollback has no ads or tracking, and file access stays on your phone.
You can turn it off again in Android's settings at any time.

Without All files access, deleting from a category goes through Android's own confirmation dialog.
