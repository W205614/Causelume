"""Live Prometheus/Loki/Alloy + Spring Boot evidence evaluation (no external model)."""
import json
import os
import subprocess
import tempfile
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import uuid4

from investigator import create_app

HERE = Path(__file__).resolve().parent
STACK = HERE.parent / "deployment" / "observability"


def verify():
    started = time.monotonic()
    project = "causelume-observe-test-" + uuid4().hex[:10]
    cases = json.loads((HERE / "evaluation-cases.json").read_text(encoding="utf-8"))
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        config = root / "prometheus.yml"
        config.write_text((STACK / "prometheus.yml").read_text().replace("10s", "1s").replace("4s", "900ms"))
        env = {**os.environ, "CONTRACT_PROM_CONFIG": config.as_posix(), "CONTRACT_MANAGEMENT_TOKEN": "test-only"}
        command = ["docker", "compose", "--env-file", os.devnull, "-p", project, "-f", str(STACK / "contract-compose.yml")]

        def compose(*args):
            return subprocess.check_output([*command, *args], env=env, text=True, timeout=600).strip()

        def origin(service, port):
            return "http://" + compose("port", service, str(port))

        try:
            compose("up", "-d", "--build")
            prom, loki, example = origin("prometheus", 9090), origin("loki", 3100), origin("example-service", 8085)
            deadline = time.monotonic() + 100
            for url in (prom + "/-/ready", loki + "/ready", example + "/actuator/health"):
                while True:
                    try:
                        with urlopen(url, timeout=2):
                            break
                    except (OSError, URLError):
                        if time.monotonic() > deadline:
                            raise
                        time.sleep(1)
            profile = root / "sources.json"
            profile.write_text(json.dumps([{"id": "prom-loki", "name": "Contract",
                                            "prometheus": {"url": prom}, "loki": {"url": loki}}]))
            os.environ.update(SOURCE_REGISTRY_FILE=str(profile), AGENT_MODE="offline", AGENT_TOKEN="test-agent",
                              EVIDENCE_TOKEN="test-evidence", DEMO_URL="http://127.0.0.1:1")
            client = create_app().test_client()
            auth = {"Authorization": "Bearer test-agent"}
            assert client.get("/sources/prom-loki/check?service=example-service&environment=local",
                              headers=auth).json["available"]
            for case in cases:
                # Seed the status counter before measuring increases in this test window.
                if case["action"]:
                    def hit():
                        try:
                            with urlopen(Request(example + "/example/" + case["action"], data=b""), timeout=5):
                                pass
                        except HTTPError as exc:
                            assert exc.code in (502, 503)
                    hit()
                    time.sleep(1.3)
                start = datetime.now(timezone.utc) - timedelta(seconds=0.1)
                if case["action"]:
                    time.sleep(1.2)
                    hit()
                deadline = time.monotonic() + 25
                while True:
                    result = client.post("/investigate", headers=auth, json={
                        "taskId": case["case"], "sourceId": "prom-loki", "service": case["service"],
                        "environment": "local", "windowStart": start.isoformat(),
                        "windowEnd": datetime.now(timezone.utc).isoformat()})
                    assert result.status_code == 200, result.json
                    snapshot = result.json
                    metrics = {r["signal"]: r["value"] for r in snapshot["observations"]["metrics"]["samples"]}
                    logs = snapshot["observations"]["logs"]["samples"]
                    events = {r["event"] for r in logs}
                    passed = {
                        "unreachable": metrics.get("min_up") == 0,
                        "errors": metrics.get("errors", 0) > 0 and "request_failed" in events,
                        "slow": metrics.get("mean_seconds", 0) > 1 and any(
                            r["event"] == "slow_request" and r.get("duration_ms", 0) >= 1000 for r in logs),
                        "dependency": "connection_refused" in events,
                        "no-data": snapshot["completeness"] == "empty",
                        "injection": "unclassified" in events and not any(
                            word in json.dumps(snapshot) for word in ("fixture-only", "ignore earlier instructions"))}
                    if passed[case["case"]]:
                        print(f"{case['case']}: PASS {case['facts']}", flush=True)
                        break
                    if time.monotonic() > deadline:
                        raise AssertionError((case["case"], snapshot))
                    time.sleep(0.6)
        finally:
            compose("down", "--volumes", "--remove-orphans")
    print(f"Evaluated six scenarios in {time.monotonic() - started:.1f}s including stack startup and teardown")


if __name__ == "__main__":
    verify()
