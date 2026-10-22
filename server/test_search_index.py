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
                    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 64)
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


if __name__ == "__main__":
    unittest.main()
