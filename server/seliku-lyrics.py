#!/usr/bin/env python3
"""
Song lyrics for the Jellyfin music libraries (lrclib.net), saved as «<song>.lrc» next to the song.

Jellyfin can fetch lyrics itself, but gives up for good when lrclib.net answers 503 (overloaded). This helper
asks patiently (pauses, retries). Songs that already have lyrics are skipped; songs without a match are not
asked again for 30 days. .lrc files whose song is gone are removed again, so lyrics leave with their song.

Usage: seliku-lyrics.py [--dry-run] [search term]
Environment: JELLYFIN_URL, JELLYFIN_API_KEY, optional PATH_MAP (see seliku_common.py)
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from seliku_common import jellyfin, local_path, refresh_library  # noqa: E402

NO_MATCH = os.environ.get("STATE_DIR", "/var/lib/seliku") + "/lyrics-no-match.json"
UA = {"User-Agent": "Seliku-Lyrics/1.0 (https://github.com/seliku/jellyfin-seliku-tv)"}


def has_lyrics(item_id):
    try:
        jellyfin(f"/Audio/{item_id}/Lyrics")
        return True
    except urllib.error.HTTPError:
        return False


def lrclib(item):
    duration = round((item.get("RunTimeTicks") or 0) / 1e7)
    artists = ";".join(item.get("Artists") or []) or item.get("AlbumArtist") or ""
    first = (item.get("Artists") or [artists])[0].split(" feat.")[0].split(";")[0].strip()
    attempts = [
        ("get", {"track_name": item["Name"], "artist_name": artists, "album_name": item.get("Album", ""), "duration": duration}),
        ("get", {"track_name": item["Name"], "artist_name": first, "duration": duration}),
        ("search", {"track_name": item["Name"], "artist_name": first}),
    ]
    for kind, q in attempts:
        for round_ in range(4):
            try:
                url = f"https://lrclib.net/api/{kind}?" + urllib.parse.urlencode(q)
                with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
                    d = json.loads(r.read())
                break
            except urllib.error.HTTPError as e:
                if e.code == 404:
                    d = None
                    break
                time.sleep(5 * (round_ + 1))  # 503/429: wait patiently
            except Exception:
                time.sleep(5 * (round_ + 1))
        else:
            continue
        if kind == "search" and isinstance(d, list):
            # only take it if the duration matches within ±3 s
            d = next((x for x in d if abs((x.get("duration") or 0) - duration) <= 3 and (x.get("syncedLyrics") or x.get("plainLyrics"))), None)
        if d and (d.get("syncedLyrics") or d.get("plainLyrics")):
            return d.get("syncedLyrics") or d.get("plainLyrics")
    return None


def music_folders():
    return [local_path(p) for f in jellyfin("/Library/VirtualFolders")
            if f.get("CollectionType") == "music" for p in f.get("Locations") or []]


def main():
    args = [a for a in sys.argv[1:] if a != "--dry-run"]
    dry = "--dry-run" in sys.argv
    try:
        no_match = json.load(open(NO_MATCH))
    except Exception:
        no_match = {}
    now = time.time()
    no_match = {k: v for k, v in no_match.items() if now - v < 30 * 86400}
    q = {"Recursive": "true", "IncludeItemTypes": "Audio", "Fields": "Path"}
    if args:
        q["SearchTerm"] = args[0]
    songs = jellyfin("/Items", **q)["Items"]
    new = todo = 0
    for it in songs:
        path = local_path(it.get("Path") or "")
        lrc = os.path.splitext(path)[0] + ".lrc"
        if not path or os.path.exists(lrc) or it["Id"] in no_match or has_lyrics(it["Id"]):
            continue
        if dry:
            todo += 1
            continue
        text = lrclib(it)
        if text:
            with open(lrc, "w", encoding="utf-8") as f:
                f.write(text)
            new += 1
            print("lyrics:", path)
        else:
            no_match[it["Id"]] = now
        time.sleep(1)
    # Clean up: .lrc without its song next to it (song deleted or renamed)
    removed = 0
    for root in music_folders():
        for folder, _, files in os.walk(root):
            stems = {os.path.splitext(f)[0] for f in files if not f.lower().endswith(".lrc")}
            for f in files:
                if f.lower().endswith(".lrc") and os.path.splitext(f)[0] not in stems:
                    if dry:
                        print("would remove orphaned:", os.path.join(folder, f))
                    else:
                        os.remove(os.path.join(folder, f))
                    removed += 1
    if dry:
        print(f"songs to look up: {todo}, orphaned .lrc: {removed}")
        return
    os.makedirs(os.path.dirname(NO_MATCH), exist_ok=True)
    json.dump(no_match, open(NO_MATCH, "w"))
    print(f"{time.strftime('%F %T')} new lyrics: {new}, orphaned removed: {removed}, without match: {len(no_match)}")
    if new:
        refresh_library()


if __name__ == "__main__":
    main()
