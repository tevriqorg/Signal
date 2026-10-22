"""Local-only OCR + multilingual CLIP retrieval, no external AI provider or user data export.

Model caches and derived vectors are OUTSIDE_GIT. Enable explicitly with SIGNAL_AI_ENABLED=1.
Uses upstream SentenceTransformers extension points; no framework source modifications.
"""
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import threading
import time
import math
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo


class MediaSearchIndex:
    def __init__(self, service):
        from PIL import Image
        from sentence_transformers import SentenceTransformer
        self.service = service
        self.lock = threading.Lock()
        def load_model(name):
            model = SentenceTransformer(name, device="cpu")
            if os.environ.get("SIGNAL_AI_INT8") == "1":
                import torch
                torch.ao.quantization.quantize_dynamic(model, {torch.nn.Linear}, dtype=torch.qint8, inplace=True)
            return model
        # Quantize sequentially so constrained runtimes never hold both full float models plus copies.
        self.visual = load_model(os.environ.get("SIGNAL_IMAGE_MODEL", "sentence-transformers/clip-ViT-B-32"))
        self.text = load_model(os.environ.get("SIGNAL_TEXT_MODEL", "sentence-transformers/clip-ViT-B-32-multilingual-v1"))
        with service.db() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS media_analysis(
                asset_id TEXT PRIMARY KEY, sha TEXT NOT NULL, ocr TEXT NOT NULL,
                vector TEXT NOT NULL, model TEXT NOT NULL)""")
            db.execute("""CREATE TABLE IF NOT EXISTS media_analysis_errors(
                asset_id TEXT PRIMARY KEY, sha TEXT NOT NULL, attempts INTEGER NOT NULL,
                next_retry REAL NOT NULL, error_code TEXT NOT NULL)""")
            columns = {r[1] for r in db.execute("PRAGMA table_info(media_analysis)")}
            if "context" not in columns:
                db.execute("ALTER TABLE media_analysis ADD COLUMN context TEXT NOT NULL DEFAULT '{}'")
        self.ready = True

    def index_pending(self):
        from PIL import Image, ImageOps
        with self.lock:
            with self.service.db() as db:
                rows = db.execute("""SELECT a.* FROM assets a LEFT JOIN media_analysis m ON m.asset_id=a.id
                    LEFT JOIN media_analysis_errors e ON e.asset_id=a.id AND e.sha=a.original_sha
                    WHERE a.original_ready=1 AND a.deleted=0 AND (m.sha IS NULL OR m.sha<>a.original_sha)
                    AND (e.next_retry IS NULL OR e.next_retry<=?) LIMIT 32""", (time.time(),)).fetchall()
            for row in rows:
                try:
                    # Video uses its authenticated representative cover, not fabricated frame understanding.
                    path = self.service.root / ("thumbs" if row["media_type"] == "video" else "objects") / (row["id"] if row["media_type"] == "video" else row["original_sha"])
                    if not path.is_file():
                        continue
                    with Image.open(path) as source:
                        context = {}
                        try:
                            exif = source.getexif()
                            context["description"] = str(exif.get(270, ""))[:1000]
                            gps = exif.get_ifd(34853)
                            def degrees(values):
                                return float(values[0]) + float(values[1]) / 60 + float(values[2]) / 3600
                            if gps.get(2) and gps.get(4):
                                lat = degrees(gps[2]) * (-1 if gps.get(1) == "S" else 1)
                                lon = degrees(gps[4]) * (-1 if gps.get(3) == "W" else 1)
                                if -90 <= lat <= 90 and -180 <= lon <= 180:
                                    context.update(latitude=lat, longitude=lon)
                        except (KeyError, TypeError, ValueError, ZeroDivisionError):
                            pass
                        image = ImageOps.exif_transpose(source).convert("RGB")
                        image.thumbnail((1600, 1600))
                        # Bound subprocess and pixels, and send only local PNG bytes to OCR.
                        import io
                        data = io.BytesIO()
                        image.save(data, format="PNG")
                        result = subprocess.run(["tesseract", "stdin", "stdout", "-l", os.environ.get("SIGNAL_OCR_LANG", "eng+jpn+chi_sim")],
                            input=data.getvalue(), capture_output=True, timeout=45, check=True)
                        ocr = result.stdout.decode("utf-8", errors="replace")[:32000]
                        vector = self.visual.encode(image, normalize_embeddings=True).tolist()
                    with self.service.lock, self.service.db() as db:
                        # Never attach old-source analysis to a newer version.
                        current = db.execute("SELECT original_sha,deleted FROM assets WHERE id=?", (row["id"],)).fetchone()
                        if current and current["original_sha"] == row["original_sha"] and not current["deleted"]:
                            db.execute("INSERT OR REPLACE INTO media_analysis(asset_id,sha,ocr,vector,model,context) VALUES(?,?,?,?,?,?)",
                                (row["id"], row["original_sha"], ocr, json.dumps(vector), "multilingual-clip-vit-b32", json.dumps(context)))
                            db.execute("DELETE FROM media_analysis_errors WHERE asset_id=?", (row["id"],))
                except Exception as error:
                    # One malformed media must not block every later asset; retain a private bounded error code.
                    with self.service.db() as db:
                        previous = db.execute("SELECT attempts FROM media_analysis_errors WHERE asset_id=? AND sha=?",
                            (row["id"], row["original_sha"])).fetchone()
                        attempts = (previous[0] if previous else 0) + 1
                        db.execute("INSERT OR REPLACE INTO media_analysis_errors VALUES(?,?,?,?,?)",
                            (row["id"], row["original_sha"], attempts,
                             time.time() + min(3600, 5 * 2 ** min(attempts, 10)), type(error).__name__[:80]))
            return len(rows)

    def status(self):
        with self.service.db() as db:
            total = db.execute("SELECT COUNT(*) FROM assets WHERE original_ready=1 AND deleted=0").fetchone()[0]
            indexed = db.execute("SELECT COUNT(*) FROM assets a JOIN media_analysis m ON m.asset_id=a.id AND m.sha=a.original_sha WHERE a.deleted=0 AND a.original_ready=1").fetchone()[0]
            failed = db.execute("SELECT COUNT(*) FROM assets a JOIN media_analysis_errors e ON e.asset_id=a.id AND e.sha=a.original_sha WHERE a.deleted=0 AND a.original_ready=1").fetchone()[0]
        return {"enabled": True, "indexed": indexed, "pending": total - indexed, "failed": failed}

    def search(self, query, offset, limit):
        from signal_server import public_item
        with self.lock:
            query_vector = self.text.encode(query, normalize_embeddings=True).tolist()
        terms = [t.casefold() for t in re.findall(r"[\w]+", query) if len(t) > 1]
        with self.service.db() as db:
            rows = db.execute("""SELECT a.*, d.name AS device_name, m.ocr, m.vector, m.context AS media_context,
                COALESCE((SELECT MAX(seq) FROM changes WHERE asset_id=a.id),0) AS seq
                FROM assets a JOIN devices d ON a.device_id=d.id
                LEFT JOIN media_analysis m ON m.asset_id=a.id AND m.sha=a.original_sha
                WHERE a.deleted=0""").fetchall()
        zone = ZoneInfo(os.environ.get("SIGNAL_TIMEZONE", "Asia/Tokyo"))
        today = datetime.now(zone).date()
        date = today if "今天" in query or "today" in query.lower() else today - timedelta(days=1) if "昨天" in query or "yesterday" in query.lower() else None
        iso = re.search(r"\b(20\d{2}-\d{2}-\d{2})\b", query)
        if iso:
            try: date = datetime.strptime(iso.group(1), "%Y-%m-%d").date()
            except ValueError: pass
        matches = []
        coordinate_match = re.search(r"(-?\d{1,2}\.\d+)\s*[,，]\s*(-?\d{1,3}\.\d+)", query)
        coordinate = tuple(map(float, coordinate_match.groups())) if coordinate_match else None
        filename_query = bool(re.search(r"\.(?:jpg|jpeg|png|webp|heic|mp4|mov)$", query, re.I))
        for row in rows:
            if filename_query and query.casefold() not in (row["filename"] or "").casefold():
                continue
            reasons = []
            context = json.loads(row["media_context"] or "{}")
            if coordinate:
                if "latitude" not in context or "longitude" not in context:
                    continue
                lat1, lon1, lat2, lon2 = map(math.radians, (*coordinate, context["latitude"], context["longitude"]))
                value = math.sin((lat2-lat1)/2)**2 + math.cos(lat1)*math.cos(lat2)*math.sin((lon2-lon1)/2)**2
                distance = 6371000 * 2 * math.asin(min(1, math.sqrt(value)))
                if distance > 5000:
                    continue
                reasons.append({"kind": "location", "distance_m": round(distance), "basis": "original_exif_gps"})
            if date:
                if row["captured_at"] is None or datetime.fromtimestamp(row["captured_at"] / 1000, zone).date() != date:
                    continue
                reasons.append({"kind": "time", "date": str(date), "timezone": str(zone)})
            text = " ".join(str(row[k] or "") for k in ("filename", "search_text", "device_name", "ocr")) + " " + context.get("description", "")
            hits = [term for term in terms if term in text.casefold()]
            score = 0.0
            if hits:
                score += 1.0
                reasons.append({"kind": "ocr_or_metadata", "terms": hits, "excerpt": text[:240]})
            if row["vector"]:
                vector = json.loads(row["vector"])
                similarity = sum(a*b for a,b in zip(query_vector, vector))
                if similarity >= 0.18:
                    score += similarity
                    reasons.append({"kind": "visual_similarity", "score": round(similarity, 4),
                        "basis": "video_cover" if row["media_type"] == "video" else "photo", "model": "multilingual-clip-vit-b32"})
            if reasons:
                item = public_item(row)
                item["match_reasons"] = reasons
                matches.append((score, item))
        matches.sort(key=lambda x: (-x[0], x[1]["media_id"]))
        return {"items": [item for _,item in matches[offset:offset+limit]],
                "next_cursor": offset + len(matches[offset:offset+limit])}
