# FAQ

[← Guide index](README.md)

### Does Scrollback need an internet connection or an account?

No. Everything except the optional servers works offline, with no account. The internet permission
is only used for servers you add yourself (Nextcloud, Subsonic / OpenSubsonic, Jellyfin, SFTP, SMB,
a VERT server), for [Nearby share](nearby-share.md) on your Wi-Fi, and for **Check for update**,
which contacts GitHub only when you tap it.

### Does Scrollback track me or show ads?

No. There are no analytics, crash-reporting or advertising libraries in the app, and no ads.

### Which Android versions are supported?

Android 13 and newer.

### Where can I download the APK?

From the [latest release](https://github.com/PouryaFA81/Scrollback/releases/latest) on GitHub. Pick
**arm64-v8a** for most phones, **armeabi-v7a** for older 32-bit phones, or **universal** if you're
not sure. See the [README](../../README.md#download) for details. The repository is private for now,
so the link works only for people with access until it goes public.

### Why does Scrollback mention "All files access"?

It's optional. It lets the [file manager](file-manager.md) browse your whole internal storage
without picking folders one by one. Everything else, including the feeds and music, works with the
folders you grant through Android's folder picker.

### My new videos don't show up.

Scrollback rescans your folders when the app starts. To rescan right away, open **Settings** and
tap **Rescan now**. Also check that the folder (or a parent folder) is listed in
**Settings → Folders**.

### Which file types are supported?

The feeds and the music player play the video, picture and audio files that Android's media
framework can handle on your phone; the exact list depends on your device. For other video formats
(for example MPEG-2, VC-1, AVI or WMV), [Scrollback Player](scrollback-player.md) uses libVLC, which
plays almost anything. If the feed can't play a video, it opens in Scrollback Player automatically.

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

Yes, since v0.12.0. Open **Library → Watch**. See [Watch](watch.md).

### My home server works on Wi-Fi but not over my phone's VPN.

If your VPN sends all traffic through the tunnel, it may not be able to reach your home network.
Turn on **LAN bypass** or **split tunneling** in your VPN app, or add the address that works over
the VPN as the server's **alternate address**. **Test connection** shows which address answers. See
[Server connections](server-connections.md).

### Every song shows up twice.

That happens when two servers share one music folder.
Turn on **Hide duplicates across servers** in **Settings → Music**. Nothing is deleted. See
[Hide duplicates across servers](music-servers.md#hide-duplicates-across-servers).

### Are the server features stable?

They're new and still in early testing, especially Watch (tested against a simulated Jellyfin
server so far) and reading files from SMB shares. Your feedback helps a lot:
[open an issue](https://github.com/PouryaFA81/Scrollback/issues/new/choose).

### Can Scrollback update itself?

Yes, since v0.6.0. Open **Settings → Updates → Check for update**. It only checks when you tap. See
[Updating Scrollback](updates.md).

### Can Scrollback convert files?

Yes, since v0.9.0, with VERT. See [File conversion with VERT](file-conversion.md).

### Can I share files with my computer?

Yes. [Nearby share](nearby-share.md) sends and receives files over Wi-Fi with computers and phones
running LocalSend or RSend.

### Why is the APK so much bigger since v0.13.0?

Since v0.13.0 the APK includes libVLC for [Scrollback Player](scrollback-player.md), which plays
almost any video format. Pick the **arm64-v8a** APK if your phone supports it, because the
**universal** APK is the biggest. The release page lists the current size of each file.

### Is it on F-Droid or Google Play?

Not yet. An F-Droid / IzzyOnDroid build without All files access is on the
[roadmap](../../ROADMAP.md).

### What license is Scrollback under?

Scrollback is free software under the **GNU General Public License v3.0 or later**
(GPL-3.0-or-later). See [LICENSE](../../LICENSE). Third-party libraries keep their own licenses;
they are listed in [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md) and in the app under
**Settings → About → Open-source licenses**.
