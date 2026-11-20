# Music and music servers

[← Guide index](README.md)

Scrollback includes a music player. It plays the songs in your folders, and can also stream from
your Nextcloud, from **Subsonic / OpenSubsonic** servers and from **Jellyfin**.

> **New.** The music player and the music-server connections arrived in v0.4 and are still in
> early testing. Please [report anything odd](https://github.com/PouryaFA81/Scrollback/issues/new/choose).

## The Music tab

The Music tab has five sections: **Songs**, **Artists**, **Albums**, **Folders** and **Playlists**.
**Playlists** also contains **Liked songs** and **Recently played**. Use the search button at the top
to search **Songs, artists, albums**.

- Tap a song to play it. Lists have **Play** and **Shuffle** buttons.
- Each song has a **More** menu with **Like**, **Play next**, **Add to queue** and **Add to
  playlist**.

Songs in your [folders](adding-folders.md) are picked up on the next scan, together with their
tags and embedded cover art.

### Show or hide the Music tab

Don't need music? Open **Settings** and turn off **Show Music** under **Music**. The tab disappears,
leaving four tabs. Turn it back on any time.

## Playing music

- A **mini player** sits above the bottom bar on every tab except the Feed. Tap it to open the full
  player.
- The **full player** shows the artwork and a seek bar, with **Shuffle**, **Previous**,
  **Play/Pause**, **Next** and **Repeat** (tap to cycle: off, repeat all, repeat one). You can also
  **Like** the song or **Add to playlist**.
- Open the **Queue** to see what's coming up. You can **Move up**, **Move down** or **Remove**
  songs.
- Music keeps playing in the background, with controls in the notification and on the lock screen.
  The first time you play a song, Android may ask whether Scrollback may show notifications.
- When you start a video in the feed, the music pauses.

## Connect a music server

### Subsonic / OpenSubsonic (including Navidrome)

Scrollback speaks the **Subsonic / OpenSubsonic** protocol. That covers servers such as
**Navidrome**, **Nextcloud Music**, **Airsonic** and **Gonic**. There is no separate Navidrome
connection: connect Navidrome as a Subsonic / OpenSubsonic server.

1. Open **Settings → Servers**, tap **Add server** and choose **Subsonic / OpenSubsonic**.
2. Enter the **Server address** (for example `https://music.example.com`), your **Username** and
   your **Password**.
3. Tap **Connect**.

Scrollback signs in with token-and-salt authentication, so your password is never sent in clear
text. The password is stored encrypted on your phone.

**Likes and stars:** liking a song in Scrollback stars it on the server, and starred songs show up
as liked. If a song was liked in Scrollback since the last scan, that wins; otherwise the server's
star wins.

### Jellyfin

1. Open **Settings → Servers**, tap **Add server** and choose **Jellyfin**.
2. Enter the **Server address** (for example `https://jellyfin.example.com`), your **Username** and
   your **Password**.
3. Tap **Connect**.

Scrollback signs in once and keeps only Jellyfin's access token, stored encrypted on your phone.
Your password is not kept. Scrollback streams your music libraries; **favourites** in Jellyfin
show up as likes and the other way round.

The same Jellyfin account also gives you movies and shows under **Library → Watch**. See
[Watch](watch.md).

### After connecting

You'll see **Connected — indexing music…** and the server's songs start to appear in the Music
tab. If you need to index a server again, tap it in **Settings → Servers** and choose **Add music
library**. **Sign out** forgets the credentials and the server's songs on your phone; nothing is
deleted on the server.

When you add a server, Scrollback follows any redirects and saves the address the server really
answers on.

### Nextcloud

Audio in Nextcloud folders you have added as sources plays in the Music tab too. See
[Nextcloud](nextcloud.md).

## Alternate address and Test connection

Tap a Subsonic / OpenSubsonic or Jellyfin server in **Settings → Servers** to set an **Alternate
address** (for example `http://192.168.1.10:4533` at home and a VPN address away) or run **Test
connection**. See [Server connections](server-connections.md).

> **Using a VPN on your phone?** If your VPN sends all traffic through the tunnel, your home
> server may be unreachable while it's on. Turn on **LAN bypass** or **split tunneling** in your VPN
> app, or add the address that works over the VPN as the server's
> [alternate address](server-connections.md#alternate-address).

## Hide duplicates across servers

If the same songs are on more than one server, for example when Nextcloud Music and Navidrome
share one music folder, every song shows up twice. To show each song once:

1. Open **Settings** and turn on **Hide duplicates across servers** under **Music**. The same
   switch is also on the **Servers** screen, in the **Music from several sources** card.
2. On the **Servers** screen, use the arrows in that card to put your sources in order. The first
   one is your **preferred source**: merged songs play from it.

Songs count as the same when their title, artist and album match and their length is within a
couple of seconds (and their track numbers match, when both copies have one). Albums and artists
are merged too.

- Merged songs show a small **2 sources** label (or more, if there are more copies).
- **Long-press** a merged song, or tap **More** and then **Play from…**, to play a different copy.
- **Liking** a merged song likes every copy, and stars or favourites it on each server that
  supports that.
- It only changes what you see. **Nothing is deleted** on your phone or on any server, and you can
  turn it off any time.

## Coming soon

- **Scrobbling** to Subsonic servers
- **Lyrics**
