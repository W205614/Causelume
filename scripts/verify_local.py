"""Exercise the packaged jar and both Python services on loopback, using a fresh in-memory DB."""
import os
import secrets
import base64
import socket
import subprocess
import sys
import time
from contextlib import ExitStack
from pathlib import Path
from urllib import request
from urllib.error import URLError

ROOT = Path(__file__).resolve().parents[1]


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return str(sock.getsockname()[1])


def start(stack, cmd, settings, url):
    process = subprocess.Popen(cmd, cwd=ROOT, env={**os.environ, **settings})
    def stop():
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
    stack.callback(stop)
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Service exited before it was ready: {url}")
        try:
            with request.urlopen(url, timeout=1):
                return
        except (URLError, TimeoutError):
            time.sleep(.25)
    raise TimeoutError(url)


def main():
    java_port, agent_port, checkout_port = free_port(), free_port(), free_port()
    app, agent, checkout = [f"http://127.0.0.1:{p}" for p in (java_port, agent_port, checkout_port)]
    admin, operator, bearer, control, reader = [secrets.token_urlsafe(24) for _ in range(5)]
    with ExitStack() as stack:
        start(stack, [sys.executable, "agent/checkout.py"],
              dict(PORT=checkout_port, DEMO_CONTROL_TOKEN=control, EVIDENCE_TOKEN=reader), checkout + "/health")
        start(stack, [sys.executable, "agent/investigator.py"],
              dict(PORT=agent_port, DEMO_URL=checkout, AGENT_MODE="offline", AGENT_TOKEN=bearer,
                   EVIDENCE_TOKEN=reader), agent + "/health")
        start(stack, ["java", "-jar", "backend/target/causelume-1.0.0.jar"],
              dict(SERVER_PORT=java_port, SERVER_ADDRESS="127.0.0.1", APP_ADMIN_PASSWORD=admin,
                   APP_OPERATOR_PASSWORD=operator, APP_MFA_KEY=base64.b64encode(secrets.token_bytes(32)).decode(), AGENT_URL=agent, AGENT_TOKEN=bearer, DEMO_URL=checkout,
                   EVIDENCE_TOKEN=reader, APP_DB_URL="jdbc:h2:mem:acceptance;DB_CLOSE_DELAY=-1"), app + "/api/health")
        subprocess.run([sys.executable, "scripts/e2e.py", "--app", app, "--checkout", checkout], check=True,
                       cwd=ROOT, env={**os.environ, "APP_ADMIN_PASSWORD": admin,
                                      "APP_OPERATOR_PASSWORD": operator, "DEMO_CONTROL_TOKEN": control})


        subprocess.run(["node", "frontend/tests/live-console.mjs"], cwd=ROOT, check=True,
                       env={**os.environ, "CAUSELUME_URL": app, "CAUSELUME_ADMIN_PASSWORD": admin,
                            "CAUSELUME_VERIFY_EVIDENCE": "1"})

        subprocess.run(["node", "frontend/tests/live-product.mjs"], cwd=ROOT, check=True,
                       env={**os.environ, "CAUSELUME_URL": app, "CAUSELUME_ADMIN_PASSWORD": admin})

if __name__ == "__main__":
    main()
