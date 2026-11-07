"""Shared bits of the Seliku server helpers: Jellyfin access and path mapping."""
import json
import os
import urllib.parse
import urllib.request

JELLYFIN = os.environ.get("JELLYFIN_URL", "http://127.0.0.1:8096").rstrip("/")
API_KEY = os.environ.get("JELLYFIN_API_KEY", "")
HEADERS = {"Authorization": f'MediaBrowser Token="{API_KEY}", Client="seliku-server", Device="server", DeviceId="seliku-server", Version="1"'}

# PATH_MAP translates paths as Jellyfin sees them into paths on this machine (Jellyfin in Docker):
# "/media=/srv/media;/music=/mnt/music". Without it, the paths are used as they are.
_MAP = [tuple(p.split("=", 1)) for p in os.environ.get("PATH_MAP", "").split(";") if "=" in p]


def local_path(path: str) -> str:
    for src, dst in _MAP:
        if path == src or path.startswith(src.rstrip("/") + "/"):
            return dst.rstrip("/") + path[len(src.rstrip("/")):]
    return path


def jellyfin(path: str, method: str = "GET", **query):
    url = JELLYFIN + path + ("?" + urllib.parse.urlencode(query) if query else "")
    with urllib.request.urlopen(urllib.request.Request(url, method=method, headers=HEADERS), timeout=60) as r:
        data = r.read()
        return json.loads(data) if data else None


def refresh_library():
    """Let Jellyfin pick up new files right away instead of at the next scheduled scan."""
    jellyfin("/Library/Refresh", method="POST")
