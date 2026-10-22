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
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo


class MediaSearchIndex:
    def __init__(self, service):
        from PIL import Image
        from sentence_transformers import SentenceTransformer
        self.service = service
        self.lock = threading.Lock()
        self.visual = SentenceTransformer(os.environ.get("SIGNAL_IMAGE_MODEL", "sentence-transformers/clip-ViT-B-32"), device="cpu")
        self.text = SentenceTransformer(os.environ.get("SIGNAL_TEXT_MODEL", "sentence-transformers/clip-ViT-B-32-multilingual-v1"), device="cpu")
        with service.db() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS media_analysis(
                asset_id TEXT PRIMARY KEY, sha TEXT NOT NULL, ocr TEXT NOT NULL,
                vector TEXT NOT NULL, model TEXT NOT NULL)""")
        self.ready = True

    def index_pending(self):
        from PIL import Image, ImageOps
        with self.lock:
            with self.service.db() as db:
                rows = db.execute("""SELECT a.* FROM assets a LEFT JOIN media_analysis m ON m.asset_id=a.id
                    WHERE a.original_ready=1 AND a.deleted=0 AND (m.sha IS NULL OR m.sha<>a.original_sha) LIMIT 32""").fetchall()
            for row in rows:
                # Video uses its authenticated representative cover, not fabricated frame understanding.
                path = self.service.root / ("thumbs" if row["media_type"] == "video" else "objects") / (row["id"] if row["media_type"] == "video" else row["original_sha"])
                if not path.is_file():
                    continue
                with Image.open(path) as source:
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
                        db.execute("INSERT OR REPLACE INTO media_analysis VALUES(?,?,?,?,?)",
                            (row["id"], row["original_sha"], ocr, json.dumps(vector), "multilingual-clip-vit-b32"))
            return len(rows)

    def search(self, query, offset, limit):
        from signal_server import public_item
        with self.lock:
            query_vector = self.text.encode(query, normalize_embeddings=True).tolist()
        terms = [t.casefold() for t in re.findall(r"[\w]+", query) if len(t) > 1]
        with self.service.db() as db:
            rows = db.execute("""SELECT a.*, d.name AS device_name, m.ocr, m.vector,
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
        for row in rows:
            reasons = []
            if date:
                if row["captured_at"] is None or datetime.fromtimestamp(row["captured_at"] / 1000, zone).date() != date:
                    continue
                reasons.append({"kind": "time", "date": str(date), "timezone": str(zone)})
            text = " ".join(str(row[k] or "") for k in ("filename", "search_text", "device_name", "ocr"))
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
