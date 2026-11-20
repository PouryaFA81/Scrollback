# Network storage: SFTP and SMB

[← Guide index](README.md)

Since v0.7.0, Scrollback can open folders on your **SFTP** servers and **SMB** shares (Windows,
Samba or a NAS), and stream their videos and pictures into your feeds.

> **New.** SFTP has been tested end to end. Reading files from SMB shares still needs testing on a
> real Samba or Windows share, so please
> [report anything odd](https://github.com/PouryaFA81/Scrollback/issues/new/choose).

## Add a server

1. Open the **Files** tab, then **Tools → Network storage**.
2. Tap **Add server** and choose **SFTP** or **SMB**.
3. Fill in the details:
   - **SFTP**: **Host or IP** (for example `nas.example.com` or `192.168.1.10`), **Port** (22),
     an optional **Start folder**, your **User**, and either a **Password** or a **Private key**
     (OpenSSH or PEM). Tap **Load key file…** to pick the key, or paste it. Add the
     **Key passphrase** if your key has one.
   - **SMB**: **Host or IP**, **Port** (445), the **Share** (for example `media`), an optional
     **Folder in the share**, the **User** (`DOMAIN\user`, or leave it empty for guest access) and
     the **Password**.
4. Tap **Connect & save**. Scrollback checks the connection before saving.

### Trusting an SFTP server

The first time you connect to an SFTP server, Scrollback shows its key fingerprint (it starts with
`SHA256:`) and asks **Trust this server?** If you can, compare it with the fingerprint on the server
(for example with `ssh-keygen -lf` on the server's host key). After you tap **Trust**, the key is
remembered. If it ever changes, Scrollback refuses to connect, because that could mean someone is
intercepting the connection.

## Browse and manage files

Saved servers appear under **Files → Locations**. Tap one to browse it like any other folder: open
folders, create new ones, rename, delete and share. Opening or sharing a file downloads it to the
app's cache first.

Tools such as the [Notes editor](notes-editor.md) and [Archives](archives.md) work on server folders
too, and videos play in [Scrollback Player](scrollback-player.md).

## Stream into your feeds

- In **Tools → Network storage**, tap **Add to feeds** on a server to index its root folder; or
- open a folder on the server, select it, and tap **Add source**.

Its videos and pictures then show up in the **Feed** and **Pictures** tabs, streamed with seeking.
Recently played media is cached on your phone so it plays smoothly the next time.

## Your credentials

Passwords and private keys are encrypted with a key from the Android Keystore, like your other
server accounts. To change or remove a server, open **Tools → Network storage** and use **Edit** or
**Remove**.

> On **Android 17** and newer, apps need the **Nearby devices** (local network) permission to reach
> devices on your home network. Scrollback asks for it when you first open
> [Nearby share](nearby-share.md); once granted, it covers SFTP and SMB servers on your LAN too.
