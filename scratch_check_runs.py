import urllib.request
import json
import sys

url = "https://api.github.com/repos/NaveenKumarYeluri/keyboard/actions/runs?per_page=10"
req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
try:
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode("utf-8"))
        for r in data.get("workflow_runs", []):
            num = r.get("run_number")
            rid = r.get("id")
            status = r.get("status")
            conclusion = r.get("conclusion")
            head = r.get("head_commit", {})
            sha = head.get("id", "")[:7]
            msg = head.get("message", "").split("\n")[0]
            print(f"Run {num} (id={rid}): status={status}, conclusion={conclusion}, sha={sha}, msg={msg}")
except Exception as e:
    print("Error:", e, file=sys.stderr)
