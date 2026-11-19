# Updating Scrollback

[← Guide index](README.md)

Since **v0.6.0**, Scrollback can update itself from its GitHub Releases. It only does this when you
ask: there are no background checks and no scheduled jobs.

## Which version do I have?

Open **Settings** with the gear at the top. The version is shown under **About**, for example
**Scrollback 0.6.0**. Debug builds end in `-debug`, for example **Scrollback 0.6.0-debug**. The
**Updates** section also shows it: *Only checks when you tap. Current: …*

## Check for an update

1. Open **Settings** (the gear at the top) and scroll to **Updates**.
2. Tap **Check for update**. Scrollback asks GitHub for the latest release and tells you either
   **You're up to date**, or that a new version is available, with its download size.
3. If there is a new version, tap **Install** with the version number. The first lines of the
   release notes are shown under it.
4. The first time, Android asks you to allow Scrollback to **install unknown apps**. Turn it on,
   go back, and tap the row again.
5. Scrollback downloads the update (with a progress bar), checks it, and hands it to Android.
   Android shows its own install screen. Confirm, and Android installs the new version.

Your library, likes, playlists, notes and settings stay as they are.

### What Scrollback checks before installing

- It picks the APK that matches your phone's processor: **arm64-v8a** first, then
  **armeabi-v7a**, then the **universal** APK.
- A release build only updates from release APKs, and a debug build only from the debug APK.
- Before installing, it makes sure the downloaded file is really Scrollback (the same package name)
  and that it is **signed with the same key** as the copy on your phone. If not, it stops and tells
  you, instead of letting Android fail halfway.

## While the repository is private

The Scrollback repository is private for now, so GitHub doesn't answer anonymous update checks.
You'll see *No public release found. The repository is private for now…*

If you have access to the repository, you can add a token so the check works:

1. On GitHub, create a **fine-grained personal access token** with **read-only Contents** access to
   `PouryaFA81/Scrollback`.
2. In Scrollback, open **Settings → Updates → GitHub token**, paste it and tap **Save**.

The token is encrypted with a key from the Android Keystore and is only ever sent to
`api.github.com`. To change or remove it, tap **GitHub token** again. Once the repository is
public, no token is needed.

## If something goes wrong

| Message | What it means |
|---|---|
| *GitHub rejected the token. Replace or remove it.* | The token is wrong or has expired. |
| *No release found, or the token can't read PouryaFA81/Scrollback.* | The token doesn't have access to the repository. |
| *GitHub rate limit reached. Try again later.* | Too many checks in a short time. Wait a while. |
| *… has no APK for this build type yet* | The release has no APK for your kind of build (release or debug). |
| *The update is signed with a different key than this install…* | Your copy was signed with a different key, so Android would refuse the update. See below. |
| *Update cancelled.* | You tapped Cancel on Android's install screen. Tap the row again to retry. |

## Release builds and debug builds

Starting with v0.6.0, every release has **signed release APKs** (app id `app.scrollback`) and a
**debug APK** for testing (app id `app.scrollback.debug`). Versions before v0.6.0 were debug builds
only.

The two are **separate apps**. They can be installed side by side, and each has its own library
and settings. Installing the release APK doesn't replace an older debug install, and it doesn't
copy its data. If you used a debug build before and want to move to the release build, turn on
[Sync library](nextcloud.md#sync-your-library) for a Nextcloud account first if you use one, so
your likes, notes, tags, history and playlists can come back after you sign in on the release app.

## Update manually

You can always update by hand:

1. Download the APK for your phone from the
   [latest release](https://github.com/PouryaFA81/Scrollback/releases/latest) (see
   [which one to pick](../../README.md#download)).
2. Open it on your phone, or install it from a computer with `adb install -r <file>.apk`.

Install the new APK **over** the old one to keep your data. Don't uninstall first: uninstalling
deletes Scrollback's data on your phone (your media files in your own folders are not affected).

See the [changelog](../../CHANGELOG.md) for what changed in each version.
