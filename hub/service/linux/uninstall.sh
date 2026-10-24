#!/usr/bin/env bash
# Removes the Service Monitor hub service. Run as root.
#
#   sudo ./uninstall.sh [--purge]
#
# Without --purge the settings (/etc/svcmon-hub), the database (/var/lib/svcmon-hub) and the svcmon-hub user
# stay, so a later install picks up where this one left off. --purge deletes them too, which cannot be undone.
set -euo pipefail

NAME=svcmon-hub

die() { echo "error: $*" >&2; exit 1; }

PURGE=false
case "${1:-}" in
  "") ;;
  --purge) PURGE=true ;;
  -h|--help) echo "Usage: sudo ./uninstall.sh [--purge]"; exit 0 ;;
  *) die "unknown option: $1" ;;
esac
[ $# -le 1 ] || die "too many arguments"
[ "$(id -u)" -eq 0 ] || die "run as root (sudo ./uninstall.sh)"

if systemctl cat $NAME >/dev/null 2>&1; then
  systemctl disable --now $NAME >/dev/null 2>&1 || true
fi
rm -f /etc/systemd/system/$NAME.service
systemctl daemon-reload
systemctl reset-failed $NAME >/dev/null 2>&1 || true
rm -rf /opt/$NAME
echo "removed service $NAME and /opt/$NAME"

if [ "$PURGE" = true ]; then
  rm -rf /etc/$NAME /var/lib/$NAME
  if id -u $NAME >/dev/null 2>&1; then
    userdel $NAME 2>/dev/null || true
  fi
  echo "deleted /etc/$NAME, /var/lib/$NAME and the $NAME user"
else
  echo "kept /etc/$NAME and /var/lib/$NAME (settings and database); add --purge to delete them"
fi
