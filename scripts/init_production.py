"""Prepare separate server secrets. Existing values are preserved."""
import argparse
import base64
import os
from pathlib import Path
import secrets

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--directory",type=Path,default=Path("secrets"))
    args=parser.parse_args()
    args.directory.mkdir(mode=0o700,parents=True,exist_ok=True)
    generated=("POSTGRES_PASSWORD","APP_DB_PASSWORD","APP_MIGRATOR_PASSWORD","APP_ADMIN_PASSWORD",
               "APP_OPERATOR_PASSWORD","APP_MFA_KEY","AGENT_TOKEN","MANAGEMENT_TOKEN","ALERTMANAGER_TOKEN",
               "HOLMES_API_KEY","RESTIC_PASSWORD")
    for name in generated:
        target=args.directory/name
        if not target.exists():
            value=base64.b64encode(secrets.token_bytes(32)).decode() if name=="APP_MFA_KEY" else secrets.token_urlsafe(32)
            fd=os.open(target,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
            with os.fdopen(fd,"w",encoding="utf-8") as output: output.write(value)
    for name in ("SMTP_PASSWORD","OPENAI_API_KEY","AWS_ACCESS_KEY_ID","AWS_SECRET_ACCESS_KEY"):
        target=args.directory/name
        if not target.exists():
            fd=os.open(target,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
            os.close(fd)
    if os.name=="nt":
        import subprocess
        identity=subprocess.check_output(["whoami"],text=True).strip()
        subprocess.run(["icacls",str(args.directory.resolve()),"/inheritance:r","/grant:r",
                        identity+":(OI)(CI)F","*S-1-5-18:(OI)(CI)F"],check=True,stdout=subprocess.DEVNULL)
    print("Prepared protected secret files. Fill external provider credentials locally.")

if __name__=="__main__": main()
