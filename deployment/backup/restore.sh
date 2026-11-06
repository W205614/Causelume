#!/bin/sh
set -eu
umask 077
export RESTIC_PASSWORD_FILE=/run/secrets/RESTIC_PASSWORD
if [ -s /run/secrets/AWS_ACCESS_KEY_ID ]; then
  export AWS_ACCESS_KEY_ID="$(cat /run/secrets/AWS_ACCESS_KEY_ID)"
  export AWS_SECRET_ACCESS_KEY="$(cat /run/secrets/AWS_SECRET_ACCESS_KEY)"
fi
export PGPASSWORD="$(cat /run/secrets/APP_MIGRATOR_PASSWORD)"
restic check
restic restore latest --tag causelume --target /restore
test -s /restore/work/causelume.dump
test -s /restore/work/release.txt
(cd /restore/work && sha256sum -c dump.sha256)
pg_restore -h restore-postgres -U causelume_migrator -d causelume --no-owner --no-acl --exit-on-error /restore/work/causelume.dump
export PGPASSWORD="$(cat /run/secrets/APP_DB_PASSWORD)"
for table in accounts services alerts tickets tasks timeline audit; do
  pg_restore --data-only --table="$table" --file=/restore/table.sql /restore/work/causelume.dump
  expected="$(awk '/^COPY / {copy=1;next} copy && $0=="\\." {copy=0;next} copy {n++} END {print n+0}' /restore/table.sql)"
  actual="$(psql -h restore-postgres -U causelume_app -d causelume -Atc "select count(*) from $table")"
  test "$expected" = "$actual" || { printf '%s\n' "Restore row count mismatch: $table" >&2; exit 1; }
done
printf '%s\n' 'Restored encrypted release and matched table counts in the isolated database.'
