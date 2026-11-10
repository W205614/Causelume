"""Exercise the candidate JAR in a disposable prod-profile stack. No provider or project .env is used."""
import argparse
import base64
from datetime import datetime, timedelta, timezone
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import uuid4

from e2e import Client


ROOT = Path(__file__).resolve().parents[1]
OBS = ROOT / "deployment/observability"
AM = "prom/alertmanager@sha256:e9733bafb1bdef9b00e25a21f8f99dc26a22224bf16641ad754d1649f4c3357a"
MAIL = "axllent/mailpit@sha256:9b3a463be15354c0f0eba91697d80e3e0d8a5623ef0e627cacc836bc17fd5231"
RUNTIME = "eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269"
CADDY = "caddy:2.11.9-alpine@sha256:71d41fe4b1ae92a058abc87afe614178b996d8a6b96d0f4c71ae30606e5927e3"


def wait_for(label, read, matches=bool, timeout=90):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        value = read()
        if matches(value):
            return value
        time.sleep(1)
    raise TimeoutError(label)


def ready(url):
    try:
        with urlopen(url, timeout=3) as response:
            return response.status == 200
    except (URLError, ConnectionError, TimeoutError):
        return False


def enrolled(app, name, password):
    client = Client(app)
    actor = client.call("/api/login", {"username": name, "password": password})
    assert actor["enrollmentRequired"]
    seed = client.call("/api/account/mfa/enroll", {"password": password})["secret"]
    digest = hmac.digest(base64.b32decode(seed), (int(time.time()) // 30).to_bytes(8, "big"), "sha1")
    offset = digest[-1] & 15
    code = (int.from_bytes(digest[offset:offset+4], "big") & 0x7fffffff) % 1000000
    client.call("/api/account/mfa/confirm", {"code": f"{code:06d}"})
    assert client.call("/api/me")["mfaEnabled"]
    return client


def exercise(origin, keys, management_token):
    app = origin("app", 8080)
    example = origin("example-service", 8085)
    mail = Client(origin("mail", 8025))
    manager = Client(origin("alertmanager", 9093))
    for url in (app + "/api/health", example + "/actuator/health", manager.base + "/-/ready",
                origin("loki", 3100) + "/ready", origin("prometheus", 9090) + "/-/ready"):
        wait_for(url, lambda: ready(url))
    metrics = origin("app", 9091) + "/actuator/prometheus"
    assert_http_status(Request(metrics), 401)
    with urlopen(Request(metrics, headers={"Authorization": "Bearer " + management_token}), timeout=5) as response:
        assert b"jvm_" in response.read()
    gateway = origin("query-gateway", 9090)
    assert ready(gateway + "/-/ready")
    assert_http_status(Request(gateway + "/api/v1/query", data=b"query=up"), 403)
    print("PASS protected management metrics and read-only observation gateway", flush=True)
    admin = enrolled(app, "admin", keys["APP_ADMIN_PASSWORD"])
    owner = enrolled(app, "operator", keys["APP_OPERATOR_PASSWORD"])
    owner_id = owner.call("/api/me")["id"]
    recipient = "candidate-owner@example.invalid"
    owner.call("/api/account/profile", {"email": recipient})
    service = {"name": "example-service", "description": "Candidate only", "resource": "local"}
    sid = admin.call("/api/services", service)["id"]
    admin.call(f"/api/services/{sid}", {**service, "ownerId": owner_id, "environment": "local",
               "active": True, "sourceId": "prom-loki", "serviceLabel": "example-service", "version": 0})
    assert admin.call(f"/api/services/{sid}/source-check")["available"]
    print("PASS production MFA enrollment, least-privilege runtime and approved source", flush=True)

    def now():
        return datetime.now(timezone.utc)

    start = now() - timedelta(seconds=30)
    for _ in range(3):
        try:
            with urlopen(Request(example + "/example/error", data=b""), timeout=5):
                raise AssertionError("example fault did not fire")
        except HTTPError as error:
            assert error.code in (502, 503)
        time.sleep(2)
    event = {"labels": {"service": "example-service", "severity": "critical", "alertname": "CandidateFailure"},
             "annotations": {"summary": "Candidate example failure"}, "startsAt": start.isoformat()}
    manager.call("/api/v2/alerts", [{**event, "endsAt": (now() + timedelta(minutes=10)).isoformat()}])
    tickets = wait_for("Alertmanager webhook -> ticket", lambda: owner.call(f"/api/tickets/page?serviceId={sid}"),
                       lambda page: page["total"] == 1)
    ticket = tickets["items"][0]
    assert ticket["status"] == "ASSIGNED" and ticket["assigneeId"] == owner_id
    route = f"/api/tickets/{ticket['id']}"
    wait_for("owner notification and SMTP acknowledgement", lambda: owner.call("/api/notifications")["items"],
             lambda rows: any(r["ticketId"] == ticket["id"] and r["mailStatus"] == "SENT" for r in rows))
    assert any(to["Address"] == recipient for message in mail.call("/api/v1/messages")["messages"] for to in message["To"])
    owner.call(route + "/actions", {"action": "START", "version": ticket["version"], "note": "Inspect example errors"})
    print("PASS real Alertmanager webhook, automatic assignment, inbox and SMTP delivery", flush=True)

    task = owner.call("/api/tasks", {"ticketId": ticket["id"], "requestId": str(uuid4()),
                      "windowStart": start.isoformat(), "windowEnd": now().isoformat()})
    task = wait_for("offline investigation", lambda: owner.call(f"/api/tasks/{task['id']}"),
                    lambda item: item["status"] in ("SUCCEEDED", "FAILED"))
    assert task["status"] == "SUCCEEDED", task["error"]
    result = task["result"]
    assert result["mode"] == "offline" and result["sourceId"] == "prom-loki" and result["snapshotId"]
    observations = result["observations"]
    assert any(s["signal"] == "errors" and s["value"] > 0 for s in observations["metrics"]["samples"])
    assert any(s["event"] == "request_failed" for s in observations["logs"]["samples"])
    print("PASS offline investigation with real Prometheus counter and Loki log in one snapshot", flush=True)

    manager.call("/api/v2/alerts", [{**event, "endsAt": now().isoformat()}])
    ticket = wait_for("Alertmanager resolved delivery", lambda: owner.call(route),
                      lambda item: any(e["action"] == "ALERT_RESOLVED" for e in item["timeline"]))
    assert ticket["status"] == "PROCESSING"
    ticket = owner.call(route + "/actions", {"action": "VERIFY", "version": ticket["version"],
                        "verification": "PASSED", "note": "Checked recovery and service health"})
    review = {"impact": "Synthetic test requests", "startedAt": start.isoformat(), "endedAt": now().isoformat(),
              "cause": "Explicitly injected example fault", "uncertainty": "Candidate traffic only"}
    owner.call(route + "/actions", {"action": "CLOSE", "version": ticket["version"], "note": "Candidate review",
                                    "postmortem": review})
    report = owner.call(route + "/export?format=json", {})
    assert report["ticket"]["status"] == "CLOSED" and report["ticket"]["cause"] == review["cause"]
    assert report["investigations"][0]["id"] == task["id"] and report["timeline"]
    print("PASS recovery signal, human verification, structured review, closure and authorized export", flush=True)


def assert_http_status(req, status):
    try:
        with urlopen(req, timeout=5) as response:
            actual = response.status
    except HTTPError as error:
        actual = error.code
    assert actual == status, (req.full_url, status, actual)


def manifest(root, jar, runtime, agent_runtime, backup_image, keys):
    def bind(path, target):
        return {"type": "bind", "source": str(path.resolve()), "target": target, "read_only": True}

    def node(**config):
        return {"networks": ["contract"], **config}

    files = {}
    for name, value in keys.items():
        path = root / name
        path.write_text(value, encoding="ascii")
        files[name] = {"file": str(path)}
    source = root / "sources.json"
    source.write_text(json.dumps([{"id": "prom-loki", "name": "Candidate",
                      "prometheus": {"url": "http://query-gateway:9090"}, "loki": {"url": "http://query-gateway:3100"}}]))
    alert = root / "alertmanager.yml"
    alert.write_text("""route:
  receiver: unused
  routes:
    - receiver: candidate
      matchers: ['alertname="CandidateFailure"']
      group_wait: 0s
      group_interval: 1s
receivers:
  - name: unused
  - name: candidate
    webhook_configs:
      - url: http://app:8080/api/integrations/alertmanager/primary
        send_resolved: true
        http_config:
          authorization:
            credentials_file: /run/secrets/ALERTMANAGER_TOKEN
""")
    agent = node(environment={"AGENT_MODE": "offline", "BIND_HOST": "0.0.0.0", "SOURCE_REGISTRY_FILE": "/sources.json",
                              "AGENT_TOKEN_FILE": "/run/secrets/AGENT_TOKEN"}, secrets=["AGENT_TOKEN"],
                 volumes=[bind(source, "/sources.json")])
    if agent_runtime:
        agent.update(image=agent_runtime, entrypoint=["python", "/candidate/investigator.py"])
        agent["volumes"].append(bind(ROOT / "agent", "/candidate"))
    else:
        agent["build"] = {"context": str(ROOT), "dockerfile": "agent/Dockerfile"}
    dbkeys = ["POSTGRES_PASSWORD", "APP_DB_PASSWORD", "APP_MIGRATOR_PASSWORD"]
    db_environment = {"POSTGRES_DB": "causelume", "POSTGRES_USER": "causelume_owner",
                      "POSTGRES_PASSWORD_FILE": "/run/secrets/POSTGRES_PASSWORD"}
    db_health = {"test": ["CMD", "pg_isready", "-h", "127.0.0.1", "-U", "causelume_owner", "-d", "causelume"],
                 "interval":"2s", "timeout":"3s", "retries":30}
    roles = bind(ROOT / "deployment/postgres/10-roles.sh", "/docker-entrypoint-initdb.d/10-roles.sh")
    backup = node(profiles=["recovery"], secrets=["APP_DB_PASSWORD", "APP_MIGRATOR_PASSWORD", "RESTIC_PASSWORD"],
                  environment={"RESTIC_REPOSITORY": "/repository", "APP_RELEASE": "candidate"},
                  volumes=["repository:/repository", "backup-work:/work", "restored:/restore",
                           bind(source, "/config/sources.json"), bind(root / "APP_MFA_KEY", "/config/secrets/APP_MFA_KEY")])
    if backup_image:
        backup["image"] = backup_image
    else:
        backup["build"] = {"context": str(ROOT / "deployment/backup")}
    return {"secrets": files, "volumes": {v: {} for v in ("repository", "backup-work", "restored")}, "services": {
        "backup": backup,
        "restore-postgres": node(image="postgres:18-alpine", tmpfs=["/var/lib/postgresql"], profiles=["recovery"],
                                 secrets=dbkeys, volumes=[roles], environment=db_environment, healthcheck=db_health),
        "query-gateway": node(image=CADDY, ports=["127.0.0.1::9090"],
                              volumes=[bind(ROOT / "deployment/caddy/query.Caddyfile", "/etc/caddy/Caddyfile")]),
        "postgres": node(image="postgres:18-alpine", tmpfs=["/var/lib/postgresql"], secrets=dbkeys,
                         environment=db_environment,
                         volumes=[roles],
                         healthcheck=db_health),
        "agent": agent,
        "migrate": node(image=runtime, entrypoint=["java", "-jar", "/candidate.jar", "storage", "migrate-pg"],
                        volumes=[bind(jar, "/candidate.jar")], secrets=["APP_MIGRATOR_PASSWORD"],
                        environment={"APP_DB_URL":"jdbc:postgresql://postgres:5432/causelume"},
                        depends_on={"postgres":{"condition":"service_healthy"}}),
        "app": node(image=runtime, entrypoint=["java", "-jar", "/candidate.jar"], volumes=[bind(jar, "/candidate.jar")],
                    secrets=[k for k in keys if k not in ("POSTGRES_PASSWORD", "RESTIC_PASSWORD", "APP_MIGRATOR_PASSWORD")] + ["MANAGEMENT_TOKEN"],
                    ports=["127.0.0.1::8080", "127.0.0.1::9091"],
                    depends_on={"postgres":{"condition":"service_healthy"},"migrate":{"condition":"service_completed_successfully"}},
                    environment={"SPRING_PROFILES_ACTIVE":"prod", "APP_DB_URL":"jdbc:postgresql://postgres:5432/causelume",
                                 "APP_DB_USER":"causelume_app", "SPRING_FLYWAY_ENABLED":"false", "AGENT_URL":"http://agent:8091", "AGENT_MODE":"offline",
                                 "MANAGEMENT_SERVER_PORT":"9091", "SPRING_MAIL_HOST":"mail", "SPRING_MAIL_PORT":"1025",
                                 "APP_SMTP_FROM":"candidate@example.invalid"}),
        "alertmanager": node(image=AM, volumes=[bind(alert, "/etc/alertmanager/alertmanager.yml")],
                             command=["--config.file=/etc/alertmanager/alertmanager.yml", "--cluster.listen-address="],
                             secrets=["ALERTMANAGER_TOKEN"], ports=["127.0.0.1::9093"]),
        "mail": node(image=MAIL, ports=["127.0.0.1::8025"])}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "backend/target/causelume-1.0.3.jar")
    parser.add_argument("--runtime-image", default=RUNTIME, help="Java 21 runtime image. The mounted JAR supplies the application.")
    parser.add_argument("--agent-runtime", help="existing Python image with agent requirements; mount current source code")
    parser.add_argument("--backup-image", help="prebuilt image from deployment/backup; defaults to building the source")
    args = parser.parse_args()
    if not args.jar.is_file():
        parser.error("build the candidate with mvn -f backend/pom.xml package first")
    project = "causelume-candidate-" + uuid4().hex[:12]
    keys = {k: secrets.token_hex(24) for k in ("POSTGRES_PASSWORD", "APP_DB_PASSWORD", "APP_MIGRATOR_PASSWORD",
            "APP_ADMIN_PASSWORD", "APP_OPERATOR_PASSWORD", "AGENT_TOKEN", "ALERTMANAGER_TOKEN", "RESTIC_PASSWORD")}
    keys["APP_MFA_KEY"] = base64.b64encode(secrets.token_bytes(32)).decode()
    with tempfile.TemporaryDirectory(prefix=project) as directory:
        root = Path(directory)
        overlay = root / "candidate.json"
        overlay.write_text(json.dumps(manifest(root, args.jar, args.runtime_image, args.agent_runtime, args.backup_image, keys)))
        prom = root / "prometheus.yml"
        prom.write_text((OBS / "prometheus.yml").read_text().replace("10s", "1s").replace("4s", "900ms"))
        env = {**os.environ, "CONTRACT_PROM_CONFIG": prom.as_posix(), "CONTRACT_MANAGEMENT_TOKEN": secrets.token_hex(24)}
        cmd = ["docker", "compose", "--env-file", os.devnull, "-p", project,
               "-f", str(OBS / "contract-compose.yml"), "-f", str(overlay)]
        def compose(*args):
            return subprocess.check_output([*cmd, *args], env=env, text=True, timeout=600).strip()
        def assert_runtime_permissions(service):
            # SQL executes with the real application role on both the fresh and restored databases.
            sql = """
                DO $checks$
                DECLARE command text;
                BEGIN
                    UPDATE public.tickets SET title=title WHERE false;
                    FOREACH command IN ARRAY ARRAY[
                        'INSERT INTO public.flyway_schema_history DEFAULT VALUES',
                        'UPDATE public.flyway_schema_history SET success=true WHERE false',
                        'DELETE FROM public.flyway_schema_history WHERE false',
                        'CREATE TABLE public.unexpected_ddl (id integer)'
                    ] LOOP
                        BEGIN
                            EXECUTE command;
                            RAISE EXCEPTION 'runtime unexpectedly allowed %', command;
                        EXCEPTION WHEN insufficient_privilege THEN NULL;
                        END;
                    END LOOP;
                END $checks$;
            """
            compose("exec", "-T", service, "sh", "-ec",
                    'export PGPASSWORD="$(cat /run/secrets/APP_DB_PASSWORD)"; '
                    'exec psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -U causelume_app -d causelume -c "$1"',
                    "check-privileges", sql)

        print(f"Candidate JAR SHA256 {hashlib.sha256(args.jar.read_bytes()).hexdigest()}", flush=True)
        try:
            compose("up", "-d")
            rights = compose("exec", "-T", "postgres", "psql", "-U", "causelume_owner", "-d", "causelume", "-Atc",
                             "select current_setting('server_version_num')::integer/10000,rolsuper,rolcreatedb,"
                             "rolcreaterole,has_schema_privilege(rolname,'public','CREATE') "
                             "from pg_roles where rolname='causelume_app'")
            assert rights == "18|f|f|f|f", "unexpected PostgreSQL version or runtime privileges"
            compose("exec", "-T", "app", "sh", "-ec", "test ! -e /run/secrets/APP_MIGRATOR_PASSWORD")
            assert "Offline storage operation completed." in compose("logs", "migrate"), "dedicated migration did not complete"
            assert_runtime_permissions("postgres")
            exercise(lambda service, port: "http://" + compose("port", service, str(port)), keys, env["CONTRACT_MANAGEMENT_TOKEN"])
            compose("up", "-d", "--wait", "restore-postgres")
            if not args.backup_image:
                compose("build", "backup")
            run = ("run", "--rm", "-T")
            compose(*run, "--entrypoint", "restic", "backup", "--password-file", "/run/secrets/RESTIC_PASSWORD", "init")
            compose(*run, "backup")
            compose(*run, "--entrypoint", "/usr/local/bin/restore-check", "backup")
            compose(*run, "--entrypoint", "sh", "backup", "-ec",
                    "cmp /config/sources.json /restore/config/sources.json && "
                    "cmp /config/secrets/APP_MFA_KEY /restore/config/secrets/APP_MFA_KEY && "
                    "test ! -e /work/causelume.dump && test ! -e /work/dump.sha256 && test ! -e /work/release.txt && "
                    "test -s /work/last-success && test \"$(cat /restore/work/release.txt)\" = candidate")
            assert_runtime_permissions("restore-postgres")
            restored = compose("exec", "-T", "restore-postgres", "sh", "-ec",
                               'export PGPASSWORD="$(cat /run/secrets/APP_DB_PASSWORD)"; '
                               'exec psql -h 127.0.0.1 -U causelume_app -d causelume -Atc "$1"', "sh",
                               "select pg_get_userbyid(relowner),has_table_privilege(current_user,'tickets','UPDATE'),"
                               "(select count(*) from tickets where status='CLOSED' and cause='Explicitly injected example fault') "
                               "from pg_class where oid='public.tickets'::regclass")
            assert restored == "causelume_migrator|t|1", "restored ownership, runtime privileges or incident data differ"
            print("PASS production backup and restore scripts, encrypted local repository, configuration, seven counts and closed incident", flush=True)
        finally:
            compose("--profile", "recovery", "down", "--volumes", "--remove-orphans", "--rmi", "local")


if __name__ == "__main__":
    main()
