# File conversion with VERT

[← Guide index](README.md)

Since v0.9.0, Scrollback can convert files with [VERT](https://vert.sh), an open-source file
converter.

## Convert a file

- Open **Files → Tools → Convert (VERT)** and tap **Pick a file…**; or
- in [Files](file-manager.md), select one file and tap **Convert**.

### Videos

Videos convert on a VERT video server (called **vertd**).

1. Choose the format under **Convert to**: MP4, WebM, MKV, MOV, AVI, GIF, WebP, APNG, M4V or 3GP.
2. Choose **Fast**, **Balanced** or **Quality**.
3. Tap **Convert**.

Scrollback uploads the video, shows the progress, saves the result to `Download/Scrollback/` and
then tells the server to delete its copy. You can cancel at any time. When it's done, tap **Open
result** or **Share**.

### Images, audio and documents

These open in the **VERT web app** in your browser (**Open VERT**), which converts them right on
your phone, so nothing is uploaded.

## Choose a server

Tap **VERT server** on the Convert screen and pick one of two choices:

- **vert.sh** (the default): the public VERT web app at `https://vert.sh`, with its video server
  at `https://vertd.vert.sh`, both run by the VERT team.
- **Custom**: your own VERT server. Tapping it clears both fields so you can enter:
  - **VERT URL**: your VERT web app, for example `https://vert.example.com`.
  - **Video server (vertd) URL**: your vertd address. Self-hosted VERT shows it in its settings.
    Leave it empty if you only want the web app; video conversion is then turned off.

Both addresses are saved only on your phone. If you save with the VERT URL left empty, Scrollback
goes back to vert.sh. A custom server you saved before v0.14.1 is kept and shows as **Custom**.

You can also add:

- **Extra header (optional)**: for a vertd behind a login proxy, in the form `Name: value`. It is
  stored encrypted and only sent to vertd. **Remove header** deletes it.

Tap **Test** to save and check the connection to the video server, or **Save** to keep your
choice.

## Good to know

- Converting a **video** uploads it to the vertd server you chose. Use your own server if you'd
  rather keep your files at home.
- You need an internet connection (or a connection to your own server). Scrollback tells you clearly
  when you're offline or the server can't be reached.
- VERT and vertd are separate open-source projects by the VERT team. Scrollback only talks to them
  over the network and includes none of their code.
