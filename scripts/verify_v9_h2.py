"""Cold-back up a disposable v1.1.1 H2 workspace and read it from a restored volume."""
import hashlib
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
from uuid import uuid4

from e2e import Client
from verify_candidate import V9_APP, ready, wait_for


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True, timeout=120).strip()


def main():
    name = "causelume-v9-restore-" + uuid4().hex[:10]
    restored = name + "-restored"
    password = secrets.token_hex(24)
    env = {**os.environ, "APP_ADMIN_PASSWORD": password, "APP_OPERATOR_PASSWORD": secrets.token_hex(24)}
    containers, volumes = [], []

    def volume(identifier):
        docker("volume", "create", identifier)
        volumes.append(identifier)

    def start(identifier):
        subprocess.run(["docker", "run", "-d", "--name", identifier, "-p", "127.0.0.1::8080",
                        "-v", identifier + ":/app/data", "-e", "APP_ADMIN_PASSWORD", "-e", "APP_OPERATOR_PASSWORD",
                        "-e", "APP_DB_URL=jdbc:h2:file:/app/data/causelume", "-e", "AGENT_MODE=offline", V9_APP],
                       env=env, check=True, stdout=subprocess.DEVNULL, timeout=120)
        containers.append(identifier)
        client = Client("http://" + docker("port", identifier, "8080"))
        wait_for("v1.1.1 H2 ready", lambda: ready(client.base + "/api/health"))
        client.call("/api/login", {"username": "admin", "password": password})
        return client

    try:
        volume(name)
        original = start(name)
        ticket = original.call("/api/tickets", {"serviceId": 1, "requestId": str(uuid4()), "title": "V9 recovery fixture"})
        docker("stop", name)
        with tempfile.TemporaryDirectory(prefix=name) as folder:
            backup = Path(folder) / "causelume.mv.db"
            docker("cp", name + ":/app/data/causelume.mv.db", str(backup))
            expected = hashlib.sha256(backup.read_bytes()).hexdigest()
            volume(restored)
            copy = docker("run", "--rm", "--network", "none", "--user", "0", "--entrypoint", "sh",
                          "-v", restored + ":/app/data", "--mount", "type=bind,source=" + folder + ",target=/backup,readonly",
                          V9_APP, "-ec", "cp /backup/causelume.mv.db /app/data/causelume.mv.db && "
                          "chown 999:999 /app/data/causelume.mv.db && sha256sum /app/data/causelume.mv.db")
            assert copy.split()[0] == expected
        recovered = start(restored)
        saved = recovered.call(f"/api/tickets/{ticket['id']}")
        assert saved["title"] == ticket["title"] and saved["timeline"] == ticket["timeline"]
        print("PASS v1.1.1 H2 cold backup SHA256, separate volume restore and authenticated read-back", flush=True)
    finally:
        for identifier in reversed(containers):
            docker("rm", "-f", identifier)
        for identifier in reversed(volumes):
            docker("volume", "rm", identifier)


if __name__ == "__main__":
    main()
