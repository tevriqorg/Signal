"""Synthetic HTTP regression for lost enrollment replies and explicit identity recovery."""
import hashlib
import json
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from signal_server import MediaService, make_handler

ENROLL = "synthetic-pairing-recovery-enrollment"


class PairingRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.service = MediaService(self.temp.name, ENROLL)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.service))
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"
        self.body = {"device_id": "synthetic-stable-device", "device_name": "Synthetic Android", "platform": "android"}

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join(timeout=3)
        self.temp.cleanup()

    def request(self, method, path, data=None, token=None, key=None):
        headers = {}
        if token: headers["Authorization"] = "Bearer " + token
        if key: headers["X-Enrollment-Key"] = key
        if isinstance(data, dict):
            headers["Content-Type"] = "application/json"
            data = json.dumps(data).encode()
        try:
            response = urlopen(Request(self.base + path, data=data, method=method, headers=headers), timeout=3)
        except HTTPError as error:
            response = error
        with response:
            payload = response.read()
            return response.code, json.loads(payload) if response.headers.get_content_type() == "application/json" else payload

    def test_lost_reply_recovery_keeps_identity_assets_and_revokes_old_token(self):
        status, initial = self.request("POST", "/v1/devices", self.body, key=ENROLL)
        self.assertEqual(201, status)
        # The first response may never reach the phone. The device is already committed.
        old_token = initial["device_token"]
        self.assertEqual(409, self.request("POST", "/v1/devices", self.body, key=ENROLL)[0])
        old_device = self.service.authenticate("Bearer " + old_token)
        asset = self.service.upsert_asset(old_device, {"local_id": "same-media", "filename": "generated.bin", "media_type": "photo", "mime_type": "image/jpeg"})
        data = b"synthetic retained original bytes"
        sha = hashlib.sha256(data).hexdigest()
        session = self.service.start_upload(old_device, {"media_id": asset["media_id"], "size": len(data), "sha256": sha})
        self.service.write_chunk(old_device, session["upload_id"], 0, data)
        body = {**self.body, "recover_existing": True, "device_name": "Recovered Android"}
        self.assertEqual(401, self.request("POST", "/v1/devices", body, key="wrong-administrator-secret")[0])
        self.assertEqual(409, self.request("POST", "/v1/devices", {**body, "recover_existing": "true"}, key=ENROLL)[0])
        status, recovered = self.request("POST", "/v1/devices", body, key=ENROLL)
        self.assertEqual(201, status)
        self.assertEqual(initial["device_id"], recovered["device_id"])
        self.assertNotEqual(old_token, recovered["device_token"])
        self.assertEqual(401, self.request("GET", "/v1/media", token=old_token)[0])
        status, catalog = self.request("GET", "/v1/media?since=0", token=recovered["device_token"])
        self.assertEqual(200, status)
        self.assertEqual({asset["media_id"]}, {item["media_id"] for item in catalog["items"]})
        self.assertTrue(all(item["device_name"] == "Recovered Android" for item in catalog["items"]))
        self.assertEqual((200, data), self.request("GET", "/v1/media/" + asset["media_id"] + "/original", token=recovered["device_token"]))
        restored_device = self.service.authenticate("Bearer " + recovered["device_token"])
        duplicate = self.service.upsert_asset(restored_device, {"local_id": "same-media", "filename": "generated.bin", "media_type": "photo", "mime_type": "image/jpeg"})
        self.assertEqual(asset["media_id"], duplicate["media_id"])
        self.service.revoke_device(ENROLL, initial["device_id"])
        self.assertEqual(401, self.request("GET", "/v1/media", token=recovered["device_token"])[0])
        self.assertEqual(201, self.request("POST", "/v1/devices", body, key=ENROLL)[0])


if __name__ == "__main__":
    unittest.main()
