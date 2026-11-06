#!/bin/sh
set -eu
cd /opt/causelume
# The public health URL verifies DNS, TLS and the reverse-proxy path as well.
. ./heartbeat.env
curl --fail --silent --show-error --max-time 10 "$PUBLIC_HEALTH_URL" > /dev/null
curl --fail --silent --show-error --max-time 10 "$EXTERNAL_HEARTBEAT_URL" > /dev/null
