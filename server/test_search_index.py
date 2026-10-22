"""Actual OCR and pretrained multilingual visual retrieval, generated media only."""
import hashlib
import io
import os
import tempfile
import unittest

from signal_server import MediaService


@unittest.skipUnless(os.environ.get("SIGNAL_TEST_AI") == "1", "dedicated AI CI installs real models")
class RealSearchTest(unittest.TestCase):
    def test_actual_ocr_and_multilingual_visual_index(self):
        from PIL import Image, ImageDraw, ImageFont
        from search_index import MediaSearchIndex
        with tempfile.TemporaryDirectory() as root:
            service = MediaService(root, "synthetic-test-enrollment-secret")
            pair = service.enroll("synthetic-test-enrollment-secret", {
                "device_id": "ai-fixture", "device_name": "Synthetic", "platform": "test"})
            device = service.authenticate("Bearer " + pair["device_token"])
            ids = {}
            for label, color in (("red", "red"), ("blue", "blue"), ("text", "white")):
                image = Image.new("RGB", (640, 480), color)
                if label == "text":
                    draw = ImageDraw.Draw(image)
                    try:
                        font = ImageFont.truetype("DejaVuSans.ttf", 64)
                    except OSError:
                        font = ImageFont.load_default(size=64)
                    draw.text((25, 180), "TOKYO 2026", font=font, fill="black")
                stream = io.BytesIO()
                image.save(stream, "PNG")
                data = stream.getvalue()
                asset = service.upsert_asset(device, {"local_id": label, "media_type": "photo", "mime_type": "image/png", "filename": "fixture.png"})
                ids[label] = asset["media_id"]
                session = service.start_upload(device, {"media_id": asset["media_id"], "size": len(data), "sha256": hashlib.sha256(data).hexdigest()})
                service.write_chunk(device, session["upload_id"], 0, data)
            service.ai = MediaSearchIndex(service)
            self.assertEqual(3, service.ai.index_pending())
            self.assertEqual(0, service.ai.index_pending())
            results = service.ai.search("TOKYO", 0, 10)["items"]
            self.assertEqual(ids["text"], results[0]["media_id"])
            self.assertTrue(any(r["kind"] == "ocr_or_metadata" and "TOKYO" in r["excerpt"] for r in results[0]["match_reasons"]))
            for query in ("a red square", "红色的方块"):
                matches = service.ai.search(query, 0, 10)["items"]
                scores = {m["media_id"]: max((r.get("score", 0) for r in m["match_reasons"]), default=0) for m in matches}
                self.assertGreater(scores.get(ids["red"], 0), scores.get(ids["blue"], 0), query)
            self.assertTrue(service.ai.search("blue", 0, 1)["items"])
            self.assertEqual([], service.ai.search("not-a-real-image-file.jpg", 0, 10)["items"])
            damaged = service.upsert_asset(device, {"local_id": "damaged", "media_type": "photo", "mime_type": "image/png", "filename": "broken.png"})
            data = b"synthetic invalid image container"
            session = service.start_upload(device, {"media_id": damaged["media_id"], "size": len(data), "sha256": hashlib.sha256(data).hexdigest()})
            service.write_chunk(device, session["upload_id"], 0, data)
            self.assertEqual(1, service.ai.index_pending())
            self.assertEqual(1, service.ai.status()["failed"])
            self.assertEqual(0, service.ai.index_pending(), "bounded retry backoff")
            self.assertEqual(3, service.ai.status()["indexed"])
            self.assertTrue(service.ai.search("TOKYO", 0, 10)["items"])
            from PIL.TiffImagePlugin import IFDRational
            image = Image.new("RGB", (640, 480), "green")
            exif = Image.Exif()
            exif[270] = "Synthetic Park"
            exif[34853] = {1: "N", 2: (IFDRational(35), IFDRational(0), IFDRational(0)),
                           3: "E", 4: (IFDRational(139), IFDRational(0), IFDRational(0))}
            output = io.BytesIO()
            image.save(output, "JPEG", exif=exif)
            data = output.getvalue()
            geo = service.upsert_asset(device, {"local_id": "geo", "media_type": "photo", "mime_type": "image/jpeg", "filename": "geo.jpg", "captured_at": 1792580400000})
            session = service.start_upload(device, {"media_id": geo["media_id"], "size": len(data), "sha256": hashlib.sha256(data).hexdigest()})
            service.write_chunk(device, session["upload_id"], 0, data)
            service.ai.index_pending()
            results = service.ai.search("35.000,139.000附近", 0, 10)["items"]
            self.assertEqual([geo["media_id"]], [item["media_id"] for item in results])
            self.assertTrue(any(reason["kind"] == "location" for reason in results[0]["match_reasons"]))
            self.assertEqual(geo["media_id"], service.ai.search("Synthetic Park", 0, 10)["items"][0]["media_id"])



if __name__ == "__main__":
    unittest.main()
