"""有限检查 AI 中心健康，仅输出 HTTP 状态，不打印地址、凭据或响应正文。"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
from urllib.parse import urlsplit

parser = argparse.ArgumentParser()
parser.add_argument("--attempts", type=int, default=3, choices=range(1, 7))
parser.add_argument("--output")
args = parser.parse_args()
endpoint = os.environ.get("SIGNAL_TEST_ENDPOINT", "http://127.0.0.1:8765").rstrip("/")
if os.environ.get("SIGNAL_ISOLATED_TARGET") == "true":
    parsed = urlsplit(endpoint)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise SystemExit("FAIL: isolated target requires a credential-free HTTPS URL")
with tempfile.TemporaryDirectory(prefix="signal-health-", dir=os.environ.get("RUNNER_TEMP")) as directory:
    body = Path(directory) / "body.json"
    for attempt in range(1, args.attempts + 1):
        body.unlink(missing_ok=True)
        # The subprocess never inherits a terminal for stderr, so curl cannot print the
        # private endpoint on DNS/TLS errors. Platform CA/hostname verification remains on.
        result = subprocess.run(["curl", "--silent", "--show-error", "--connect-timeout", "5",
            "--max-time", "15", "--output", str(body), "--write-out", "%{http_code}",
            endpoint + "/health"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        code = result.stdout.strip()
        print(f"[acceptance] health attempt {attempt}/{args.attempts}: HTTP {code if code.isdigit() else 'unavailable'}", flush=True)
        if result.returncode == 0 and code == "200":
            try:
                health = json.loads(body.read_text())
                if health.get("status") == "ok" and health.get("ai_vision") is True and not health.get("analysis_degraded"):
                    if args.output:
                        Path(args.output).write_text(json.dumps(health, sort_keys=True))
                    break
            except (OSError, ValueError, TypeError):
                pass
        if attempt < args.attempts:
            time.sleep(2)
    else:
        raise SystemExit("FAIL: AI-enabled server health unavailable after bounded checks")
