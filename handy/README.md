# Jellyfin Seliku Handy (experimental)

*Part of the [Jellyfin Seliku](../README.md) repository: TV app (root), phone app (`handy/`), web interface (`web/`).*

An Android phone app for [Jellyfin](https://jellyfin.org) with the same logic and look as
Jellyfin Seliku TV (the app in the root of this repository) – built for our own family and shared for anyone who
wants to try it.

> **Experimental.** A personal project. It works for us, but expect rough edges. The interface is in German.

Both apps keep their state in your Jellyfin account (display preferences of the client `seliku`), so whatever you start
on the TV continues on the phone and the other way round: Continue Watching, Continue Listening (audiobooks and podcasts),
your list, "not interested", radio favourites and the settings.

## What it does

- **Home screen like the TV app**: featured banner with personal recommendations (scored from your own watch history,
  with missing titles you can request through Seerr), Continue Watching with **one entry per series** (the episode you
  last started, or the next one), favourites, Live TV, "New in …", collections, "True stories".
- **Libraries** with Recommended/Library tabs, genre rows, sorting and filters; detail pages for movies, series, seasons
  and episodes with your list, watched and your own 1–5 star rating; title menu (long press) with play next, remove
  from Continue Watching, play from start, random episode, play on another device and delete (switchable, only where
  Jellyfin allows it).
- **Player** (Media3 ExoPlayer with FFmpeg audio for AC3/DTS): direct play, HLS transcoding as fallback, resume, skip
  intro/credits (media segments), next-episode countdown, audio language remembered per series only, glass clock.
- **Seerr built in**: "Entdecken", search shows "not here yet – request", request state on the tiles. Uses the Seerr
  bridge from the TV repo (`server/`), no second sign-in.
- **Live TV** in language/number blocks, programme guide with recording.
- **Music, internet radio, audiobooks and podcasts** with background playback, notification controls, speed and sleep
  timer. Audiobook and podcast positions are stored in your account and shared with the TV app.
- **Play on another device**: send a title to the TV app (Jellyfin remote control).
- **Sign-in** with user/password, **Quick Connect**, or **SSO** when the server uses the
  [SSO plugin](https://github.com/9p4/jellyfin-plugin-sso) – the button and provider are read from the server's login
  disclaimer, nothing is built in.
- **Self-update**: from your own server (`<server>/web/seliku/handy-version.json` + `seliku-handy.bin`) or from the
  releases of this repository (tags `handy-v…`).

No server address is built into the app: you enter yours on first start.

## Install

Download `jellyfin-seliku-handy.apk` from the newest `handy-v…` [release](https://github.com/seliku/jellyfin-seliku-tv/releases), allow "install unknown apps" once, done. Later updates appear in the
app under "Mehr".

## Build

Android SDK 36, JDK 21. Signing is read from Gradle properties (e.g. `~/.gradle/gradle.properties`):

```
seliku.handy.store=/path/to/keystore.jks
seliku.handy.passwort=…
```

then `./gradlew assembleRelease` in this folder (it is a Gradle project of its own).

## License

GPL-2.0, like the rest of the repository. See [LICENSE](../LICENSE).
