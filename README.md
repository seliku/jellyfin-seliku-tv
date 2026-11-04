# Jellyfin Seliku TV (experimental)

A fork of the official [Jellyfin Android TV app](https://github.com/jellyfin/jellyfin-androidtv) with a
streaming-service style home screen, built for our own living room – and shared for anyone who wants to try it.

> **Experimental.** This is a personal project. It works for us every day, but it still contains
> settings that are specific to our home (see below). Expect rough edges. The user interface is **German only** for now.

## What is different

- **Home screen like the big streaming services**: a featured banner with personal recommendations (scored from your
  own watch history), poster rows, and tiles that expand on focus and play the **trailer** after a few seconds.
- **Navigation for remotes**: a slim side bar that opens as an "island" with Back, Netflix-like Back behaviour
  (tabs → side bar → close), no accidental focus jumps.
- **Per-library start pages** (Recommended / Library) with genre rows, "Because you watched …", "Rediscover", and a genre
  filter that combines.
- **Seerr built in**: browse what is missing ("Entdecken"), request with one click, see the state on the tiles
  ("Angefragt", "Gleich verfügbar", "Jetzt ansehen"). Works either with a small server-side helper that uses the Jellyfin
  login (no second sign-in) or by connecting your own Seerr (Plex PIN or user/password).
- **Music**: playlists first, automatic mixes, A–Z bar, **internet radio** (radio-browser.info) with favourites synced to
  your Jellyfin account, zapping with left/right, and an idle screen (dimmed with clock, backdrop slideshow, or muted trailers).
- **Player**: optional "slim" controls – OK pauses, left/right seeks (live TV: switch channel) without popping up the
  overlay; controls come with Up.
- **Series pages**: theme music (if `theme.mp3` exists), trailer button, ⋮ menu, "Remove from Continue Watching"
  (also for Next Up).
- Every addition can be switched off in the in-app settings ("Seliku-Einstellungen").

## Things that are still personal / hard-coded

- **Self-update** checks `https://jellyfin.example.org/seliku-app/version.json` (`SelikuUpdate.kt`, `ABLAGE`). If you build your
  own APK, change or remove this – otherwise the app offers our builds as updates.
- **Application id** `ch.seliku.jellyfin.tv`, name and logo "Seliku".
- **Seerr helper**: the app first looks for `<your-jellyfin>/seerr` (or port 8097 on the same host). The helper is a small
  Python service we run next to Jellyfin; it is not part of this repo yet.
- **Trailers** are expected as local files (`<Title> (<Year>)-trailer.mp4`, e.g. downloaded with Trailarr). YouTube links from
  metadata are not played.
- German texts throughout; no translations yet.
- Built and tested on a Chromecast with Google TV and the Android TV emulator only.

## Building

Same as upstream (`./gradlew assembleRelease`). Signing is read from `keystore.*`/`signing.*` Gradle properties; without them you
get an unsigned build. The version code is derived from the build time.

## License

GPL-2.0, like the original Jellyfin Android TV app. All credit for the foundation goes to the Jellyfin team and contributors.
Our own code lives mainly in `app/src/main/java/org/jellyfin/androidtv/seliku/`.

---

*Kurz auf Deutsch:* Unsere eigene Fernseh-App für Jellyfin mit Bedienung wie bei den Streamingdiensten. Experimentell, mit
noch fest eingebauten persönlichen Einstellungen (Update-Adresse, Namen, Seerr-Vermittler). Wer mag, darf sie gern ausprobieren.

---

<details><summary>Original Jellyfin Android TV README</summary>


<h1 align="center">Jellyfin Android TV</h1>
<h3 align="center">Part of the <a href="https://jellyfin.org">Jellyfin Project</a></h3>

---

<p align="center">
<img alt="Logo banner" src="https://raw.githubusercontent.com/jellyfin/jellyfin-ux/master/branding/SVG/banner-logo-solid.svg?sanitize=true"/>
<br/><br/>
<a href="https://github.com/jellyfin/jellyfin-androidtv">
<img alt="GPL 2.0 License" src="https://img.shields.io/github/license/jellyfin/jellyfin-androidtv.svg"/>
</a>
<a href="https://github.com/jellyfin/jellyfin-androidtv/releases">
<img alt="Current Release" src="https://img.shields.io/github/release/jellyfin/jellyfin-androidtv.svg"/>
</a>
<a href="https://translate.jellyfin.org/projects/jellyfin-android/jellyfin-androidtv/">
<img alt="Translation Status" src="https://translate.jellyfin.org/widgets/jellyfin-android/-/jellyfin-androidtv/svg-badge.svg"/>
</a>
<br/>
<a href="https://opencollective.com/jellyfin">
<img alt="Donate" src="https://img.shields.io/opencollective/all/jellyfin.svg?label=backers"/>
</a>
<a href="https://features.jellyfin.org">
<img alt="Feature Requests" src="https://img.shields.io/badge/fider-vote%20on%20features-success.svg"/>
</a>
<a href="https://matrix.to/#/+jellyfin:matrix.org">
<img alt="Chat on Matrix" src="https://img.shields.io/matrix/jellyfin:matrix.org.svg?logo=matrix"/>
</a>
<a href="https://www.reddit.com/r/jellyfin">
<img alt="Join our Subreddit" src="https://img.shields.io/badge/reddit-r%2Fjellyfin-%23FF5700.svg"/>
</a>
<br/>
<a href="https://play.google.com/store/apps/details?id=org.jellyfin.androidtv">
<img width="153" alt="Jellyfin on Google Play" src="https://jellyfin.org/images/store-icons/google-play.png"/>
</a>
<a href="https://www.amazon.com/gp/aw/d/B07TX7Z725">
<img width="153" alt="Jellyfin on Amazon Appstore" src="https://jellyfin.org/images/store-icons/amazon.png"/>
</a>
<a href="https://f-droid.org/en/packages/org.jellyfin.androidtv/">
<img width="153" alt="Jellyfin on F-Droid" src="https://jellyfin.org/images/store-icons/fdroid.png"/>
</a>
<br/>
<a href="https://repo.jellyfin.org/releases/client/androidtv/">Download archive</a>
</p>

Jellyfin Android TV is a Jellyfin client for Android TV, Nvidia Shield, and Amazon Fire TV devices.
We welcome all contributions and pull requests! If you have a larger feature in mind please open an
issue so we can discuss the implementation before you start. 

## Translating

Translations can be improved very easily from our
[Weblate](https://translate.jellyfin.org/projects/jellyfin-android/jellyfin-androidtv) instance.
Look through the following graphic to see if your native language could use some work!

<a href="https://translate.jellyfin.org/engage/jellyfin-android/">
<img alt="Detailed Translation Status" src="https://translate.jellyfin.org/widgets/jellyfin-android/-/jellyfin-androidtv/multi-auto.svg"/>
</a>

## Build Process

### Dependencies

- Android Studio

### Build

1. Clone or download this repository

   ```sh
   git clone https://github.com/jellyfin/jellyfin-androidtv.git
   cd jellyfin-androidtv
   ```

2. Open the project in Android Studio and run it from there or build an APK directly through Gradle:

   ```sh
   ./gradlew assembleDebug
   ```
   
   Add the Android SDK to your PATH environment variable or create the ANDROID_SDK_ROOT variable for
   this to work.

### Deploy to device/emulator

   ```sh
   ./gradlew installDebug
   ```

*You can also replace the "Debug" with "Release" to get an optimized release binary.*

</details>
