# Contributing to Scrollback

Thanks for your interest in Scrollback! Bug reports, ideas, documentation fixes and code are all
welcome. This page explains how to help.

By taking part, you agree to follow our [Code of Conduct](CODE_OF_CONDUCT.md).

## Ways to help

- **Report a bug.** Use the [bug report form](https://github.com/PouryaFA81/Scrollback/issues/new?template=bug_report.yml).
  Testing on real phones and real servers is especially valuable right now: the file manager,
  Nextcloud, Subsonic / OpenSubsonic and Jellyfin support are new and still in early testing.
- **Suggest a feature.** Use the [feature request form](https://github.com/PouryaFA81/Scrollback/issues/new?template=feature_request.yml).
  Please check the [roadmap](README.md#roadmap) and existing issues first.
- **Improve the docs.** The [user guide](docs/guide/README.md) should match what the app really does.
  If a label or a step is wrong, a fix is very welcome.
- **Write code.** See below.

## Before you start on code

For anything bigger than a small fix, please **open an issue first** so we can agree on the
approach. That saves everyone time.

Scrollback has a few principles that guide what gets merged:

- **Offline first.** Everything that doesn't need a server must work without a network.
- **Private by default.** No analytics, tracking, ads or crash-reporting SDKs. Nothing leaves the
  device unless the user connected a server for it.
- **No secrets in storage or logs.** Passwords and tokens stay in the Keystore-encrypted store and
  are never logged or written to the database.
- **Optional permissions stay optional.** In particular, All files access must never be required.
- **Ask before deleting.** Anything that deletes user files needs an explicit confirmation.

## Development setup

You need the JDK (the project is set up for JDK 21) and the Android SDK. The Gradle wrapper is
included.

```bash
./scripts/build-apk.sh            # debug APK at dist/scrollback-debug.apk
./scripts/build-apk.sh --install  # same, then installs it with adb
./gradlew testDebugUnitTest lint  # unit tests and lint
```

The app needs Android 13 (API 33) or newer. Debug builds use the app id `app.scrollback.debug`, so
they can sit next to a future release build.

Read [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for the architecture, how streaming and sync work,
and the design system (colours, spacing, glass components and motion).

## Pull requests

1. Fork the repository and create a branch from `main`, for example `fix/feed-resume` or
   `docs/nextcloud-guide`.
2. Keep each pull request focused on one change.
3. Run `./gradlew testDebugUnitTest lint` and make sure the app builds.
4. Add or update unit tests for logic changes (see `app/src/test`).
5. If you change what the user sees, update the [user guide](docs/guide/README.md) and add a line
   under **Unreleased** in the [CHANGELOG](CHANGELOG.md). Screenshots or a short screen recording in
   the PR help a lot for UI changes.
6. Fill in the pull request template.

### Style

- Kotlin official code style (`kotlin.code.style=official`).
- UI uses Jetpack Compose and the tokens and components in `ui/theme` and `ui/components`. Please
  reuse them rather than adding new colours or one-off styles.
- Write user-facing text in plain, friendly English.

### Commit messages

Use a short summary line in the imperative mood ("Add queue reordering", "Fix resume position"),
with more detail in the body if needed.

## Reporting security issues

Please **don't** report security problems in public issues. Contact the maintainer privately at
**[SECURITY CONTACT: to be added by the maintainer]**.

## License

Scrollback's license is **to be decided**. It will be chosen before the repository goes public.
Until then, please open an issue before starting a large contribution so we can talk about it.
