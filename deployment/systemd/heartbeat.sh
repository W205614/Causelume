#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
. ./heartbeat.env
available="$(df -Pk "${DISK_CHECK_PATH:-/var/lib/docker}" | awk 'NR==2 {print $4}')"
test "$available" -ge "${MIN_FREE_DISK_KIB:-5242880}" || { printf '%s\n' 'Docker data partition is below its free-space threshold' >&2; exit 1; }
# The public health URL verifies DNS, TLS and the reverse-proxy path as well.
curl --fail --silent --show-error --max-time 10 "$PUBLIC_HEALTH_URL" > /dev/null
curl --fail --silent --show-error --max-time 10 "$EXTERNAL_HEARTBEAT_URL" > /dev/null
