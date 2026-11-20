# Server connections: alternate address and Test connection

[← Guide index](README.md)

Since v0.14.0, Scrollback handles server addresses more carefully, so your Nextcloud, Subsonic /
OpenSubsonic (including Navidrome) and Jellyfin servers keep working at home, away, and behind
reverse proxies.

> **New in v0.14.0** and still in early testing. Please
> [report anything odd](https://github.com/PouryaFA81/Scrollback/issues/new/choose).

## Redirects

Many servers send you on to another address, for example from `http://` to `https://`, or to a
sub-folder such as `/nextcloud`. Scrollback follows these redirects itself:

- Your sign-in is kept when the server moves you from http to https, or to another path on the same
  server.
- Your sign-in is **not** passed on to a different host, or from https down to plain http, unless
  that address is one you set up for this server.
- When you add a server, Scrollback follows its redirects first and saves the address the server
  really answers on. Nextcloud sign-in stays on the address that worked.

## Alternate address

Each server can have a second address for the same server. A common setup is your LAN address at
home and a VPN address when you're away.

1. Open **Settings → Servers** and tap your server.
2. Tap **Alternate address**.
3. Enter the address, for example `http://192.168.1.10:4533`, and tap **Check and save**.
   Scrollback checks that the server answers there.
4. If the address only works on another network (for example only at home), tap
   **Save anyway (only reachable on another network)**.

Scrollback then uses the address that worked last, and switches to the other one automatically
when it can't connect. Your sign-in is only sent to these two addresses. To stop using it, tap
**Alternate address** again and choose **Remove alternate address**.

## Test connection

1. Open **Settings → Servers** and tap your server.
2. Tap **Test connection**.

Scrollback checks the main address and the alternate address, if you set one. For each one it tells
you:

- whether the server answers and is the kind of server it should be;
- which redirects happened, explained in plain words;
- whether your saved sign-in works there;
- what went wrong, if something did: a name that can't be found from this network, no route,
  nothing answering, a timeout, an HTTPS certificate problem, a redirect loop, or a rejected
  sign-in.

The reachability check for each address gives up after 20 seconds instead of hanging, which helps
when a VPN silently drops the connection. If the redirects end on another address, Scrollback offers
a **Use …** button that saves it as the alternate address. Tap **Test again** to re-run the test.

Passwords, tokens and other secrets are never shown in the results.

## Local network access (Android 17)

On Android 17 and newer, apps need your permission to connect to devices on your **local network**,
like a home server, a NAS or a Jellyfin box at home. Scrollback asks for it when it's needed:

- when you add a server or tap **Test connection**, if the address is on your home network,
- when you connect to an SFTP or SMB server, or set a custom VERT server, on your home network,
- once when you open the app, if a server you already added is on your home network.

Scrollback first explains why, then Android shows its own **Allow** dialog. Servers on the internet
don't need this permission.

If you choose **Not now** or **Don't allow**, servers on your home network can't be reached, and
Scrollback tells you so instead of failing quietly. To turn it on later, open **Settings → Apps →
Scrollback → Permissions → Nearby devices** and allow it. Nearby share uses the same permission.

## Using a VPN on your phone

If your phone's VPN sends **all** traffic through the tunnel, it may not be able to reach your home
network, so your home servers seem to be down while the VPN is on. To fix it:

- turn on **LAN bypass** or **split tunneling** in your VPN app, so local traffic skips the tunnel; or
- set the server's [alternate address](#alternate-address) to the address that works where you are.

**Test connection** shows which address answers on the network you're on right now.

## SFTP and SMB

SFTP and SMB servers under [Network storage](network-storage.md) don't have an alternate address
or Test connection. **Connect & save** checks the connection when you add or edit one.
