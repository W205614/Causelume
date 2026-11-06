#!/bin/sh
set -eu
umask 077
export PGPASSWORD="$(cat /run/secrets/APP_MIGRATOR_PASSWORD)"
export RESTIC_PASSWORD_FILE=/run/secrets/RESTIC_PASSWORD
if [ -s /run/secrets/AWS_ACCESS_KEY_ID ]; then
  export AWS_ACCESS_KEY_ID="$(cat /run/secrets/AWS_ACCESS_KEY_ID)"
  export AWS_SECRET_ACCESS_KEY="$(cat /run/secrets/AWS_SECRET_ACCESS_KEY)"
fi
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
file="/work/causelume.dump"
pg_dump -h postgres -U causelume_migrator -d causelume -Fc --no-owner --no-acl > "$file"
pg_restore --list "$file" > /dev/null
printf '%s\n' "$APP_RELEASE" > /work/release.txt
(cd /work && sha256sum causelume.dump > dump.sha256)
restic backup "$file" /work/dump.sha256 /work/release.txt /config --tag causelume --tag "$APP_RELEASE"
restic check
if [ -n "${BACKUP_HEARTBEAT_URL:-}" ]; then
  curl --fail --silent --show-error --max-time 10 "$BACKUP_HEARTBEAT_URL" > /dev/null
fi
printf '%s\n' "$stamp" > /work/last-success
