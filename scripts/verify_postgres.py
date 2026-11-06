"""Run the PostgreSQL 18 account and migration contracts in a disposable local container."""
import argparse
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import time
import uuid


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maven", default=shutil.which("mvn") or shutil.which("mvn.cmd"))
    parser.add_argument("--image", default="postgres:18-alpine")
    args = parser.parse_args()
    if not args.maven:
        parser.error("pass --maven /path/to/mvn")

    name = "causelume-test-pg-" + uuid.uuid4().hex[:12]
    env = os.environ.copy()
    for key in ("POSTGRES_PASSWORD", "APP_MIGRATOR_PASSWORD", "APP_DB_PASSWORD"):
        env[key] = secrets.token_hex(24)
    env["APP_DB_USER"] = "causelume_app"
    # The tests exercise the unavailable-upstream path without contacting a configured agent.
    env.pop("AGENT_URL", None)
    env.pop("AGENT_TOKEN", None)
    for key in ("APP_SOURCE_DB_USER", "APP_SOURCE_DB_PASSWORD", "APP_PUBLIC_ORIGIN"):
        env.pop(key, None)
        env.pop(key + "_FILE", None)
    run("docker", "run", "-d", "--rm", "--name", name, "--label", "io.causelume.test=postgres",
        "-e", "POSTGRES_PASSWORD", "-p", "127.0.0.1::5432", "--tmpfs", "/var/lib/postgresql", args.image,
        env=env, stdout=subprocess.DEVNULL)
    try:
        for _ in range(60):
            if subprocess.run(["docker", "exec", name, "pg_isready", "-h", "127.0.0.1", "-U", "postgres"],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("isolated PostgreSQL did not become ready")
        port = run("docker", "port", name, "5432", capture_output=True).stdout.strip().split(":")[-1]
        setup = f"""
CREATE ROLE causelume_migrator LOGIN PASSWORD '{env["APP_MIGRATOR_PASSWORD"]}';
CREATE ROLE causelume_app LOGIN PASSWORD '{env["APP_DB_PASSWORD"]}';
CREATE DATABASE auth_contract OWNER causelume_migrator;
CREATE DATABASE storage_contract OWNER causelume_migrator;
"""
        def sql(database, statements):
            run("docker", "exec", "-i", name, "psql", "-q", "-U", "postgres", "-d", database,
                "-v", "ON_ERROR_STOP=1", input=statements)

        sql("postgres", setup)
        for db in ("auth_contract", "storage_contract"):
            sql(db, """REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE causelume_migrator IN SCHEMA public
 GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO causelume_app;
ALTER DEFAULT PRIVILEGES FOR ROLE causelume_migrator IN SCHEMA public
 GRANT USAGE,SELECT ON SEQUENCES TO causelume_app;""")
        env["APP_DB_URL"] = f"jdbc:postgresql://127.0.0.1:{port}/storage_contract"
        env["TEST_PG_APP_URL"] = f"jdbc:postgresql://127.0.0.1:{port}/auth_contract"
        root = Path(__file__).resolve().parents[1]
        run(args.maven, "-B", "-ntp", "-f", str(root / "backend/pom.xml"), "-Dtest=Postgres*IT", "test", env=env)
    finally:
        run("docker", "stop", name, stdout=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
