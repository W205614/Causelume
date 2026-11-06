#!/bin/sh
set -eu
cd /opt/causelume
compose() { docker compose --env-file production.env -f compose.production.yaml -f compose.observability.yaml "$@"; }
test -s production.env
for secret in APP_DB_PASSWORD APP_MIGRATOR_PASSWORD APP_MFA_KEY MANAGEMENT_TOKEN SMTP_PASSWORD; do
  test -s "secrets/$secret" || { printf '%s\n' "Missing required secret file $secret" >&2; exit 1; }
done
compose config --quiet
available="$(df -Pk . | awk 'NR==2 {print $4}')"
test "$available" -ge 5242880 || { printf '%s\n' 'At least 5 GiB of free disk required before update' >&2; exit 1; }
compose pull
if [ -n "$(compose ps -q app)" ]; then
  compose --profile maintenance run --rm backup
fi
compose up -d --wait --wait-timeout 180
printf '%s\n' 'Services healthy. Validate account access, a monitored project, delivery and recovery against the release checklist.'
