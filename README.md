# Jellyfin Seliku TV (experimental)

A fork of the official [Jellyfin Android TV app](https://github.com/jellyfin/jellyfin-androidtv) with a
streaming-service style home screen, built for our own living room – and shared for anyone who wants to try it.

> **Experimental.** This is a personal project. It works for us every day, but it still contains
> settings that are specific to our home (see below). Expect rough edges. The interface is in German and English
> (follows the system language).

## What is different

- **Home screen like the big streaming services**: a featured banner with personal recommendations (scored from your
  own watch history), poster rows with a fixed "stage" at the start of each row that plays the **trailer** after a few
  seconds, while the other posters glide in behind it.
- **Continue Watching** with one entry per series (resumed episode or next up). Optionally only what was started on
  this device (each TV remembers what was played on it).
- **Quick start**: the last home screen and side bar are shown right away; fresh rows replace them quietly once the
  server has answered (rows load in parallel, Continue Watching first).
- **Ratings**: Rotten Tomatoes score, TMDb score and your **own 1–5 star rating** (stored in your Jellyfin account).
- **"True stories" row** built from TMDb keywords (based on a true story, biography, true crime …).
- **Navigation for remotes**: a slim side bar that opens as an "island" with Back, Netflix-like Back behaviour
  (tabs → side bar → close), no accidental focus jumps. Weekday and time at the bottom of the side bar. The tile you
  scrolled past peeks out between side bar and row, so you can see there is more to the left. The Live TV recordings
  folder is not listed twice (it is under Live TV → Recordings, where the scheduled ones come last).
- **Per-library start pages** (Recommended / Library) with genre rows, "Because you watched …", "Rediscover", and a genre
  filter that combines.
- **Seerr built in**: browse what is missing ("Entdecken"), request with one click, see the state on the tiles
  ("Requested", "Available soon", "Watch now"). Works with the Seerr bridge from `server/` (uses the Jellyfin
  sign-in, no second sign-in) or by connecting your own Seerr (Plex PIN or user/password).
- **Music**: playlists first, automatic mixes, favourites (playlists, mixes, radio stations), A–Z bar, **internet radio**
  (radio-browser.info) with favourites synced to your Jellyfin account, zapping with left/right, and an idle screen
  (dimmed with clock, backdrop slideshow, or muted trailers with their title).
- **Player**: optional "slim" controls – OK pauses, left/right seeks (live TV: switch channel) without popping up the
  overlay; controls come with Up. Previous/next episode in the main bar, tooltips on the buttons. Intros and credits
  are skipped as set in the player settings (with the Intro Skipper plugin on the server). After an episode the next
  one starts by itself, **waits for OK** (a quiet "Next up" screen, nice for falling asleep – one blind press on OK
  plays on), or playback stops.
- **Live TV by language**: channels in number blocks (1–99, 100–199 …) get tabs on the Live TV page, e.g. one per
  language; holding left/right in the player zaps within the block. Block names come from the Jellyfin account
  (see Server setup), otherwise "Channels 100–199".
- **Series pages**: theme music (if `theme.mp3` exists), trailer button, ⋮ menu, "Remove from Continue Watching"
  (also for Next Up). Long descriptions open in full with "Read more".
- Every addition can be switched off in the app's own settings.

## Server setup

The app runs against any Jellyfin server, but several features need something on the server side:

| Feature in the app | Needs on the server |
|---|---|
| Rotten Tomatoes for movies | **OMDb** plugin (ships with Jellyfin) enabled as metadata source |
| "True stories" row, TMDb score | **TMDb** plugin (ships with Jellyfin) |
| Skipping intros and credits | **[Intro Skipper](https://github.com/intro-skipper/intro-skipper)** plugin, then in the app: Settings → Playback → Media segment actions → Intros: *Skip* (likewise Outros) |
| Trailers on the tiles and the banner | Local trailers next to the movies, e.g. `Title (Year)-trailer.mp4` – [Trailarr](https://github.com/nandyalu/trailarr) downloads them. YouTube links from metadata are not played. With "Clip when there is no trailer" on, the app shows 90 s from the movie instead. |
| Discover and request (Seerr) | [Seerr](https://github.com/seerr-team/seerr) plus the **Seerr bridge** (`server/seliku-seerr.py`) – or every user signs in to Seerr in the app |
| Rotten Tomatoes for series | the Seerr bridge (Seerr knows the scores) |
| Theme music on series pages | `theme.mp3` in the series folder – `server/seliku-theme-music.py` fetches them |
| Song lyrics on the music screen | `.lrc` files next to the songs – Jellyfin's own lyrics fetcher or `server/seliku-lyrics.py` |

`server/setup.sh` checks all of this and sets up what is missing, asking before every step:

```sh
git clone https://github.com/seliku/jellyfin-seliku-tv.git
cd jellyfin-seliku-tv/server
sudo ./setup.sh --check   # only report
sudo ./setup.sh           # set up step by step
```

Run it on the machine that runs Jellyfin. It needs `curl`, `python3` (3.10 or newer) and, for the helpers, systemd.
You need a Jellyfin API key (Dashboard → API Keys) and, for the Seerr bridge, the Seerr API key
(Seerr → Settings → General). The script

- enables TMDb and OMDb if they are off, and installs Intro Skipper from its repository (Jellyfin restarts only
  when you agree and nothing is playing),
- checks that no library has OMDb or Intro Skipper switched off,
- installs the helpers to `/opt/seliku-server`, keeps the Jellyfin address and key in `/etc/seliku/server.env`
  (readable by root only) and the Seerr key in `/etc/seliku-seerr/seerr.key`,
- starts the Seerr bridge as `seliku-seerr.service` on port 8097, and theme music and lyrics as daily timers
  (`seliku-theme-music.timer`, `seliku-lyrics.timer`).

**Seerr bridge.** The app looks for it at `<your Jellyfin address>/seerr`, then at port 8097 on the same host. Behind a
reverse proxy, route `/seerr` to the bridge:

```
# Caddy
handle_path /seerr/* {
	reverse_proxy 127.0.0.1:8097
}
# nginx
location /seerr/ { proxy_pass http://127.0.0.1:8097/; }
```

The bridge checks each request's Jellyfin token, acts in Seerr as the Seerr user with the same name (created with
Seerr's default permissions if missing) and lets through only what the app needs. The Seerr API key never leaves the
server. To give a Jellyfin user a different Seerr account, or read-only access, add
`/etc/seliku-seerr/mapping.json`: `{"jellyfin-name": 5, "guest": null}`.

**Live TV tab names.** Number your channels in blocks (e.g. German 1–99, Albanian 100–199, Greek 200–299) and store the
names per user as Jellyfin display preference `seliku-livetv` (client `seliku`), custom pref `gruppen`, one line per block:
`1=Deutsch`, `100=Shqip`, `200=Ελληνικά`. Without it the tabs read "Channels 100–199".

**Jellyfin in Docker.** Run the script on the Docker host and tell it how the media paths inside the container map to
the host, so theme music and lyrics land in the right folders:
`sudo PATH_MAP="/media=/srv/media;/music=/mnt/music" ./setup.sh`

## Things that are still personal / hard-coded

- **Self-update and server address** come from private Gradle properties (`seliku.updateUrl`, `seliku.server` in
  `~/.gradle/gradle.properties`). Without them your build simply has no self-update and an empty server field.
- **Application id** `ch.seliku.jellyfin.tv`, name and logo "Seliku".
- Built and tested on a Chromecast with Google TV and the Android TV emulator only.

## Building

Same as upstream (`./gradlew assembleRelease`). Signing is read from `keystore.*`/`signing.*` Gradle properties; without them you
get an unsigned build. The version code is derived from the build time.

## License

GPL-2.0, like the original Jellyfin Android TV app. All credit for the foundation goes to the Jellyfin team and contributors.
Our own code lives mainly in `app/src/main/java/org/jellyfin/androidtv/seliku/`, the server helpers in `server/`.

---

*Kurz auf Deutsch:* Unsere eigene Fernseh-App für Jellyfin mit Bedienung wie bei den Streamingdiensten. Experimentell, mit
noch fest eingebauten persönlichen Einstellungen (Name, Logo, Selbst-Update). Was der Server dazu braucht (Plugins,
Seerr-Vermittler, Titelmusik, Liedtexte), richtet `server/setup.sh` ein. Wer mag, darf sie gern ausprobieren.

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
