"""仅接受当前默认网络上的已验证、非计费 Wi-Fi，防止测试提前恢复。"""
import re
import sys
from pathlib import Path

state = Path(sys.argv[1]).read_text(errors="replace")
active = re.search(r"Active default network: (\d+)", state)
if not active:
    raise SystemExit(1)
# Android dumpsys emits each NetworkAgentInfo on one line; select the ACTIVE ID,
# rather than accepting an old disconnected network's retained capabilities.
rows = [line for line in state.splitlines() if "NetworkAgentInfo" in line and "network{" + active[1] + "}" in line]
raise SystemExit(0 if any("WIFI" in line and "VALIDATED" in line and "NOT_METERED" in line for line in rows) else 1)
