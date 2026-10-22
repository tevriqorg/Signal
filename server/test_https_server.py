"""Real TLS with certificate validation on isolated loopback; no public exposure."""
import json
from pathlib import Path
import ssl
import subprocess
import tempfile
import threading
import unittest
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from signal_server import MediaService, BoundedHTTPServer, make_handler


class IsolatedHttpsTest(unittest.TestCase):
    def test_tls_auth_persistence_and_hostname_validation(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            cert, key = root / "cert.pem", root / "key.pem"
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                "-subj", "/CN=localhost", "-addext", "subjectAltName=IP:127.0.0.1,DNS:localhost",
                "-keyout", str(key), "-out", str(cert)], check=True, capture_output=True)
            service = MediaService(root / "data", "synthetic-test-enrollment-secret")
            server = BoundedHTTPServer(("127.0.0.1", 0), make_handler(service))
            tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            tls.minimum_version = ssl.TLSVersion.TLSv1_2
            tls.load_cert_chain(cert, key)
            server.socket = tls.wrap_socket(server.socket, server_side=True)
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            base = f"https://127.0.0.1:{server.server_port}"
            context = ssl.create_default_context(cafile=str(cert))
            try:
                with urlopen(base + "/health", context=context, timeout=5) as result:
                    self.assertEqual("ok", json.load(result)["status"])
                with self.assertRaises(HTTPError) as missing:
                    urlopen(base + "/v1/media", context=context, timeout=5)
                self.assertEqual(401, missing.exception.code)
                body = json.dumps({"device_id": "https-fixture", "device_name": "Synthetic TLS", "platform": "test"}).encode()
                request = Request(base + "/v1/devices", data=body, headers={"X-Enrollment-Key": "synthetic-test-enrollment-secret"})
                with urlopen(request, context=context, timeout=5) as result:
                    credential = json.load(result)["device_token"]
                reopened = MediaService(root / "data", "synthetic-test-enrollment-secret")
                self.assertEqual("https-fixture", reopened.authenticate("Bearer " + credential)["id"])
                # System trust does not accept our isolated CA: no insecure TLS bypass.
                with self.assertRaises(Exception):
                    urlopen(base + "/health", timeout=5)
            finally:
                server.shutdown()
                server.server_close()
                worker.join(timeout=5)


if __name__ == "__main__":
    unittest.main()
