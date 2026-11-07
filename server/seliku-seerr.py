#!/usr/bin/env python3
"""
Seerr bridge for the Seliku TV app.

The app does not sign in to Seerr separately. It sends its Seerr requests here together with the Jellyfin
token of the signed-in user. The bridge checks the token with Jellyfin, finds the matching Seerr user (same
name, else the mapping file, else it creates one) and asks Seerr with the server API key on behalf of that
user (X-API-User). The API key never leaves the server. Only the routes the app needs are let through.

Environment:  SEERR_URL     Seerr address, e.g. http://127.0.0.1:5055   (required)
              JELLYFIN_URL  default http://127.0.0.1:8096
              PORT          default 8097
              USER_EMAIL_DOMAIN  domain for e-mail addresses of Seerr users created here (default jellyfin.local)
Files:        /etc/seliku-seerr/seerr.key        Seerr API key (mode 600; under systemd passed as credential)
              /etc/seliku-seerr/mapping.json     optional {"jellyfin-name": seerr-user-id | null}  (null = read only)
"""
import json
import os
import re
import secrets
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

JELLYFIN = os.environ.get("JELLYFIN_URL", "http://127.0.0.1:8096").rstrip("/")
SEERR = os.environ["SEERR_URL"].rstrip("/")
PORT = int(os.environ.get("PORT", "8097"))
EMAIL_DOMAIN = os.environ.get("USER_EMAIL_DOMAIN", "jellyfin.local")
CONFIG = "/etc/seliku-seerr"
KEY = open(os.path.join(os.environ.get("CREDENTIALS_DIRECTORY", CONFIG), "seerr.key")).read().strip()

ALLOWED = [
    ("GET", r"/api/v1/(discover/[a-z/]+(/\d+)?|search|movie/\d+(/(recommendations|similar|ratings))?|tv/\d+(/(recommendations|similar|ratings|season/\d+))?|person/\d+(/combined_credits)?|genres/(movie|tv)|auth/me|request|request/\d+|status)"),
    ("POST", r"/api/v1/request"),
    ("DELETE", r"/api/v1/request/\d+"),
]

_lock = threading.Lock()
_tokens: dict[str, tuple[str, float]] = {}     # token -> (Jellyfin name, time)
_users: tuple[list, float] = ([], 0.0)         # Seerr user list, time
_ids: dict[str, int | None] = {}               # Jellyfin name -> Seerr id


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def mapping_file() -> dict:
    try:
        with open(f"{CONFIG}/mapping.json") as f:
            return {k.lower(): v for k, v in json.load(f).items()}
    except FileNotFoundError:
        return {}


def seerr(method: str, path: str, body: bytes | None = None, as_user: int | None = None):
    headers = {"X-Api-Key": KEY, "Accept": "application/json"}
    if as_user is not None:
        headers["X-API-User"] = str(as_user)
    if body is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(SEERR + path, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def jellyfin_name(token: str) -> str | None:
    with _lock:
        t = _tokens.get(token)
        if t and time.time() - t[1] < 600:
            return t[0]
    # Newer Jellyfin versions only accept the Authorization header (EnableLegacyAuthorization=false)
    req = urllib.request.Request(f"{JELLYFIN}/Users/Me", headers={"Authorization": f'MediaBrowser Token="{token}"'})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            name = json.load(r).get("Name")
    except Exception:
        return None
    if name:
        with _lock:
            _tokens[token] = (name, time.time())
    return name


def seerr_users(fresh: bool = False) -> list:
    global _users
    with _lock:
        if not fresh and time.time() - _users[1] < 300:
            return _users[0]
    code, data = seerr("GET", "/api/v1/user?take=1000")
    users = json.loads(data).get("results", []) if code == 200 else []
    with _lock:
        _users = (users, time.time())
    return users


def seerr_id(name: str) -> int | None:
    """Seerr user for a Jellyfin name; created if there is none. None = read only."""
    key = name.lower()
    mapping = mapping_file()
    if key in mapping:
        return mapping[key]
    with _lock:
        if key in _ids:
            return _ids[key]
    for fresh in (False, True):
        for u in seerr_users(fresh):
            names = {str(u.get(k) or "").strip().lower() for k in ("displayName", "username", "plexUsername", "jellyfinUsername")}
            names.add(str(u.get("email") or "").split("@")[0].lower())
            if key in names:
                with _lock:
                    _ids[key] = u["id"]
                return u["id"]
    # Create with Seerr's default permissions; nobody needs the password, but Seerr wants one
    body = json.dumps({"email": f"{re.sub('[^a-z0-9._-]', '', key) or 'user'}@{EMAIL_DOMAIN}",
                       "username": name, "password": secrets.token_urlsafe(24)}).encode()
    code, data = seerr("POST", "/api/v1/user", body)
    if code in (200, 201):
        uid = json.loads(data)["id"]
        log(f"Seerr user created: {name} -> {uid}")
        with _lock:
            _ids[key] = uid
        seerr_users(True)
        return uid
    log(f"Creating Seerr user {name} failed: {code} {data[:200]!r}")
    return None


class Bridge(BaseHTTPRequestHandler):
    server_version = "seliku-seerr/1"

    def log_message(self, *a):
        pass

    def reply(self, code: int, data: bytes):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def error(self, code: int, text: str):
        self.reply(code, json.dumps({"message": text}).encode())

    def token(self) -> str | None:
        for k in ("X-Emby-Token", "X-MediaBrowser-Token"):
            if self.headers.get(k):
                return self.headers[k]
        m = re.search(r'Token="([^"]+)"', self.headers.get("Authorization", ""))
        return m.group(1) if m else None

    def handle_request(self, method: str):
        path = self.path
        bare = path.split("?")[0]
        if not any(m == method and re.fullmatch(p, bare) for m, p in ALLOWED):
            return self.error(404, "not allowed")
        tok = self.token()
        name = jellyfin_name(tok) if tok else None
        if not name:
            return self.error(401, "Jellyfin sign-in missing")
        uid = seerr_id(name)
        if uid is None and method != "GET":
            return self.error(403, "This account may only browse Seerr")
        if bare == "/api/v1/auth/me" and uid is None:
            return self.reply(200, json.dumps({"id": None, "displayName": name}).encode())
        # Nobody may withdraw someone else's request
        if method == "DELETE":
            code, data = seerr("GET", bare)
            if code != 200 or json.loads(data).get("requestedBy", {}).get("id") != uid:
                return self.error(403, "Only your own requests can be withdrawn")
        body = None
        if method == "POST":
            body = self.rfile.read(int(self.headers.get("Content-Length", "0") or 0))
        code, data = seerr(method, path, body, uid)
        if method != "GET":
            log(f"{name} ({uid}) {method} {bare} -> {code}")
        self.reply(code, data)

    def do_GET(self):
        self.handle_request("GET")

    def do_POST(self):
        self.handle_request("POST")

    def do_DELETE(self):
        self.handle_request("DELETE")


if __name__ == "__main__":
    log(f"Seerr bridge on :{PORT} -> {SEERR}")
    ThreadingHTTPServer(("0.0.0.0", PORT), Bridge).serve_forever()
