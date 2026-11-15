import glob
import os
import re

brain_dir = r"C:\Users\Naveen\.gemini\antigravity-cli\brain"
for root, dirs, files in os.walk(brain_dir):
    for f in files:
        if f.endswith(".jsonl") or f.endswith(".py"):
            path = os.path.join(root, f)
            try:
                with open(path, "r", encoding="utf-8", errors="ignore") as file:
                    content = file.read()
                    if "api.github.com" in content and "token" in content:
                        print("Found in:", path)
                        for line in content.splitlines():
                            if "Bearer" in line or "ghp_" in line or "github_pat_" in line or "token =" in line or "TOKEN" in line:
                                print("   ", line[:120])
            except Exception as e:
                pass
