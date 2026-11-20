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

Under **VERT server** on the Convert screen:

- **VERT URL**: the VERT web app to use. The default is `https://vert.sh`, run by the VERT team.
- **Video server (vertd) URL**: the default is `https://vertd.vert.sh`. If you run your own VERT,
  enter its vertd address here; self-hosted VERT shows it in its settings.
- **Extra header (optional)**: for a vertd behind a login proxy, in the form `Name: value`. It is
  stored encrypted and only sent to vertd.
- Tap **Test** to check the connection.

## Good to know

- Converting a **video** uploads it to the vertd server you chose. Use your own server if you'd
  rather keep your files at home.
- You need an internet connection (or a connection to your own server). Scrollback tells you clearly
  when you're offline or the server can't be reached.
- VERT and vertd are separate open-source projects by the VERT team. Scrollback only talks to them
  over the network and includes none of their code.
