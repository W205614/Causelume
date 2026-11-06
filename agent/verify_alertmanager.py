"""Verify the actual on-wire firing and resolved webhook against an isolated receiver."""
import json
import os
import queue
import subprocess
import sys
import threading
import time
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import URLError
from urllib.request import Request, urlopen
from uuid import uuid4


def receiver():
    notices = queue.Queue()

    class Receiver(BaseHTTPRequestHandler):
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            notices.put((self.path, self.headers.get("Authorization"), body))
            self.send_response(200)
            self.end_headers()

    with ThreadingHTTPServer(("0.0.0.0", 8080), Receiver) as server:
        threading.Thread(target=server.serve_forever, daemon=True).start()
        url = "http://alertmanager:9093"
        deadline = time.monotonic() + 30
        while True:
            try:
                with urlopen(url + "/-/ready", timeout=1):
                    break
            except (URLError, OSError):
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.3)
        event = {"labels": {"alertname": "ContractIncident", "service": "example-service",
                            "environment": "local", "severity": "warning"},
                 "annotations": {"summary": "Read-only delivery contract"},
                 "startsAt": (datetime.now(timezone.utc) - timedelta(seconds=2)).isoformat()}
        fingerprint = None
        for state in ("firing", "resolved"):
            if state == "resolved":
                event["endsAt"] = datetime.now(timezone.utc).isoformat()
            req = Request(url + "/api/v2/alerts", data=json.dumps([event]).encode(),
                          headers={"Content-Type": "application/json"})
            with urlopen(req, timeout=5):
                pass
            path, authorization, payload = notices.get(timeout=65)
            assert path == "/api/integrations/alertmanager/primary" and authorization == "Bearer contract-alert-token"
            assert payload["status"] == state and payload["alerts"][0]["status"] == state
            item = payload["alerts"][0]
            assert item["labels"] == event["labels"] and item["annotations"] == event["annotations"]
            assert item["fingerprint"] and (fingerprint is None or fingerprint == item["fingerprint"])
            fingerprint = item["fingerprint"]
            print(f"Verified authorized {state} webhook, fingerprint={fingerprint}", flush=True)
        server.shutdown()


def verify():
    manifest = Path(__file__).resolve().parents[1] / "deployment/observability/alertmanager-contract.yml"
    project = "causelume-alert-test-" + uuid4().hex[:10]
    args = ["docker", "compose", "--env-file", os.devnull, "-p", project, "-f", str(manifest)]
    env = {**os.environ, "CONTRACT_ALERT_TOKEN": "contract-alert-token"}
    try:
        subprocess.run([*args, "up", "--abort-on-container-exit", "--exit-code-from", "app"], env=env,
                       check=True, timeout=180)
    finally:
        subprocess.run([*args, "down", "--volumes"], env=env, check=True)


if __name__ == "__main__":
    receiver() if sys.argv[1:] == ["--receiver"] else verify()
