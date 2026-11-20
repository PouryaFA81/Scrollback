# Watch: Jellyfin movies and shows

[← Guide index](README.md)

Since v0.12.0, Scrollback can browse and play the movies and shows on your own
[Jellyfin](https://jellyfin.org) server.

> **New.** Watch was tested with automated tests and a simulated Jellyfin server, not yet with a
> real one. Please [report anything odd](https://github.com/PouryaFA81/Scrollback/issues/new/choose).

## Get started

1. Add your Jellyfin server under **Settings → Servers** (see
   [Music and music servers](music-servers.md#jellyfin)). A Jellyfin account you already added for
   music works here too.
2. Open the **Library** tab and tap **Watch** at the top.

With more than one Jellyfin account, use the ⇄ button (**Switch server**) to change.

## Browse

- **Home**: Continue watching and Next up (tap to resume), Recently added movies and shows, and
  Favorites.
- **Movies**, **Shows** and **Collections**: poster grids. Swipe left or right to move between them.
- **Search**: finds movies, shows, episodes and collections.

Tap a poster to see the details: backdrop, year, runtime, rating, genres, overview, director,
writer and cast. Shows list their seasons and episodes with your progress, plus a button to play
the next episode. Collections list their movies.

**Mark played** / **Mark unplayed** and **Favorite** are saved on your Jellyfin server.

## Play

- If a movie was started before, choose **Resume** or **From start**.
- Scrollback plays the original file when your phone can handle it. If it can't, or the phone fails
  to play it, Scrollback asks Jellyfin to convert it on the fly. The top bar shows **Direct play** or
  **Transcoding**.
- Choose **Audio** and **Subtitles** tracks, including external subtitle files. Picture-based
  subtitles are drawn into the video by the server when needed.
- Your progress is reported to Jellyfin while you watch, so other Jellyfin apps pick up at the same
  spot.
- **Press and hold** the video to watch at **2×**; let go to go back to normal speed.
- The screen stays on while a video plays (**Settings → Playback → Keep screen on while playing**).

## Privacy

Your Jellyfin password is used once to sign in. Scrollback keeps only an access token, encrypted on
your phone, and never puts it in links.

If your server is only reachable at home, see
[Server connections](server-connections.md) to add an alternate address.
