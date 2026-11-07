#!/usr/bin/env python3
"""
Theme music for series (like Plex): puts a «theme.mp3» into every series folder that has none.
Source is Plex's free theme collection tvthemes.plexapp.com/<TVDB id>.mp3. Jellyfin picks up theme.mp3 in
the series folder by itself; the TV app plays it quietly when a series page opens. The file lives in the
series folder, so it goes away together with the series.

Existing theme.mp3 files are never touched; series without a match are not asked again for 30 days.

Usage: seliku-theme-music.py [--dry-run]
Environment: JELLYFIN_URL, JELLYFIN_API_KEY, optional PATH_MAP (see seliku_common.py)
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from seliku_common import jellyfin, local_path, refresh_library  # noqa: E402

NO_MATCH = os.environ.get("STATE_DIR", "/var/lib/seliku") + "/theme-music-no-match.json"


def main():
    dry = "--dry-run" in sys.argv
    series = jellyfin("/Items", Recursive="true", IncludeItemTypes="Series", Fields="Path,ProviderIds")["Items"]
    try:
        no_match = json.load(open(NO_MATCH))
    except Exception:
        no_match = {}
    now = time.time()
    no_match = {k: v for k, v in no_match.items() if now - v < 30 * 86400}
    new = 0
    for s in series:
        path = local_path(s.get("Path") or "")
        tvdb = (s.get("ProviderIds") or {}).get("Tvdb")
        if not path or not tvdb or not os.path.isdir(path) or tvdb in no_match:
            continue
        target = os.path.join(path, "theme.mp3")
        if os.path.exists(target):
            continue
        if dry:
            print("would look for:", s["Name"], tvdb)
            continue
        try:
            with urllib.request.urlopen(f"http://tvthemes.plexapp.com/{tvdb}.mp3", timeout=30) as r:
                if "audio" not in (r.headers.get("Content-Type") or ""):
                    raise urllib.error.HTTPError(r.url, 404, "", None, None)
                data = r.read()
            with open(target, "wb") as f:
                f.write(data)
            new += 1
            print("theme music:", s["Name"])
        except Exception:
            no_match[tvdb] = now
        time.sleep(0.5)
    if dry:
        return
    os.makedirs(os.path.dirname(NO_MATCH), exist_ok=True)
    json.dump(no_match, open(NO_MATCH, "w"))
    print(f"{time.strftime('%F %T')} new theme music: {new}, without match: {len(no_match)}")
    if new:
        refresh_library()


if __name__ == "__main__":
    main()
