#!/usr/bin/env bash
# Installs the hub as a service on this machine, checks it, upgrades it, then removes it. CI runs it on a
# disposable runner; do not run it on a machine that already has a svcmon-hub service or data.
#
#   sudo ./smoke-test.sh [path-to-jar]
set -euo pipefail

NAME=svcmon-hub
PORT=18080
URL=http://127.0.0.1:$PORT
DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
JAR_ARGS=()
[ $# -lt 1 ] || JAR_ARGS=(--jar "$1")

fail() { echo "FAIL: $*" >&2; journalctl -u $NAME --no-pager -n 40 >&2 || true; exit 1; }
check() { local what=$1; shift; "$@" || fail "$what"; echo "ok: $what"; }
healthy() { curl -fsS "$URL/api/v1/health" >/dev/null; }
token_works() { curl -fsS -H "Authorization: Bearer $1" "$URL/api/v1/hub" >/dev/null; }
gone() { ! "$@" >/dev/null 2>&1; }

[ "$(id -u)" -eq 0 ] || fail "run as root"
! systemctl cat $NAME >/dev/null 2>&1 || fail "$NAME is already installed here; refusing to touch it"

echo "== fresh install"
out=$("$DIR/install.sh" "${JAR_ARGS[@]}" --port $PORT)
echo "$out"
TOKEN=$(echo "$out" | grep -o 'svm_a_[0-9a-f]\{64\}' | head -n 1)
check "install printed an admin token" test -n "$TOKEN"
check "service is active" systemctl is-active --quiet $NAME
check "service starts at boot" systemctl is-enabled --quiet $NAME
check "health answers on the chosen port" healthy
check "the printed token is accepted" token_works "$TOKEN"
check "the token is not left in the settings file" gone grep -q bootstrap-admin-token /etc/$NAME/application.yml
check "the token is not in the journal" gone sh -c "journalctl -u $NAME --no-pager | grep -q '$TOKEN'"
check "settings file is not world readable" test "$(stat -c %a /etc/$NAME/application.yml)" = 640
check "runs as the service user" test "$(ps -o uid= -p "$(systemctl show -p MainPID --value $NAME)" | tr -d ' ')" = "$(id -u $NAME)"
check "database is in the data folder" test -f /var/lib/$NAME/svcmon.db

echo "== upgrade (run again)"
out=$("$DIR/install.sh" "${JAR_ARGS[@]}")
echo "$out"
check "upgrade keeps the old port from the settings file" healthy
check "upgrade keeps the admin token valid" token_works "$TOKEN"
check "upgrade prints no second token" gone sh -c "echo '$out' | grep -q 'svm_a_'"

echo "== change the port"
OLD_URL=$URL
PORT=18081
URL=http://127.0.0.1:$PORT
out=$("$DIR/install.sh" "${JAR_ARGS[@]}" --port $PORT)
echo "$out"
check "health answers on the new port" healthy
check "the old port is closed" gone curl -fsS "$OLD_URL/api/v1/health"
check "the settings file has the new port" grep -qx '  port: 18081' /etc/$NAME/application.yml
check "the admin token is still valid on the new port" token_works "$TOKEN"

echo "== restart"
systemctl restart $NAME
for _ in $(seq 1 60); do healthy && break; sleep 1; done
check "healthy after a restart" healthy
check "token still valid after a restart" token_works "$TOKEN"

echo "== uninstall, keeping data"
"$DIR/uninstall.sh"
check "service is gone" gone systemctl cat $NAME
check "program folder is gone" test ! -e /opt/$NAME
check "the hub no longer answers" gone healthy
check "settings are kept" test -f /etc/$NAME/application.yml
check "database is kept" test -f /var/lib/$NAME/svcmon.db
check "service user is kept" id -u $NAME

echo "== reinstall over kept data, then purge"
out=$("$DIR/install.sh" "${JAR_ARGS[@]}")
echo "$out"
check "reinstall prints no new token" gone sh -c "echo '$out' | grep -q 'svm_a_'"
check "reinstall keeps the old token valid" token_works "$TOKEN"
"$DIR/uninstall.sh" --purge
check "settings are deleted" test ! -e /etc/$NAME
check "database is deleted" test ! -e /var/lib/$NAME
check "service user is deleted" gone id -u $NAME

echo "All checks passed."
