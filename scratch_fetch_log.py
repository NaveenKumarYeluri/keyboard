import urllib.request
import json
import os

run_id = 43692897927
url = f"https://api.github.com/repos/NaveenKumarYeluri/keyboard/actions/runs/{run_id}/jobs"
req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})

with urllib.request.urlopen(req) as resp:
    data = json.loads(resp.read().decode("utf-8"))

for job in data.get("jobs", []):
    job_id = job.get("id")
    job_name = job.get("name")
    conclusion = job.get("conclusion")
    print(f"Job {job_id} ({job_name}): {conclusion}")
    
    # Try fetching log url
    log_url = f"https://api.github.com/repos/NaveenKumarYeluri/keyboard/actions/jobs/{job_id}/logs"
    print("Fetching log from", log_url)
    
    # Handle redirect
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def http_error_302(self, req, fp, code, msg, hdrs):
            return fp

    opener = urllib.request.build_opener(NoRedirect)
    r = opener.open(urllib.request.Request(log_url, headers={"User-Agent": "Mozilla/5.0"}))
    loc = r.headers.get("Location")
    if loc:
        print("Redirect loc:", loc[:60])
        s3_data = urllib.request.urlopen(loc).read().decode("utf-8", errors="ignore")
        filename = f"job_{job_id}.log"
        with open(filename, "w", encoding="utf-8") as out:
            out.write(s3_data)
        print(f"Wrote {filename}, length={len(s3_data)}")
        
        # Now search for compiler errors in the log
        lines = s3_data.splitlines()
        print("\n--- ERRORS FOUND ---")
        for i, line in enumerate(lines):
            if any(k in line for k in ["e: ", "ERROR:", "FAILURE:", "What went wrong:", "Problem found:"]):
                start = max(0, i - 1)
                end = min(len(lines), i + 15)
                for l in lines[start:end]:
                    print(l)
                print("---------------------")
    else:
        print("No redirect Location header found, code:", r.code)
