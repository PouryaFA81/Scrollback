# Nearby share

[← Guide index](README.md)

Since v0.11.0, Scrollback can send and receive files over Wi-Fi with phones and computers running
[LocalSend](https://localsend.org) or RSend. No internet connection or account is needed.

> **New.** Please [report anything odd](https://github.com/PouryaFA81/Scrollback/issues/new/choose).

## Open Nearby share

- **Files → Tools → Nearby share**; or
- select files in [Files](file-manager.md) (or in a category) and tap **Send nearby**; or
- pick **Nearby share** in any app's share sheet. The feed's share sheet offers **Send nearby** too.

On **Android 17** and newer, the first time you open Nearby share, Scrollback asks for the
**Nearby devices** (local network) permission. If the prompt doesn't appear, open
**Open app settings → Permissions → Nearby devices**.

## Send files

1. Make sure the other device is on the same Wi-Fi and has **LocalSend** or **RSend** open.
2. In Nearby share, tap **Choose files…** (or **Add more…**) if you haven't picked any yet.
3. Devices on your Wi-Fi appear under **Devices** on their own. Tap the one you want to send to.

If a device doesn't show up, tap **Add by address…** and enter its IP address, for example
`192.168.1.20`.

## Receive files

While the Nearby share screen is open, Scrollback is **Ready to receive**.

- When someone sends you files, Scrollback asks you to **Accept** or **Decline**.
- Turn on **Quick save** to accept incoming files without asking.
- Received files go to `Download/Scrollback`. Use **Save to** to choose another folder.

## Settings

- **Device name**: the name other devices see.
- **Receive PIN**: an optional PIN that senders must enter. After five wrong tries, a sender has to
  wait a minute. Leave it empty to turn it off.
- **Security fingerprint**: your device's fingerprint, which you can copy and compare.

## Privacy and battery

- Transfers are encrypted (HTTPS).
- Scrollback only listens for other devices while the Nearby share screen is open or a transfer is
  running. There is no always-on background server. A notification shows while a transfer runs.

## Credits

Nearby share is a Kotlin port of RSend by the same author. It follows the public LocalSend v2
protocol so it can talk to the official LocalSend apps. LocalSend is a separate project, Scrollback
isn't affiliated with it, and no LocalSend code is included.
