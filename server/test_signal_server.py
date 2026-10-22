"""Synthetic-only HTTP integration acceptance for Signal Server."""
import hashlib
import json
from pathlib import Path
import tempfile
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from http.server import ThreadingHTTPServer

from signal_server import MediaService, make_handler

ENROLL = "synthetic-test-enrollment-secret"


class SignalServerIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.service = MediaService(self.temp.name, ENROLL)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.service))
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"
        self.device_a = self.pair("device-A", "Pixel 8", "android")
        self.device_b = self.pair("device-B", "iPhone 17", "ios")

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join(timeout=3)
        self.temp.cleanup()

    def call(self, method, path, data=None, token=None, headers=None):
        headers = dict(headers or {})
        if token:
            headers["Authorization"] = "Bearer " + token
        if isinstance(data, dict):
            data = json.dumps(data).encode()
            headers["Content-Type"] = "application/json"
        request = Request(self.base + path, method=method, data=data, headers=headers)
        try:
            with urlopen(request, timeout=3) as response:
                raw = response.read()
                return response.status, json.loads(raw) if response.headers.get_content_type() == "application/json" else raw
        except HTTPError as error:
            raw = error.read()
            return error.code, json.loads(raw) if error.headers.get_content_type() == "application/json" else raw

    def pair(self, device_id, name, platform):
        status, result = self.call(
            "POST", "/v1/devices",
            {"device_id": device_id, "device_name": name, "platform": platform},
            headers={"X-Enrollment-Key": ENROLL})
        self.assertEqual(201, status, result)
        return result["device_token"]

    def make_photo(self, owner="a", key="local-1", name="autumn.jpg"):
        token = self.device_a if owner == "a" else self.device_b
        status, item = self.call("POST", "/v1/media", {
            "local_id": key, "media_type": "photo", "mime_type": "image/jpeg",
            "filename": name, "captured_at": 1792000000000,
            "width": 1200, "height": 900, "search_text": "autumn red leaf park"
        }, token=token)
        self.assertEqual(200, status, item)
        return item["media_id"]

    def test_pairing_and_permission_boundaries(self):
        code, _ = self.call("POST", "/v1/devices", {
            "device_id": "another", "device_name": "Unknown", "platform": "android"
        }, headers={"X-Enrollment-Key": "invalid"})
        self.assertEqual(401, code)
        code, _ = self.call("GET", "/v1/media")
        self.assertEqual(401, code)
        code, _ = self.call("GET", "/v1/media", token="invalid-token")
        self.assertEqual(401, code)
        code, _ = self.call("POST", "/v1/devices", {
            "device_id": "device-A", "device_name": "Duplicated", "platform": "android"
        }, headers={"X-Enrollment-Key": ENROLL})
        self.assertEqual(409, code)

    def test_metadata_idempotence_thumbnail_and_cross_device_visibility(self):
        one = self.make_photo()
        two = self.make_photo()
        self.assertEqual(one, two, "retry must not duplicate asset")
        cover = b"\xff\xd8" + bytes(range(32)) + b"\xff\xd9"
        sha = hashlib.sha256(cover).hexdigest()
        code, _ = self.call("PUT", f"/v1/media/{one}/thumbnail", cover,
                            token=self.device_a, headers={"X-SHA256": sha})
        self.assertEqual(200, code)
        code, catalog = self.call("GET", "/v1/media", token=self.device_b)
        self.assertEqual(200, code)
        self.assertEqual({one}, {x["media_id"] for x in catalog["items"]})
        self.assertTrue(catalog["items"][-1]["thumbnail_ready"])
        self.assertEqual("Pixel 8", catalog["items"][-1]["device_name"])
        code, loaded = self.call("GET", f"/v1/media/{one}/thumbnail", token=self.device_b)
        self.assertEqual((200, cover), (code, loaded))
        code, _ = self.call("PUT", f"/v1/media/{one}/thumbnail", cover,
                            token=self.device_b, headers={"X-SHA256": sha})
        self.assertEqual(403, code)
        code, result = self.call("GET", "/v1/media?since=" + str(catalog["next_cursor"]), token=self.device_b)
        self.assertEqual(200, code)
        self.assertEqual([], result["items"])

    def test_resumable_original_after_network_interruption_and_idempotence(self):
        photo = self.make_photo()
        contents = b"\xff\xd8" + b"synthetic photo payload" * 511 + b"\xff\xd9"
        sha = hashlib.sha256(contents).hexdigest()
        code, upload = self.call("POST", "/v1/uploads", {"media_id": photo, "size": len(contents), "sha256": sha}, token=self.device_a)
        self.assertEqual(200, code)
        upload_id = upload["upload_id"]
        split = 3100
        code, first = self.call("PUT", f"/v1/uploads/{upload_id}", contents[:split], token=self.device_a, headers={"X-Offset": "0"})
        self.assertEqual(200, code)
        self.assertFalse(first["complete"])
        code, wrong = self.call("PUT", f"/v1/uploads/{upload_id}", b"oops", token=self.device_a, headers={"X-Offset": "0"})
        self.assertEqual(409, code)
        self.assertEqual(split, wrong["offset"])
        # Simulate process restart by re-opening the service against the same SQLite/staging directory.
        recovered = MediaService(self.temp.name, ENROLL)
        continued = recovered.start_upload({"id": "device-A"}, {"media_id": photo, "size": len(contents), "sha256": sha})
        self.assertEqual(split, continued["offset"])
        code, complete = self.call("PUT", f"/v1/uploads/{upload_id}", contents[split:], token=self.device_a, headers={"X-Offset": str(split)})
        self.assertEqual(200, code)
        self.assertTrue(complete["complete"])
        code, fetched = self.call("GET", f"/v1/media/{photo}/original", token=self.device_b)
        self.assertEqual(200, code)
        self.assertEqual(contents, fetched)
        code, retry = self.call("POST", "/v1/uploads", {"media_id": photo, "size": len(contents), "sha256": sha}, token=self.device_a)
        self.assertEqual(200, code)
        self.assertTrue(retry["complete"])
        self.assertIsNone(retry["upload_id"])
        self.assertEqual(sha, list((Path(self.temp.name) / "objects").iterdir())[0].name)

    def test_bad_hash_rolls_back_and_owner_only_upload(self):
        photo = self.make_photo()
        code, upload = self.call("POST", "/v1/uploads", {"media_id": photo, "size": 5, "sha256": "a"*64}, token=self.device_a)
        self.assertEqual(200, code)
        uid = upload["upload_id"]
        code, _ = self.call("PUT", f"/v1/uploads/{uid}", b"wrong", token=self.device_b, headers={"X-Offset": "0"})
        self.assertEqual(403, code)
        code, error = self.call("PUT", f"/v1/uploads/{uid}", b"wrong", token=self.device_a, headers={"X-Offset": "0"})
        self.assertEqual(422, code)
        self.assertIn("mismatch", error["error"])
        code, _ = self.call("GET", f"/v1/media/{photo}/original", token=self.device_b)
        self.assertEqual(404, code)

    def test_two_devices_search_cursor_and_deferred_video(self):
        photo_a = self.make_photo()
        photo_b = self.make_photo(owner="b", key="b-photo", name="sunset.jpg")
        status, video = self.call("POST", "/v1/media", {
            "local_id": "long-video", "media_type": "video", "mime_type": "video/mp4",
            "filename": "trip.mp4", "duration_ms": 300000}, token=self.device_b)
        self.assertEqual(200, status)
        code, catalog = self.call("GET", "/v1/media", token=self.device_a)
        self.assertEqual(200, code)
        self.assertEqual({photo_a, photo_b, video["media_id"]}, {x["media_id"] for x in catalog["items"]})
        self.assertFalse(next(x for x in catalog["items"] if x["media_id"] == video["media_id"])["original_ready"])
        code, results = self.call("GET", "/v1/search?q=autumn", token=self.device_b)
        self.assertEqual(200, code)
        self.assertEqual({photo_a, photo_b}, {x["media_id"] for x in results["items"]})
        code, results = self.call("GET", "/v1/media?since=" + str(catalog["next_cursor"]), token=self.device_a)
        self.assertEqual([], results["items"])
        self.assertEqual(200, code)


if __name__ == "__main__":
    unittest.main()

