# Jellyfin Seliku Web (experimental)

*Part of the [Jellyfin Seliku](../README.md) repository: TV app (root), phone app (`handy/`), web interface (`web/`).*

A web interface for [Jellyfin](https://jellyfin.org) with the same logic and look as
the Seliku TV app (root of this repository) and the phone app ([`handy/`](../handy)). Plain JavaScript modules and CSS, no build step.

> **Experimental.** A personal project, interface in German.

It shares its state with the apps through your Jellyfin account (display preferences of the client `seliku`):
Continue Watching (one entry per series), Continue Listening for audiobooks and podcasts, your list, ratings,
"not interested", radio favourites and settings.

## Features

- Home screen with personal banner, Continue Watching, favourites, Live TV, "New in …", collections
- Libraries, detail pages, search (Jellyfin and Seerr), "Entdecken" and requests via the Seerr bridge of the TV repo
- Player with HLS (hls.js), resume, skip intro/credits, next episode
- Live TV in blocks, programme guide with recording; music, internet radio, audiobooks, podcasts
- Sign-in with password, Quick Connect, or SSO when the server uses the SSO plugin (button read from the login disclaimer)
- Installable as an app (manifest, icons), works at phone width

## Install on a Jellyfin server

Copy the files of this folder (without `server/` and this README) to `<jellyfin web dir>/seliku/`, e.g. `/usr/share/jellyfin/web/seliku/`. It is then
reachable at `<server>/web/seliku/`.

Optional, to make it the default start page: `server/seliku-web-einbauen` copies the files from `/opt/seliku-web` and
adds a small redirect to Jellyfin's `index.html` (the admin dashboard, `?klassisch`, embedded pages and the Jellyfin apps
keep the normal interface). `server/99seliku-web` is an apt hook that re-applies it after every Jellyfin update.
Create `/opt/seliku-web/AUS` and run the script again to switch the redirect off.

## License

GPL-2.0, like the rest of the repository. `hls.min.js` is [hls.js](https://github.com/video-dev/hls.js) (Apache-2.0).
