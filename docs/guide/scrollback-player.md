# Scrollback Player

[← Guide index](README.md)

Since v0.13.0, Scrollback has its own full movie player, separate from the swipe feed, a bit like
VLC or MX Player. It's built on libVLC, so it plays almost any video: HEVC, VP9, AV1, MPEG-2, VC-1,
AVI, WMV, MKV and more, with ASS/SSA subtitles in their original styling.

## Open a video

- **From another app:** choose **Open with → Scrollback Player** for any video file.
- **From Files:** tap a video. The other videos in that folder become the playlist. Or select some
  videos and tap **Play**. (Use **Open with** in the selection bar to play in another app.)
- **From the feed:** open a video's **Info** sheet and tap **Play in full player**.
- **Automatically:** if the feed can't play a video's format, it opens in Scrollback Player.

It plays local files, files from folders you picked with Android's folder picker,
[Nextcloud](nextcloud.md), and [SFTP and SMB servers](network-storage.md).

## Gestures

| Gesture | What it does |
|---|---|
| Tap | Show or hide the controls |
| Press and hold | Play at 2× while you hold, with a **2×** pill at the top. Let go to go back to your speed. Swipes are ignored while you hold. |
| Swipe up or down on the left | Brightness |
| Swipe up or down on the right | Volume |
| Swipe sideways | Seek |
| Double-tap the left or right side | Skip back or forward 10 seconds |
| Double-tap the middle | Play or pause |
| Pinch | Zoom |

## Picture

- **Aspect modes:** Fit, Stretch, Crop, 16:9, 4:3 and Original.
- **Rotation:** Auto, Landscape or Portrait.
- **Screen lock**, which ignores gestures until you tap the lock again.
- **Frame capture:** saves the current frame to `Pictures/Scrollback`.

## Sound and subtitles

- Choose the **audio track** and the **subtitle track**, or load a **subtitle file**.
- Adjust **audio delay** and **subtitle delay**.
- Style subtitles: **size**, **colour**, and a **background box**. ASS/SSA subtitles keep their own
  styling.
- Subtitles turn on automatically when a file has them. You can switch them off.

## Playback

- **Speed** from 0.25× to 4×.
- **A-B repeat:** set a start point (A) and an end point (B) to loop a part.
- **Previous** and **next** video in the playlist.
- **Resume:** picks up where you left off.
- **Hardware or software decoding:** hardware is the default; switch if a video doesn't play well.
- **Screen stays on** while a video plays, and can turn off again when you pause (**Settings →
  Playback → Keep screen on while playing**).
- **Picture-in-picture** and **background audio**, with a media notification.

## Good to know

- The **HW** / **SW** label in the top bar shows the decoder that's **actually in use**. Even with
  hardware decoding on, some formats are decoded in software because the phone has no hardware
  decoder for them (for example MPEG-2, VC-1, or AV1 on phones without an AV1 chip). Those show
  **SW**.
- Scrollback Player isn't available on x86 devices, such as most emulators. It shows a notice there.
- libVLC is LGPL-2.1-or-later and is included unmodified as separate libraries. See
  [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md).
