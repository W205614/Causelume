"""Verify pg_dump, encrypted restic snapshot and pg_restore using isolated synthetic data."""
import argparse
import json
import os
from pathlib import Path
import secrets
import re
import subprocess
import tempfile
import time
import uuid

PG="postgres:18.6-bookworm@sha256:f1ea2a1a4fb7b68d4836be1fdcb8a6466a89b072158bf9d97663600538c294c5"
RESTIC="restic/restic:0.19.1@sha256:136600b6ff6843d61d355f7f71f460a166429f35de6fd11b568fece3c9a4d510"
def run(*args,**kwargs):
    return subprocess.run(args,check=True,**kwargs)

def main():
    name="causelume-test-restore-"+uuid.uuid4().hex[:10]
    env={**os.environ,"POSTGRES_PASSWORD":secrets.token_urlsafe(24)}
    run("docker","run","-d","--rm","--name",name,"--network","none","--tmpfs","/var/lib/postgresql",
        "-e","POSTGRES_PASSWORD",PG,env=env,stdout=subprocess.DEVNULL)
    try:
        for _ in range(60):
            # The entrypoint's initialization server listens on a socket only.
            if subprocess.run(["docker","exec",name,"pg_isready","-h","127.0.0.1","-U","postgres"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0: break
            time.sleep(.5)
        else: raise TimeoutError("Postgres not ready")
        sql="CREATE TABLE recovery_probe(id bigint primary key,body text); INSERT INTO recovery_probe VALUES(42,'synthetic-restore-evidence');"
        run("docker","exec","-i",name,"psql","-U","postgres","-v","ON_ERROR_STOP=1",input=sql.encode(),stdout=subprocess.DEVNULL)
        with tempfile.TemporaryDirectory(prefix="causelume-restore-") as folder:
            path=Path(folder)
            if os.name == "nt":
                # Docker-created files need an explicit host ACE, not OWNER RIGHTS.
                account=subprocess.check_output(["whoami","/user","/fo","csv","/nh"])
                identity=re.search(rb"S-1-\d+(?:-\d+)+",account)
                if not identity:
                    raise RuntimeError("Could not determine the current Windows user SID")
                run("icacls",str(path),"/grant","*"+identity.group().decode("ascii")+":(OI)(CI)F",
                    stdout=subprocess.DEVNULL)
            (path/"password").write_text(secrets.token_urlsafe(32),encoding="utf-8")
            dump=run("docker","exec",name,"pg_dump","-U","postgres","-Fc","postgres",capture_output=True).stdout
            (path/"database.dump").write_bytes(dump)
            # Linux restores must be readable and removable by the owner of this temporary directory.
            owner = ["--user", f"{os.getuid()}:{os.getgid()}"] if os.name == "posix" else []
            def restic(*args):
                return run("docker","run","--rm","--network","none",*owner,"--entrypoint","restic",
                    "-v",str(path.resolve())+":/test",RESTIC,"--no-cache","-r","/test/repository","--password-file","/test/password",*args,
                    capture_output=True)
            restic("init")
            restic("backup","/test/database.dump","--tag","synthetic-restore")
            restic("check","--read-data")
            restic("restore","latest","--target","/test/restored")
            restored=(path/"restored/test/database.dump").read_bytes()
            assert dump==restored,"Encrypted restore differs"
            run("docker","exec",name,"createdb","-U","postgres","restored")
            run("docker","exec","-i",name,"pg_restore","-U","postgres","-d","restored","--exit-on-error",input=restored,stdout=subprocess.DEVNULL)
            result=run("docker","exec",name,"psql","-U","postgres","-d","restored","-Atc","select body from recovery_probe where id=42",capture_output=True)
            assert result.stdout.strip()==b"synthetic-restore-evidence"
            print("PASS: PostgreSQL18 dump -> encrypted restic -> checked restore -> separate database readback")
    finally:
        subprocess.run(["docker","stop",name],stdout=subprocess.DEVNULL,check=True)
if __name__=="__main__": main()
