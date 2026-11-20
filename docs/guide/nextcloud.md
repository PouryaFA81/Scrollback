# Nextcloud

[← Guide index](README.md)

If you have a Nextcloud, Scrollback can stream your videos, pictures and music from it, download
copies for offline use, and keep your likes, notes, tags, history and playlists in sync between
devices.

> **Optional and new.** Nextcloud support arrived in v0.3 and is still in early testing. Scrollback
> stays fully usable without it. If you run into problems, please
> [report a bug](https://github.com/PouryaFA81/Scrollback/issues/new/choose).

## Connect your Nextcloud

1. Open **Settings** with the gear at the top, then tap **Servers** under **Library**.
2. Tap **Add server** and choose **Nextcloud**.
3. Enter your **Server address**, for example `cloud.example.com`. If you leave out `https://`,
   Scrollback adds it for you.
4. Tap **Sign in in browser**.
5. Your browser opens your Nextcloud's own login page. Sign in there (two-factor login and single
   sign-on work) and approve access for Scrollback.
6. Go back to Scrollback. You'll see **Signed in as …** once it's done.

Scrollback never sees your Nextcloud password. Nextcloud gives it an **app password**, which is
stored encrypted on your phone and is never written to logs or to the app's database. You can revoke
it at any time in your Nextcloud under **Settings → Security**.

When you add the server, Scrollback follows any redirects and saves the address your Nextcloud
really answers on. The sign-in stays on that address.

> **Local servers:** for a server on your home network without HTTPS, type the address with
> `http://` in front, for example `http://192.168.1.10`. Only do this on a network you trust.

## Home and away: alternate address and Test connection

Tap your Nextcloud account in **Settings → Servers** to find two more options:

- **Alternate address**: a second address for the same server, for example your LAN address at
  home and a VPN address when you're away. Scrollback switches between the two automatically.
- **Test connection**: checks each address and tells you in plain words what's wrong, if anything.

See [Server connections](server-connections.md) for details.

> **Using a VPN on your phone?** If your VPN sends all traffic through the tunnel, your home
> server may be unreachable while it's on. Turn on **LAN bypass** or **split tunneling** in your VPN
> app, or add the address that works over the VPN as the server's
> [alternate address](server-connections.md#alternate-address).

## Stream a folder into your feeds

1. In **Settings → Servers**, tap your Nextcloud account.
2. Tap **Browse files**.
3. Open the folder you want and tap **Add as Scrollback source** at the bottom.

Scrollback indexes the folder and its media shows up in the **Feed**, **Pictures** and **Music**
tabs, mixed with your local folders. Videos are **streamed**, so you don't need to download them
first. Recently played media is cached on your phone so it plays smoothly the next time.

Nextcloud folders appear in **Settings → Folders** with a cloud symbol (☁).

## Download for offline use

- From a feed: tap **Info**, then **Download**.
- From **Browse files**: tap a file, then **Download**.

The copy is saved to **Download/Scrollback** on your phone. Downloaded items play and share offline.
To share a streamed item that you haven't downloaded yet, download it first.

## Sync your library

Tap your Nextcloud account in **Settings → Servers**:

- **Sync library**: turn this on to keep your likes, notes, tags, history and playlists in sync.
  Scrollback stores them in one file in your Nextcloud: `/Scrollback/scrollback-sync.json`.
- **Sync now**: syncs right away and shows when the last sync happened, or why it failed.

Scrollback also syncs when the app starts and when you leave it. When the same item was changed on
two devices, the most recent change wins. Items are matched across devices by their Nextcloud path,
or by file name and size for local files.

## Offline

If your server can't be reached, Scrollback keeps working. Server items simply won't play until
you're back online, and nothing in your library is marked as missing. Sync errors are shown on the
account and never block the app.

## Sign out

Tap the account in **Settings → Servers**, then **Sign out**. Scrollback forgets the credentials and
removes this server's streams, likes and playlist entries from your phone. **Nothing is deleted on
the server.**

> **Careful:** **Delete file** in a feed's Info sheet deletes a Nextcloud item **on the server**.
