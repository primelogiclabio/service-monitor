#!/usr/bin/env bash
# Checks install.sh --dry-run: it prints the plan, rejects bad input and changes nothing. Safe on any machine,
# because it never installs; CI runs it on every pull request. As root, in a terminal-like session, it also
# answers the "proceed?" prompt (never with yes).
#
#   ./dry-run-test.sh [path-to-jar]
set -euo pipefail

NAME=svcmon-hub
DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
JAR_ARGS=()
[ $# -lt 1 ] || JAR_ARGS=(--jar "$1")

fail() { echo "FAIL: $*" >&2; exit 1; }
check() { local what=$1; shift; "$@" || fail "$what"; echo "ok: $what"; }
has() { grep -q -- "$1" <<<"$OUT"; }
# Everything the installer would create or change, as one comparable string.
state() {
  { id -u $NAME 2>&1 || true
    ls -ld /opt/$NAME /etc/$NAME /var/lib/$NAME /etc/systemd/system/$NAME.service 2>&1 || true
    systemctl is-active $NAME 2>&1 || true; }
}
run() { OUT=$("$DIR/install.sh" "$@" 2>&1) && CODE=0 || CODE=$?; }

before=$(state)

echo "== the plan"
run "${JAR_ARGS[@]}" --dry-run
check "exits 0" test "$CODE" -eq 0
check "says nothing has been changed" has "Install plan (nothing has been changed yet)"
check "shows the default port and URL" has "Port     8080 (default"
check "shows the URL" has "http://localhost:8080"
check "lists numbered steps" has "    1. "
check "names the unit file it would write" has "/etc/systemd/system/$NAME.service"
check "says it is a dry run" has "Dry run only"

echo "== a chosen port"
run "${JAR_ARGS[@]}" --dry-run --port 9090
check "shows the chosen port" has "Port     9090 (--port)"
check "shows the URL for it" has "http://localhost:9090"
check "plans the health check on it" has "http://127.0.0.1:9090/api/v1/health"

echo "== bad input is refused with every problem named"
for bad in 0 abc 70000; do
  run "${JAR_ARGS[@]}" --dry-run --port "$bad"
  check "--port $bad exits 1" test "$CODE" -eq 1
  check "--port $bad says why" has "--port must be a number from 1 to 65535"
done
run --dry-run --jar /nonexistent/hub.jar --java /bin/true
check "a missing jar and a non-Java exit 1" test "$CODE" -eq 1
check "names the missing jar" has "jar not found: /nonexistent/hub.jar"
check "names the java problem" has "is not Java 21 or newer"
check "lists them under one heading" has "Cannot install yet:"

LISTENER=""
trap '[ -z "$LISTENER" ] || kill "$LISTENER" 2>/dev/null || true' EXIT
if command -v python3 >/dev/null && python3 -c 'import socket' 2>/dev/null; then
  echo "== a port that is already taken is flagged"
  python3 -c 'import socket, time; s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); s.bind(("127.0.0.1", 19095)); s.listen(); time.sleep(120)' &
  LISTENER=$!
  sleep 1
  run "${JAR_ARGS[@]}" --dry-run --port 19095
  check "a dry run on the taken port exits 1" test "$CODE" -eq 1
  check "the plan marks the port IN USE" has "Port     19095 (--port) - IN USE by"
  check "the plan says the install would stop" has "Blocked: port 19095 is in use by"
  run "${JAR_ARGS[@]}" --dry-run --port 19096
  check "a free port is reported free" has "Port     19096 (--port) - free"
  check "and the dry run exits 0" test "$CODE" -eq 0
fi

if [ "$(id -u)" -ne 0 ]; then
  echo "== a real run without root is refused"
  run "${JAR_ARGS[@]}"
  check "exits 1" test "$CODE" -eq 1
  check "asks for root" has "run as root"
  if [ -n "$LISTENER" ]; then
    run "${JAR_ARGS[@]}" --port 19095
    check "a real run also names the taken port" has "port 19095 is already in use by"
  fi
fi

if [ "$(id -u)" -eq 0 ] && command -v script >/dev/null && [ -d /run/systemd/system ]; then
  echo "== the prompt (answers 9090, then n)"
  cmd=$(printf '%q ' "$DIR/install.sh" "${JAR_ARGS[@]}" --dry-run)
  OUT=$( { sleep 2; echo 9090; sleep 2; echo n; sleep 1; } | script -qefc "$cmd" /dev/null | tr -d '\r' ) \
    || fail "install.sh --dry-run did not exit 0 at the prompt: $OUT"
  check "asks whether to proceed" has "Proceed with the real installation?"
  check "a port typed at the prompt re-prints the plan with it" has "Port     9090 (--port)"
  check "n cancels" has "Cancelled; nothing was changed."
fi

echo "== the release tarball layout: the jar sits beside install.sh"
TARBALL=$(mktemp -d)
trap '[ -z "$LISTENER" ] || kill "$LISTENER" 2>/dev/null || true; rm -rf "$TARBALL"' EXIT
cp "$DIR/install.sh" "$DIR/uninstall.sh" "$DIR/$NAME.service" "$TARBALL/"
: >"$TARBALL/$NAME-9.9.9.jar"
OUT=$("$TARBALL/install.sh" --dry-run 2>&1) && CODE=0 || CODE=$?
check "a jar beside the script is found without --jar" test "$CODE" -eq 0
check "the plan installs that jar" has "$NAME-9.9.9.jar -> /opt/$NAME/$NAME.jar"
: >"$TARBALL/$NAME-9.9.8.jar"
OUT=$("$TARBALL/install.sh" --dry-run 2>&1) && CODE=0 || CODE=$?
check "two jars beside the script are refused" test "$CODE" -eq 1
check "and the message says where" has "more than one jar beside this script"
OUT=$("$TARBALL/install.sh" --dry-run --jar "$TARBALL/$NAME-9.9.8.jar" 2>&1) && CODE=0 || CODE=$?
check "--jar still picks one of them" test "$CODE" -eq 0
check "and it is the one named" has "$NAME-9.9.8.jar -> /opt/$NAME/$NAME.jar"

echo "== nothing was changed"
after=$(state)
[ "$before" = "$after" ] || { diff <(echo "$before") <(echo "$after") || true; fail "the dry runs changed the machine"; }
echo "ok: user, folders, unit and service are as they were"

echo "All checks passed."
