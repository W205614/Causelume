"""Exercise release.sh in a network-isolated Linux container with a recording Docker CLI."""
import json
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SITE = "deployment/observability/compose.site.yaml"
BASE = ["compose", "--env-file", "production.env", "-f", "compose.production.yaml", "-f", "compose.observability.yaml"]
BUILD = ["--profile", "maintenance", "--profile", "restore", "build", "backup", "restore-check"]
BACKUP = ["--profile", "maintenance", "run", "--rm", "--no-deps", "-e", "APP_RELEASE=pre-upgrade:sha256:old", "backup"]
UP = ["up", "-d", "--wait", "--wait-timeout", "180"]
STOP = ["stop", "app"]

# Only external command responses are simulated. release.sh decides which calls to make.
DOCKER = """#!/usr/local/bin/python3
import json, sys
from pathlib import Path

args = sys.argv[1:]
with open("calls.jsonl", "a") as log:
    log.write(json.dumps(args) + "\\n")
if args[0] == "compose":
    for i, arg in enumerate(args):
        if arg in {"build", "ps", "config", "stop", "run", "pull", "up"}:
            args = args[i:]
            break
elif args[0] == "inspect":
    args = ["inspect", args[2], args[3]]
elif args[:2] == ["volume", "ls"]:
    args = ["volume", "ls"]
responses = json.loads(Path("responses.json").read_text())
key = " ".join(args)
if key not in responses:
    sys.exit("Unexpected Docker call: " + key)
code, output = responses[key]
print(output)
sys.exit(code)
"""


class ReleaseContract(unittest.TestCase):
    def run_release(self, *, existing=True, running=True, mount="team-postgres", site=False,
                    build_code=0, backup_code=0):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            (work / "secrets").mkdir()
            for name in ("APP_DB_PASSWORD", "APP_MIGRATOR_PASSWORD", "APP_MFA_KEY", "MANAGEMENT_TOKEN", "SMTP_PASSWORD"):
                (work / "secrets" / name).write_text("fixture")
            (work / "production.env").write_text("FIXTURE=1")
            (work / "heartbeat.env").touch()
            if site:
                (work / SITE).parent.mkdir(parents=True)
                (work / SITE).touch()
            bin_dir = work / "bin"
            bin_dir.mkdir()
            for name, script in (("docker", DOCKER), ("df", "#!/bin/sh\nprintf 'Available\\n0 0 0 6000000\\n'\n")):
                path = bin_dir / name
                path.write_bytes(script.encode())
                path.chmod(0o755)
            mount_format = '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql"}}{{.Name}}{{end}}{{end}}'
            replies = {
                "config --quiet": [0, ""], "build backup restore-check": [build_code, ""],
                "config --format json": [0, json.dumps({"volumes": {"postgres": {"name": "team-postgres"}}})],
                "volume ls": [0, "team-postgres" if existing else ""],
                "ps --status running -q postgres": [0, "pg-id" if running else ""],
                "inspect " + mount_format + " pg-id": [0, mount],
                "ps --all -q app": [0, "stopped-app-id"],
                "inspect {{.Config.Image}} stopped-app-id": [0, "previous:latest"],
                "inspect {{.Image}} stopped-app-id": [0, "sha256:old"],
                "stop app": [0, ""], " ".join(BACKUP[2:]): [backup_code, ""],
                "pull": [0, ""], " ".join(UP): [0, ""],
            }
            (work / "responses.json").write_text(json.dumps(replies))
            image = shlex.split((ROOT / "agent/Dockerfile").read_text().splitlines()[0])[1]
            result = subprocess.run([
                "docker", "run", "--rm", "--network", "none",
                "--mount", f"type=bind,src={work},dst=/opt/causelume",
                "--mount", f"type=bind,src={ROOT / 'scripts/release.sh'},dst=/release.sh,readonly",
                "-e", "PATH=/opt/causelume/bin:/usr/local/bin:/usr/bin:/bin", image, "/bin/sh", "/release.sh",
            ], capture_output=True, text=True, timeout=180)
            calls = [json.loads(line) for line in (work / "calls.jsonl").read_text().splitlines()]
            prefix = BASE + (["-f", SITE] if site else [])
            actions = []
            for call in calls:
                if call[0] == "compose":
                    self.assertEqual(call[:len(prefix)], prefix)
                    command = call[len(prefix):]
                    if command[0] not in ("config", "ps"):
                        actions.append(command)
            return result, actions

    def test_first_install(self):
        result, actions = self.run_release(existing=False, running=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(actions, [BUILD, ["pull"], UP])

    def test_upgrade_with_stopped_app_and_site_overlay(self):
        result, actions = self.run_release(site=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(actions, [BUILD, STOP, BACKUP, ["pull"], UP])

    def test_stopped_or_mismounted_database_blocks_update(self):
        for fixture, reason in (({"running": False}, "no running PostgreSQL"), ({"mount": "unrelated"}, "different data mount")):
            with self.subTest(fixture=fixture):
                result, actions = self.run_release(**fixture)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(reason, result.stderr)
                self.assertEqual(actions, [BUILD])

    def test_maintenance_build_failure_leaves_app_running(self):
        result, actions = self.run_release(build_code=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(actions, [BUILD])

    def test_backup_failure_keeps_app_stopped(self):
        result, actions = self.run_release(backup_code=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Backup failed", result.stderr)
        self.assertEqual(actions, [BUILD, STOP, BACKUP])


if __name__ == "__main__":
    unittest.main()
