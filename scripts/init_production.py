"""Prepare separate server secrets. Existing values are preserved."""
import argparse
import base64
import os
from pathlib import Path
import secrets
import stat

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--directory",type=Path,default=Path("secrets"))
    args=parser.parse_args()
    args.directory.mkdir(mode=0o700,parents=True,exist_ok=True)
    if args.directory.is_symlink():
        parser.error("the secret directory must not be a symlink")
    if os.name != "nt":
        args.directory.chmod(0o700)
    generated=("POSTGRES_PASSWORD","APP_DB_PASSWORD","APP_MIGRATOR_PASSWORD","APP_ADMIN_PASSWORD",
               "APP_OPERATOR_PASSWORD","APP_MFA_KEY","AGENT_TOKEN","MANAGEMENT_TOKEN","ALERTMANAGER_TOKEN",
               "HOLMES_API_KEY","RESTIC_PASSWORD")
    for name in generated:
        target=args.directory/name
        if not target.exists():
            value=base64.b64encode(secrets.token_bytes(32)).decode() if name=="APP_MFA_KEY" else secrets.token_urlsafe(32)
            fd=os.open(target,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
            with os.fdopen(fd,"w",encoding="utf-8") as output: output.write(value)
    external=("SMTP_PASSWORD","OPENAI_API_KEY","AWS_ACCESS_KEY_ID","AWS_SECRET_ACCESS_KEY")
    for name in external:
        target=args.directory/name
        if not target.exists():
            fd=os.open(target,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
            os.close(fd)
    heartbeat=args.directory.parent/"heartbeat.env"
    if not heartbeat.exists():
        template=Path(__file__).resolve().parents[1]/"deployment"/"systemd"/"heartbeat.env.example"
        contents=template.read_bytes()
        fd=os.open(heartbeat,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,"wb") as output: output.write(contents)
    if os.name != "nt":
        info=heartbeat.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
            parser.error("heartbeat.env must be a regular file with a single link")
        heartbeat.chmod(0o600)
        # Compose bind-mounts each file at /run/secrets without remapping its owner.
        # The owner-only directory protects the host; authorized containers get individual read-only files.
        for name in (*generated, *external):
            target=args.directory/name
            info=target.lstat()
            if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
                parser.error(f"expected a regular file with a single link: {name}")
            target.chmod(0o444)
    else:
        import subprocess
        import re
        account=subprocess.check_output(["whoami","/user","/fo","csv","/nh"])
        match=re.search(rb"S-1-\d+(?:-\d+)+",account)
        if not match:
            raise RuntimeError("Could not determine the current Windows user SID")
        identity="*"+match.group().decode("ascii")
        subprocess.run(["icacls",str(args.directory.resolve()),"/inheritance:r","/grant:r",
                        identity+":(OI)(CI)F","*S-1-5-18:(OI)(CI)F"],check=True,stdout=subprocess.DEVNULL)
        subprocess.run(["icacls",str(heartbeat.resolve()),"/inheritance:r","/grant:r",
                        identity+":F","*S-1-5-18:F"],check=True,stdout=subprocess.DEVNULL)
    print("Prepared protected secret files. Fill external provider credentials locally.")

if __name__=="__main__": main()
