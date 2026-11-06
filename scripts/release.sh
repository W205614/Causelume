#!/bin/sh
set -eu
cd /opt/causelume
compose() {
  if [ -f deployment/observability/compose.site.yaml ]; then
    set -- -f deployment/observability/compose.site.yaml "$@"
  fi
  docker compose --env-file production.env -f compose.production.yaml -f compose.observability.yaml "$@"
}
test -s production.env
test -f heartbeat.env || { printf '%s\n' 'Initialize heartbeat.env with scripts/init_production.py before release' >&2; exit 1; }
for secret in APP_DB_PASSWORD APP_MIGRATOR_PASSWORD APP_MFA_KEY MANAGEMENT_TOKEN SMTP_PASSWORD; do
  test -s "secrets/$secret" || { printf '%s\n' "Missing required secret file $secret" >&2; exit 1; }
done
compose config --quiet
available="$(df -Pk . | awk 'NR==2 {print $4}')"
test "$available" -ge 5242880 || { printf '%s\n' 'At least 5 GiB of free disk required before update' >&2; exit 1; }
pg_volume="$(compose config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["volumes"]["postgres"]["name"])')"
existing="$(docker volume ls --format '{{.Name}}' --filter "name=^${pg_volume}$")"
running="$(compose ps --status running -q postgres)"
if [ -n "$existing" ]; then
  if [ -z "$running" ]; then
    printf '%s\n' "Existing database volume $pg_volume has no running PostgreSQL service. Start the current compatible PostgreSQL container, verify its data and take a backup before upgrading." >&2
    exit 1
  fi
  mounted="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql"}}{{.Name}}{{end}}{{end}}' "$running")"
  test "$mounted" = "$pg_volume" || { printf '%s\n' 'Running PostgreSQL uses a different data mount; verify the previous deployment before upgrading.' >&2; exit 1; }
  old_app="$(compose ps --all -q app)"
  old_image=unknown
  if [ -n "$old_app" ]; then
    old_image="$(docker inspect --format '{{.Config.Image}}' "$old_app")"
    case "$old_image" in
      *@sha256:*|sha256:*) ;;
      *) old_image="$(docker inspect --format '{{.Image}}' "$old_app")" ;;
    esac
  fi
  compose --profile maintenance run --rm --no-deps -e "APP_RELEASE=pre-upgrade:$old_image" backup
elif [ -n "$running" ]; then
  printf '%s\n' 'Running PostgreSQL found without the expected named volume; inspect its data mount before upgrading.' >&2
  exit 1
fi
compose pull
compose up -d --wait --wait-timeout 180
printf '%s\n' 'Services healthy. Validate account access, a monitored project, delivery and recovery against the release checklist.'
