"""公开产物不得携带隔离中心地址、注册凭据；不修改测试结果和断言。"""
import os
import sys
from pathlib import Path
from urllib.parse import urlsplit

endpoint = os.environ.get("SIGNAL_TEST_ENDPOINT", "")
private = os.environ.get("SIGNAL_ISOLATED_TARGET") == "true"
values = [os.environ.get("SIGNAL_TEST_ENROLLMENT", "")]
if private:
    values.extend([endpoint, urlsplit(endpoint).hostname or ""])
values = sorted({value for value in values if value}, key=len, reverse=True)
count = 0
for file in Path(sys.argv[1]).rglob("*"):
    if not file.is_file() or file.suffix.lower() not in {".txt", ".log", ".json", ".xml"}:
        continue
    raw = file.read_text(errors="replace")
    text = raw
    for value in values:
        text = text.replace(value, "[isolated-secret-redacted]")
    if text != raw:
        file.write_text(text)
        count += 1
print("已脱敏证据文件：", count)
