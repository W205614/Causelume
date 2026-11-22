#!/bin/sh
set -eu
umask 077
export RESTIC_PASSWORD_FILE=/run/secrets/RESTIC_PASSWORD
if [ -s /run/secrets/AWS_ACCESS_KEY_ID ]; then
  export AWS_ACCESS_KEY_ID="$(cat /run/secrets/AWS_ACCESS_KEY_ID)"
  export AWS_SECRET_ACCESS_KEY="$(cat /run/secrets/AWS_SECRET_ACCESS_KEY)"
fi
if [ "$#" -eq 1 ] && [ "$1" = init ]; then
  exec restic init
fi
if [ "$#" -ne 0 ]; then
  printf '%s\n' 'usage: backup [init]' >&2
  exit 2
fi
exec 9>/work/backup.lock
flock -n 9 || { printf '%s\n' 'Another backup is using the staging volume; this run did not start.' >&2; exit 1; }
. /usr/local/lib/causelume/recovery-status.sh
result=FAILED
heartbeat=NOT_CONFIGURED
[ -z "${BACKUP_HEARTBEAT_URL:-}" ] || heartbeat=NOT_SENT
finish_backup() {
  rm -f /work/causelume.dump /work/dump.sha256 /work/release.txt
  write_recovery_status backup "$result" "$heartbeat"
}
trap finish_backup EXIT
export PGPASSWORD="$(cat /run/secrets/APP_MIGRATOR_PASSWORD)"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
file="/work/causelume.dump"
pg_dump -h postgres -U causelume_migrator -d causelume -Fc --no-owner --no-acl > "$file"
pg_restore --list "$file" > /dev/null
printf '%s\n' "$APP_RELEASE" > /work/release.txt
(cd /work && sha256sum causelume.dump > dump.sha256)
restic backup "$file" /work/dump.sha256 /work/release.txt /config --tag causelume --tag "$APP_RELEASE"
restic check
result=SUCCEEDED
if [ -n "${BACKUP_HEARTBEAT_URL:-}" ]; then
  heartbeat=FAILED
  curl --fail --silent --show-error --max-time 10 "$BACKUP_HEARTBEAT_URL" > /dev/null
  heartbeat=SENT
fi
printf '%s\n' "$stamp" > /work/last-success
