"""Signal Server v0.3: isolated personal media sync, no AIBOX/P14 dependencies.

Runs behind an authenticated HTTPS reverse proxy in production. Python stdlib only.
All data and credentials reside outside git. Tests use synthetic fixtures only.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import tempfile
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

MAX_JSON = 128 * 1024
MAX_CHUNK = 8 * 1024 * 1024
MAX_THUMB = 3 * 1024 * 1024
MAX_FILE = 500 * 1024 ** 3
HEX64 = re.compile(r"^[a-f0-9]{64}$")
ID = re.compile(r"^[a-zA-Z0-9_.:-]{1,160}$")


def token_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


class MediaService:
    def __init__(self, root: str | Path, enrollment_key: str):
        if len(enrollment_key) < 16:
            raise ValueError("enrollment key must be at least 16 characters")
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        for name in ("objects", "thumbs", "staging"):
            (self.root / name).mkdir(exist_ok=True)
        self.enrollment_hash = token_hash(enrollment_key)
        self.db_path = str(self.root / "signal.sqlite3")
        self.lock = threading.RLock()
        with self.db() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS devices (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, platform TEXT NOT NULL,
                    token_hash TEXT NOT NULL UNIQUE, created_at TEXT DEFAULT CURRENT_TIMESTAMP
                );
                CREATE TABLE IF NOT EXISTS assets (
                    id TEXT PRIMARY KEY, device_id TEXT NOT NULL REFERENCES devices(id),
                    local_id TEXT NOT NULL, media_type TEXT NOT NULL, mime_type TEXT NOT NULL,
                    captured_at INTEGER, width INTEGER, height INTEGER, duration_ms INTEGER,
                    size INTEGER, original_sha TEXT, original_ready INTEGER NOT NULL DEFAULT 0,
                    thumb_ready INTEGER NOT NULL DEFAULT 0, filename TEXT, search_text TEXT DEFAULT '',
                    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(device_id,local_id)
                );
                CREATE TABLE IF NOT EXISTS changes (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT, asset_id TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS uploads (
                    id TEXT PRIMARY KEY, asset_id TEXT NOT NULL REFERENCES assets(id),
                    device_id TEXT NOT NULL, size INTEGER NOT NULL, sha TEXT NOT NULL,
                    offset INTEGER NOT NULL DEFAULT 0, done INTEGER NOT NULL DEFAULT 0
                );
                CREATE INDEX IF NOT EXISTS assets_date_idx ON assets(captured_at DESC);
            """)

    def db(self):
        connection = sqlite3.connect(self.db_path, timeout=30)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys=ON")
        return connection

    def enroll(self, key: str, body: dict):
        if not hmac.compare_digest(token_hash(key), self.enrollment_hash):
            raise ApiError(401, "invalid enrollment credential")
        device_id = text_field(body, "device_id", 128)
        name = text_field(body, "device_name", 80)
        platform = text_field(body, "platform", 20)
        if platform not in ("android", "ios", "test"):
            raise ApiError(400, "unsupported platform")
        token = secrets.token_urlsafe(40)
        with self.lock, self.db() as db:
            existing = db.execute("SELECT 1 FROM devices WHERE id=?", (device_id,)).fetchone()
            if existing:
                raise ApiError(409, "device already enrolled")
            db.execute("INSERT INTO devices (id,name,platform,token_hash) VALUES (?,?,?,?)",
                       (device_id, name, platform, token_hash(token)))
            db.commit()
        return {"device_id": device_id, "device_token": token}

    def authenticate(self, raw: str) -> sqlite3.Row:
        if not raw.startswith("Bearer "):
            raise ApiError(401, "missing bearer token")
        hashed = token_hash(raw[7:])
        with self.db() as db:
            row = db.execute("SELECT id,name,platform FROM devices WHERE token_hash=?", (hashed,)).fetchone()
        if row is None:
            raise ApiError(401, "invalid device credential")
        return row

    def upsert_asset(self, device: sqlite3.Row, body: dict):
        local_id = text_field(body, "local_id", 160)
        media_type = text_field(body, "media_type", 16)
        mime = text_field(body, "mime_type", 96)
        if media_type not in ("photo", "video", "screenshot"):
            raise ApiError(400, "unsupported media type")
        if not (mime.startswith("image/") or mime.startswith("video/")):
            raise ApiError(400, "unsupported mime")
        captured = integer_field(body, "captured_at", -1, 100000000000000, optional=True)
        width = integer_field(body, "width", 0, 100000, optional=True)
        height = integer_field(body, "height", 0, 100000, optional=True)
        duration = integer_field(body, "duration_ms", 0, 1000000000, optional=True)
        filename = str(body.get("filename", ""))[:180].replace("/", "_").replace("\\", "_")
        search_text = str(body.get("search_text", ""))[:2000]
        with self.lock, self.db() as db:
            row = db.execute("SELECT * FROM assets WHERE device_id=? AND local_id=?",
                             (device["id"], local_id)).fetchone()
            if row:
                media_id = row["id"]
                db.execute("""UPDATE assets SET media_type=?,mime_type=?,captured_at=?,
                           width=?,height=?,duration_ms=?,filename=?,search_text=? WHERE id=?""",
                           (media_type, mime, captured, width, height, duration, filename, search_text, media_id))
            else:
                media_id = str(uuid.uuid4())
                db.execute("""INSERT INTO assets
                           (id,device_id,local_id,media_type,mime_type,captured_at,width,height,duration_ms,filename,search_text)
                           VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                           (media_id, device["id"], local_id, media_type, mime, captured, width, height, duration, filename, search_text))
            db.execute("INSERT INTO changes(asset_id) VALUES (?)", (media_id,))
            db.commit()
        return {"media_id": media_id, "origin_device_id": device["id"], "original_ready": bool(row["original_ready"]) if row else False}

    def item(self, media_id: str):
        try:
            uuid.UUID(media_id)
        except ValueError:
            raise ApiError(400, "invalid media ID")
        with self.db() as db:
            row = db.execute("""SELECT a.*, d.name AS device_name FROM assets a
                                JOIN devices d ON a.device_id=d.id WHERE a.id=?""", (media_id,)).fetchone()
        if row is None:
            raise ApiError(404, "media not found")
        return row

    def catalog(self, cursor: int, limit: int, query: str = ""):
        with self.db() as db:
            where = "WHERE c.seq>?"
            params: list = [cursor]
            if query:
                where += " AND (a.filename LIKE ? OR a.search_text LIKE ? OR d.name LIKE ?)"
                pattern = "%" + query.replace("%", "\\%").replace("_", "\\_") + "%"
                where = where.replace(" LIKE ?", " LIKE ? ESCAPE '\\'")
                params.extend([pattern, pattern, pattern])
            params.append(limit)
            rows = db.execute(f"""SELECT c.seq, a.*, d.name AS device_name FROM changes c
                              JOIN assets a ON a.id=c.asset_id JOIN devices d ON d.id=a.device_id
                              {where} ORDER BY c.seq ASC LIMIT ?""", params).fetchall()
        # Delta entries may repeat on metadata update; client upserts by media_id.
        return {"items": [public_item(row) for row in rows],
                "next_cursor": rows[-1]["seq"] if rows else cursor}

    def thumbnail(self, device: sqlite3.Row, media_id: str, data: bytes, sha: str):
        row = self.item(media_id)
        if row["device_id"] != device["id"]:
            raise ApiError(403, "not the owning device")
        if len(data) < 1 or len(data) > MAX_THUMB or not HEX64.fullmatch(sha):
            raise ApiError(400, "invalid thumbnail")
        if not hmac.compare_digest(hashlib.sha256(data).hexdigest(), sha):
            raise ApiError(422, "thumbnail sha mismatch")
        atomic_save(self.root / "thumbs" / media_id, data)
        with self.lock, self.db() as db:
            db.execute("UPDATE assets SET thumb_ready=1 WHERE id=?", (media_id,))
            db.execute("INSERT INTO changes(asset_id) VALUES (?)", (media_id,))
            db.commit()
        return {"media_id": media_id, "thumbnail_ready": True}

    def start_upload(self, device: sqlite3.Row, body: dict):
        media_id = text_field(body, "media_id", 48)
        row = self.item(media_id)
        if row["device_id"] != device["id"]:
            raise ApiError(403, "not the owning device")
        size = integer_field(body, "size", 1, MAX_FILE)
        sha = text_field(body, "sha256", 64)
        if not HEX64.fullmatch(sha):
            raise ApiError(400, "invalid sha256")
        with self.lock, self.db() as db:
            if row["original_ready"] and row["original_sha"] == sha and row["size"] == size:
                return {"upload_id": None, "offset": size, "complete": True}
            existing = db.execute("""SELECT * FROM uploads
                 WHERE asset_id=? AND device_id=? AND sha=? AND size=? AND done=0
                 ORDER BY rowid DESC LIMIT 1""", (media_id, device["id"], sha, size)).fetchone()
            if existing:
                part = self.root / "staging" / existing["id"]
                offset = part.stat().st_size if part.exists() else 0
                if offset > size:
                    raise ApiError(409, "staging file too large")
                db.execute("UPDATE uploads SET offset=? WHERE id=?", (offset, existing["id"]))
                db.commit()
                return {"upload_id": existing["id"], "offset": offset, "complete": False}
            upload_id = str(uuid.uuid4())
            db.execute("INSERT INTO uploads(id,asset_id,device_id,size,sha) VALUES(?,?,?,?,?)",
                       (upload_id, media_id, device["id"], size, sha))
            db.commit()
        return {"upload_id": upload_id, "offset": 0, "complete": False}

    def write_chunk(self, device: sqlite3.Row, upload_id: str, offset: int, data: bytes):
        try:
            uuid.UUID(upload_id)
        except ValueError:
            raise ApiError(400, "invalid upload ID")
        if not data or len(data) > MAX_CHUNK:
            raise ApiError(413, "invalid chunk size")
        with self.lock, self.db() as db:
            row = db.execute("SELECT * FROM uploads WHERE id=?", (upload_id,)).fetchone()
            if row is None:
                raise ApiError(404, "upload not found")
            if row["device_id"] != device["id"]:
                raise ApiError(403, "not the owning device")
            if row["done"]:
                return {"offset": row["size"], "complete": True}
            path = self.root / "staging" / upload_id
            actual = path.stat().st_size if path.exists() else 0
            if offset != actual:
                raise ApiError(409, "offset conflict", extra={"offset": actual})
            if actual + len(data) > row["size"]:
                raise ApiError(413, "chunk exceeds declared upload size")
            with path.open("ab") as handle:
                handle.write(data)
                handle.flush()
                os.fsync(handle.fileno())
            new_offset = actual + len(data)
            if new_offset == row["size"]:
                sha = file_sha(path)
                if not hmac.compare_digest(sha, row["sha"]):
                    path.unlink(missing_ok=True)
                    db.execute("UPDATE uploads SET offset=0 WHERE id=?", (upload_id,))
                    db.commit()
                    raise ApiError(422, "original sha mismatch")
                target = self.root / "objects" / sha
                if target.exists():
                    path.unlink(missing_ok=True)
                else:
                    os.replace(path, target)
                db.execute("""UPDATE assets SET original_ready=1, original_sha=?, size=?
                              WHERE id=?""", (sha, row["size"], row["asset_id"]))
                db.execute("UPDATE uploads SET done=1,offset=? WHERE id=?", (new_offset, upload_id))
                db.execute("INSERT INTO changes(asset_id) VALUES (?)", (row["asset_id"],))
            else:
                db.execute("UPDATE uploads SET offset=? WHERE id=?", (new_offset, upload_id))
            db.commit()
            return {"offset": new_offset, "complete": new_offset == row["size"]}

    def content(self, media_id: str, kind: str):
        item = self.item(media_id)
        if kind == "thumbnail":
            if not item["thumb_ready"]:
                raise ApiError(404, "preview not ready")
            return self.root / "thumbs" / media_id, "image/jpeg"
        if kind == "original":
            if not item["original_ready"]:
                raise ApiError(404, "original not ready")
            return self.root / "objects" / item["original_sha"], item["mime_type"]
        raise ApiError(404, "unknown resource")


def text_field(body: dict, key: str, max_len: int):
    value = body.get(key)
    if not isinstance(value, str) or not value or len(value) > max_len or "\x00" in value:
        raise ApiError(400, f"invalid {key}")
    return value


def integer_field(body: dict, key: str, low: int, high: int, optional=False):
    value = body.get(key)
    if optional and value is None:
        return None
    if type(value) is not int or not low <= value <= high:
        raise ApiError(400, f"invalid {key}")
    return value


def public_item(row):
    return {
        "media_id": row["id"], "origin_device_id": row["device_id"],
        "device_name": row["device_name"], "media_type": row["media_type"],
        "mime_type": row["mime_type"], "captured_at": row["captured_at"],
        "width": row["width"], "height": row["height"],
        "duration_ms": row["duration_ms"], "filename": row["filename"],
        "size": row["size"], "sha256": row["original_sha"],
        "original_ready": bool(row["original_ready"]),
        "thumbnail_ready": bool(row["thumb_ready"]), "revision": row["seq"]
    }


def file_sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for part in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(part)
    return digest.hexdigest()


def atomic_save(destination: Path, data: bytes):
    fd, tmp = tempfile.mkstemp(dir=destination.parent, prefix="tmp-")
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, destination)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


class ApiError(Exception):
    def __init__(self, status: int, message: str, extra=None):
        super().__init__(message)
        self.status = status
        self.extra = extra or {}


def make_handler(service: MediaService):
    class Handler(BaseHTTPRequestHandler):
        server_version = "SignalMedia/0.3"
        protocol_version = "HTTP/1.1"

        def log_message(self, _format, *_args):
            # No tokens, user filenames or raw routes in access logs.
            pass

        def json_reply(self, status, obj):
            data = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def bytes_reply(self, data: bytes, mime: str):
            self.send_response(200)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "private, max-age=120")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(data)

        def body(self, limit):
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                raise ApiError(400, "invalid content length")
            if length < 1 or length > limit:
                raise ApiError(413, "body too large or empty")
            data = self.rfile.read(length)
            if len(data) != length:
                raise ApiError(400, "incomplete body")
            return data

        def json_body(self):
            try:
                result = json.loads(self.body(MAX_JSON))
            except (ValueError, UnicodeDecodeError):
                raise ApiError(400, "invalid JSON")
            if not isinstance(result, dict):
                raise ApiError(400, "JSON object required")
            return result

        def route(self):
            url = urlsplit(self.path)
            parts = [p for p in url.path.split("/") if p]
            if self.command == "GET" and parts == ["health"]:
                return self.json_reply(200, {"status": "ok", "protocol": "signal-media-v1",
                                             "search": "metadata", "ai_vision": False})
            if self.command == "POST" and parts == ["v1", "devices"]:
                return self.json_reply(201, service.enroll(self.headers.get("X-Enrollment-Key", ""), self.json_body()))
            device = service.authenticate(self.headers.get("Authorization", ""))
            if self.command == "POST" and parts == ["v1", "media"]:
                return self.json_reply(200, service.upsert_asset(device, self.json_body()))
            if self.command == "GET" and parts in (["v1", "media"], ["v1", "search"]):
                params = parse_qs(url.query)
                try:
                    cursor = max(0, int(params.get("since", ["0"])[0]))
                    limit = max(1, min(200, int(params.get("limit", ["150"])[0])))
                except ValueError:
                    raise ApiError(400, "invalid cursor")
                search = params.get("q", [""])[0].strip()[:200] if parts[-1] == "search" else ""
                return self.json_reply(200, service.catalog(cursor, limit, search))
            if self.command == "PUT" and len(parts) == 4 and parts[:2] == ["v1", "media"] and parts[3] == "thumbnail":
                data = self.body(MAX_THUMB)
                return self.json_reply(200, service.thumbnail(device, parts[2], data,
                                       self.headers.get("X-SHA256", "")))
            if self.command == "GET" and len(parts) == 4 and parts[:2] == ["v1", "media"]:
                path, mime = service.content(parts[2], parts[3])
                if not path.is_file():
                    raise ApiError(500, "verified media missing")
                return self.bytes_reply(path.read_bytes(), mime)
            if self.command == "POST" and parts == ["v1", "uploads"]:
                return self.json_reply(200, service.start_upload(device, self.json_body()))
            if self.command == "PUT" and len(parts) == 3 and parts[:2] == ["v1", "uploads"]:
                try:
                    offset = int(self.headers.get("X-Offset", "-1"))
                except ValueError:
                    raise ApiError(400, "invalid offset")
                return self.json_reply(200, service.write_chunk(device, parts[2], offset, self.body(MAX_CHUNK)))
            raise ApiError(404, "not found")

        def respond(self):
            try:
                self.route()
            except ApiError as error:
                self.json_reply(error.status, {"error": str(error), **error.extra})
            except (OSError, sqlite3.Error):
                self.json_reply(500, {"error": "internal storage error"})

        do_GET = respond
        do_POST = respond
        do_PUT = respond

    return Handler


def serve():
    secret = os.environ.get("SIGNAL_ENROLL_TOKEN", "")
    data = os.environ.get("SIGNAL_DATA_DIR", "/var/lib/signal-media")
    bind = os.environ.get("SIGNAL_BIND", "127.0.0.1")
    port = int(os.environ.get("SIGNAL_PORT", "8765"))
    if not secret:
        raise RuntimeError("SIGNAL_ENROLL_TOKEN is required; do not store it in git")
    service = MediaService(data, secret)
    server = ThreadingHTTPServer((bind, port), make_handler(service))
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    serve()

