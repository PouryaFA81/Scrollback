# FAQ

[← Guide index](README.md)

### Does Scrollback need an internet connection or an account?

No. Everything except the optional servers works offline, with no account. The internet permission
is only used for servers you add yourself (Nextcloud, Subsonic / OpenSubsonic, Jellyfin).

### Does Scrollback track me or show ads?

No. There are no analytics, crash-reporting or advertising libraries in the app, and no ads.

### Which Android versions are supported?

Android 13 and newer.

### Where can I download the APK?

**APK downloads on the Releases page: coming soon.** For now, build a debug APK and install it with
adb, as described in the [README](../../README.md#install).

### Why does Scrollback mention "All files access"?

It's optional. It lets the [file manager](file-manager.md) browse your whole internal storage
without picking folders one by one. Everything else, including the feeds and music, works with the
folders you grant through Android's folder picker.

### My new videos don't show up.

Scrollback rescans your folders when the app starts. To rescan right away, open **Settings** and
tap **Rescan now**. Also check that the folder (or a parent folder) is listed in
**Settings → Folders**.

### Which file types are supported?

Scrollback plays the video, picture and audio files that Android's media framework can handle on
your phone. The exact list depends on your device.

### Does removing a folder delete my files?

No. **Remove folder** only makes Scrollback forget the folder, along with its likes, notes and
playlist entries. Your files stay where they are.

### Does deleting a playlist delete the videos?

No. The videos themselves stay on your device.

### What does "Delete file" do?

It permanently deletes the file from its folder after you confirm. For a Nextcloud item it deletes
the file **on the server**.

### I forgot my PIN.

There is no PIN recovery in the app. Clearing Scrollback's app data in Android's settings (or
reinstalling) removes the PIN, but it also removes your Scrollback library: likes, notes, tags,
playlists, history and hidden flags. Your media files are not affected. If you use
[Nextcloud sync](nextcloud.md#sync-your-library), your library data can come back from your server
after you sign in again.

### Are my likes and playlists backed up?

They are stored on your phone only. Scrollback turns off Android's automatic app backup, so they
are not included in your device's cloud backup. To keep a copy elsewhere, turn on
[Sync library](nextcloud.md#sync-your-library) for a Nextcloud account.

### Is Navidrome supported?

Yes, through the **Subsonic / OpenSubsonic** connection. There is no separate Navidrome option. See
[Music and music servers](music-servers.md).

### Can I watch my Jellyfin videos?

Not yet. Jellyfin videos in the feeds are **Coming soon**. Today Scrollback uses Jellyfin for music.

### Are the server features stable?

They're new. Nextcloud, Subsonic / OpenSubsonic and Jellyfin support arrived in v0.3 and v0.4 and
are still in early testing. Your feedback helps a lot:
[open an issue](https://github.com/PouryaFA81/Scrollback/issues/new/choose).

### Can Scrollback convert files or update itself?

Not yet. [File conversion with VERT](file-conversion.md) and [in-app updates](updates.md) are both
**Coming soon**.

### Is it on F-Droid or Google Play?

Not yet. A version suitable for Google Play, without All files access, is on the roadmap.

### What license is Scrollback under?

The license is **to be decided** and will be chosen before the repository goes public.
