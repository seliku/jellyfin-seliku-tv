#!/usr/bin/env bash
# Seliku TV – server setup.
#
# Checks and sets up what the app expects from the Jellyfin server: plugins (Intro Skipper, TMDb, OMDb), the
# Seerr bridge (requests without a second sign-in), theme music for series and song lyrics.
# Run it as root on the machine that runs Jellyfin (with Docker: on the Docker host, see PATH_MAP below).
# Every step asks first; nothing is removed or overwritten. Running it again is safe.
#
#   sudo ./setup.sh            ask step by step
#   sudo ./setup.sh --check    only report, change nothing
#   sudo ./setup.sh --yes      answer every question with yes (values from the environment, see below)
#
# Environment (otherwise asked for):
#   JELLYFIN_URL       default http://127.0.0.1:8096
#   JELLYFIN_API_KEY   an API key from Dashboard → API Keys
#   SEERR_URL          e.g. http://127.0.0.1:5055 (empty: skip the Seerr bridge)
#   SEERR_API_KEY      Seerr → Settings → General → API Key
#   PATH_MAP           only if Jellyfin runs in Docker: "/media=/srv/media;/music=/mnt/music"
#                      (path inside the container = path on this machine)
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
PREFIX=/opt/seliku-server
CONF=/etc/seliku/server.env
CHECK=0
YES=0
for a in "$@"; do
	case "$a" in
		--check) CHECK=1 ;;
		--yes) YES=1 ;;
		-h | --help) sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
		*) echo "unknown option: $a" >&2; exit 2 ;;
	esac
done

bold=$'\e[1m' green=$'\e[32m' yellow=$'\e[33m' red=$'\e[31m' off=$'\e[0m'
[ -t 1 ] || bold= green= yellow= red= off=
step() { echo; echo "${bold}== $*${off}"; }
ok() { echo "  ${green}✓${off} $*"; }
warn() { echo "  ${yellow}!${off} $*"; }
fail() { echo "  ${red}✗${off} $*"; }
info() { echo "    $*"; }

# ask "question" → 0 for yes. --check never changes anything, --yes says yes.
ask() {
	[ $CHECK = 1 ] && { info "(--check: skipped)"; return 1; }
	[ $YES = 1 ] && return 0
	local r
	read -r -p "  $1 [Y/n] " r </dev/tty
	[[ -z "$r" || "$r" =~ ^[YyJj] ]]
}

# value VAR "prompt" [secret]: keeps an existing value, otherwise asks
value() {
	local var=$1 text=$2 secret=${3:-} v
	[ -n "${!var:-}" ] && return 0
	[ $YES = 1 ] && return 0
	if [ -n "$secret" ]; then read -r -s -p "  $text: " v </dev/tty; echo; else read -r -p "  $text: " v </dev/tty; fi
	printf -v "$var" '%s' "$v"
}

have_systemd() { [ -d /run/systemd/system ]; }

# ---------------------------------------------------------------------------------------------------------------
step "Requirements"
for c in curl python3; do command -v $c >/dev/null || { fail "$c is missing – please install it"; exit 1; }; done
python3 -c 'import sys; sys.exit(sys.version_info < (3, 10))' || { fail "python3 3.10 or newer is needed"; exit 1; }
ok "curl and python3 $(python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])')"
if [ $CHECK = 0 ] && [ "$(id -u)" != 0 ]; then fail "please run as root (sudo), or use --check"; exit 1; fi
have_systemd && ok "systemd" || warn "no systemd: the Seerr bridge, theme music and lyrics are skipped"

# Earlier answers (Jellyfin address, key, path map) are kept in $CONF
if [ -r "$CONF" ]; then
	while IFS='=' read -r k v; do
		case "$k" in JELLYFIN_URL | JELLYFIN_API_KEY | PATH_MAP) [ -n "${!k:-}" ] || printf -v "$k" '%s' "$v" ;; esac
	done <"$CONF"
fi

# ---------------------------------------------------------------------------------------------------------------
step "Jellyfin"
JELLYFIN_URL=${JELLYFIN_URL:-http://127.0.0.1:8096}
JELLYFIN_URL=${JELLYFIN_URL%/}
info "address: $JELLYFIN_URL"
value JELLYFIN_API_KEY "API key (Jellyfin Dashboard → API Keys → +)" secret
[ -n "${JELLYFIN_API_KEY:-}" ] || { fail "no API key"; exit 1; }
AUTH="Authorization: MediaBrowser Token=\"$JELLYFIN_API_KEY\""

jf() { curl -fsS -H "$AUTH" "$JELLYFIN_URL$1"; }
jf_post() {
	local data=()
	[ -n "${2:-}" ] && data=(-d "$2")
	curl -sS -o /dev/null -w '%{http_code}' -X POST -H "$AUTH" -H 'Content-Type: application/json' "${data[@]}" "$JELLYFIN_URL$1"
}
py() { python3 -c "import json,sys; d=json.load(sys.stdin); $1"; }

INFO=$(jf /System/Info) || { fail "Jellyfin does not answer, or the API key is wrong"; exit 1; }
ok "Jellyfin $(echo "$INFO" | py 'print(d["Version"])') («$(echo "$INFO" | py 'print(d["ServerName"])')»)"

# ---------------------------------------------------------------------------------------------------------------
step "Plugins"
PLUGINS=$(jf /Plugins)
plugin() { echo "$PLUGINS" | py "p=[x for x in d if x['Name'].lower()=='$1'.lower()]; print(p[0]['Status']+' '+p[0]['Id']+' '+p[0]['Version'] if p else '')"; }

RESTART=0
for name in TMDb OMDb; do
	read -r status id ver _ <<<"$(plugin "$name") _ _ _"
	case "$status" in
		Active) ok "$name active" ;;
		_) warn "$name is not installed (it ships with Jellyfin; reinstall it from the catalogue)" ;;
		*)
			warn "$name is $status"
			[ "$name" = OMDb ] && info "OMDb brings the Rotten Tomatoes rating for movies"
			[ "$name" = TMDb ] && info "TMDb brings tags such as «based on true story» (row «True stories»)"
			if ask "Enable $name?"; then jf_post "/Plugins/$id/$ver/Enable" >/dev/null && ok "enabled" && RESTART=1; fi
			;;
	esac
done

read -r status _ <<<"$(plugin "Intro Skipper") _"
if [ "$status" = Active ]; then
	ok "Intro Skipper active"
else
	warn "Intro Skipper is ${status/_/missing}"
	info "It finds intros and credits; the app then skips them as set in the player settings"
	if [ "$status" = _ ] && ask "Install Intro Skipper (from intro-skipper.org)?"; then
		REPOS=$(jf /Repositories | py "
if not any('intro-skipper' in r['Url'] for r in d):
    d.append({'Name': 'Intro Skipper', 'Url': 'https://intro-skipper.org/manifest.json', 'Enabled': True})
print(json.dumps(d))")
		jf_post /Repositories "$REPOS" >/dev/null
		sleep 3
		GUID=$(jf /Packages | py "print(next((p['guid'] for p in d if p['name'].lower()=='intro skipper'), ''))")
		if [ -n "$GUID" ] && [ "$(jf_post "/Packages/Installed/Intro%20Skipper?assemblyGuid=$GUID")" = 204 ]; then
			ok "installed (active after a restart of Jellyfin)"
			RESTART=1
		else
			fail "installation failed – install it by hand: Dashboard → Plugins → Catalogue"
		fi
	fi
fi

if [ $RESTART = 1 ]; then
	PLAYING=$(jf "/Sessions?activeWithinSeconds=600" | py "print(sum(1 for s in d if s.get('NowPlayingItem')))")
	if [ "$PLAYING" != 0 ]; then
		warn "Jellyfin needs a restart, but $PLAYING stream(s) are playing – restart later (Dashboard → Restart)"
	elif ask "Jellyfin needs a restart. Restart now (nothing is playing)?"; then
		jf_post /System/Restart >/dev/null && ok "restarting"
		info "Intro Skipper analyses the libraries in the background; that takes hours on large libraries"
	fi
fi

# ---------------------------------------------------------------------------------------------------------------
step "Libraries"
jf /Library/VirtualFolders | py "
bad = False
for f in d:
    o = f.get('LibraryOptions') or {}
    for t in o.get('TypeOptions') or []:
        if t['Type'] == 'Movie' and t.get('MetadataFetchers') and 'The Open Movie Database' not in t['MetadataFetchers']:
            print('  ! «%s»: OMDb is switched off as metadata source – no Rotten Tomatoes for movies' % f['Name']); bad = True
    if any('intro' in p.lower() for p in o.get('DisabledMediaSegmentProviders') or []):
        print('  ! «%s»: Intro Skipper is switched off as media segment provider' % f['Name']); bad = True
if not bad:
    print('  ✓ metadata sources and segment providers are on')
" | sed "s/^  ✓/  ${green}✓${off}/; s/^  !/  ${yellow}!${off}/"
info "Trailers: the app plays local trailers only (e.g. «Title (Year)-trailer.mp4» next to the movie)."
info "Trailarr (github.com/nandyalu/trailarr) downloads them automatically."

# ---------------------------------------------------------------------------------------------------------------
install_helpers() {
	install -d -m 755 "$PREFIX"
	install -m 755 "$HERE"/seliku-seerr.py "$HERE"/seliku-theme-music.py "$HERE"/seliku-lyrics.py "$PREFIX"/
	install -m 644 "$HERE"/seliku_common.py "$PREFIX"/
	install -d -m 755 "$(dirname "$CONF")"
	umask 077
	printf 'JELLYFIN_URL=%s\nJELLYFIN_API_KEY=%s\nPATH_MAP=%s\n' "$JELLYFIN_URL" "$JELLYFIN_API_KEY" "${PATH_MAP:-}" >"$CONF"
	umask 022
}

# timer NAME "description" SCRIPT "OnCalendar"
timer() {
	cat >/etc/systemd/system/$1.service <<-EOF
		[Unit]
		Description=$2
		After=network-online.target

		[Service]
		Type=oneshot
		EnvironmentFile=$CONF
		ExecStart=/usr/bin/python3 $PREFIX/$3
		Nice=10
	EOF
	cat >/etc/systemd/system/$1.timer <<-EOF
		[Unit]
		Description=$2 (daily)

		[Timer]
		OnCalendar=*-*-* $4
		RandomizedDelaySec=15m
		Persistent=true

		[Install]
		WantedBy=timers.target
	EOF
	systemctl daemon-reload
	systemctl enable --now $1.timer >/dev/null 2>&1
}

step "Seerr bridge (request movies and series from the app)"
if ! have_systemd; then
	warn "skipped (no systemd)"
elif systemctl is-active -q seliku-seerr 2>/dev/null; then
	ok "seliku-seerr is running"
else
	info "Lets the app browse and request in Seerr with the Jellyfin sign-in – no second sign-in on the TV."
	info "Without it, users can still sign in to Seerr in the app (Plex PIN or user/password)."
	if ask "Set up the Seerr bridge?"; then
		value SEERR_URL "Seerr address (e.g. http://127.0.0.1:5055, empty = skip)"
		SEERR_URL=${SEERR_URL%/}
		if [ -n "$SEERR_URL" ]; then
			value SEERR_API_KEY "Seerr API key (Seerr → Settings → General)" secret
			if ! curl -fsS -o /dev/null -H "X-Api-Key: ${SEERR_API_KEY:-}" "$SEERR_URL/api/v1/settings/main"; then
				fail "Seerr does not accept the address or the key – skipped"
			else
				install_helpers
				install -d -m 755 /etc/seliku-seerr
				(umask 077; printf '%s\n' "$SEERR_API_KEY" >/etc/seliku-seerr/seerr.key)
				cat >/etc/systemd/system/seliku-seerr.service <<-EOF
					[Unit]
					Description=Seerr bridge for the Seliku TV app (sign-in through Jellyfin)
					After=network-online.target
					Wants=network-online.target

					[Service]
					Environment=SEERR_URL=$SEERR_URL JELLYFIN_URL=$JELLYFIN_URL PORT=8097
					ExecStart=/usr/bin/python3 $PREFIX/seliku-seerr.py
					Restart=always
					RestartSec=5
					DynamicUser=yes
					ReadOnlyPaths=/etc/seliku-seerr
					LoadCredential=seerr.key:/etc/seliku-seerr/seerr.key
					NoNewPrivileges=yes

					[Install]
					WantedBy=multi-user.target
				EOF
				systemctl daemon-reload
				systemctl enable --now seliku-seerr >/dev/null 2>&1
				sleep 2
				code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8097/api/v1/status || true)
				[ "$code" = 401 ] && ok "running on port 8097" || fail "not answering (journalctl -u seliku-seerr)"
			fi
		fi
	fi
fi
if have_systemd && systemctl is-enabled -q seliku-seerr 2>/dev/null; then
	info "The app looks for the bridge at <Jellyfin address>/seerr, then at port 8097 of the same host."
	info "Behind a reverse proxy, route /seerr to port 8097, e.g.:"
	info "  Caddy:  handle_path /seerr/* { reverse_proxy 127.0.0.1:8097 }"
	info "  nginx:  location /seerr/ { proxy_pass http://127.0.0.1:8097/; }"
fi

# ---------------------------------------------------------------------------------------------------------------
step "Theme music for series"
if ! have_systemd; then
	warn "skipped (no systemd)"
elif systemctl is-enabled -q seliku-theme-music.timer 2>/dev/null; then
	ok "seliku-theme-music.timer is on"
else
	info "Puts a theme.mp3 (from tvthemes.plexapp.com) into each series folder; the app plays it on the series page."
	info "Needs write access to the series folders from this machine."
	if ask "Fetch theme music daily?"; then
		install_helpers
		timer seliku-theme-music "Seliku: theme music for series" seliku-theme-music.py 05:50
		ok "on – first run tonight (now: systemctl start seliku-theme-music)"
	fi
fi

step "Song lyrics"
if ! have_systemd; then
	warn "skipped (no systemd)"
elif systemctl is-enabled -q seliku-lyrics.timer 2>/dev/null; then
	ok "seliku-lyrics.timer is on"
else
	info "Saves lyrics from lrclib.net as .lrc next to the songs (the music screen shows them)."
	if ask "Fetch song lyrics daily?"; then
		install_helpers
		timer seliku-lyrics "Seliku: song lyrics from lrclib.net" seliku-lyrics.py 03:10
		ok "on – first run tonight (now: systemctl start seliku-lyrics)"
	fi
fi

# ---------------------------------------------------------------------------------------------------------------
step "In the app"
info "Skip intros without a button: Settings → Playback → Media segment actions → Intros (and Outros): «Skip»."
info "All Seliku additions can be switched off in the app's own settings."
echo
