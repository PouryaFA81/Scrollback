# Updating Scrollback

[← Guide index](README.md)

> **In-app updates: Coming soon.** Scrollback can't check for or install updates by itself yet.
> **APK downloads on the Releases page: coming soon** as well.

## Which version do I have?

Open **Settings** with the gear at the top. The version is shown under **About**, for example
**Scrollback 0.5.0-debug**.

## Update manually today

Today, updating means building and installing the new version yourself, the same way you installed
it:

1. Get the latest code from the repository (for example `git pull` in your copy).
2. Build and install it:

   ```bash
   ./scripts/build-apk.sh --install
   ```

   or build with `./scripts/build-apk.sh` and run `adb install -r dist/scrollback-debug.apk`.
3. Alternatively, copy the new `dist/scrollback-debug.apk` to your phone and open it.

Installing the new APK **over** the old one keeps your library, likes, playlists, notes and
settings. Don't uninstall first: uninstalling deletes Scrollback's data on your phone (your media
files in your own folders are not affected).

> Android only accepts an update that is signed with the same key as the installed app. Debug
> builds made on a different computer use a different key, so Android refuses to install them over
> each other. In that case you'd have to uninstall first, which deletes your Scrollback data. Signed
> release builds, which avoid this, are **Coming soon**.

Before a big update, it's worth turning on **Sync library** for your [Nextcloud](nextcloud.md) if you
use one, so your likes, notes, tags, history and playlists are also stored on your server.

See the [changelog](../../CHANGELOG.md) for what changed in each version.
