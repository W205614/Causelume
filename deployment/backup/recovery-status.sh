#!/bin/sh

# The shared volume contains only fixed enums and UTC times. The app mounts it read-only.
write_recovery_status() {
  [ -n "${RECOVERY_STATUS_DIR:-}" ] || return 0
  report="$RECOVERY_STATUS_DIR/$1"
  finished="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  last_success=
  if [ "$2" = SUCCEEDED ]; then
    last_success="$finished"
  elif [ -f "$report" ]; then
    last_success="$(sed -n '3p' "$report")"
  fi
  draft="$(mktemp "$RECOVERY_STATUS_DIR/.$1.XXXXXX")"
  printf '%s\n' "$2" "$finished" "$last_success" "$3" > "$draft"
  chmod 0644 "$draft"
  mv -f "$draft" "$report"
}
