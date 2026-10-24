#!/usr/bin/env bash
# Installs the Service Monitor hub as a systemd service. Run as root; safe to run again to upgrade.
#
#   sudo ./install.sh [--jar PATH] [--java PATH] [--port N]
#
# Layout: program /opt/svcmon-hub, settings /etc/svcmon-hub/application.yml, database /var/lib/svcmon-hub,
# log in the journal (journalctl -u svcmon-hub). Runs as the system user svcmon-hub. An existing
# application.yml and database are never touched.
set -euo pipefail

NAME=svcmon-hub
APP_DIR=/opt/$NAME
CONF_DIR=/etc/$NAME
DATA_DIR=/var/lib/$NAME
UNIT=/etc/systemd/system/$NAME.service

die() { echo "error: $*" >&2; exit 1; }

usage() {
  cat <<'EOF'
Usage: sudo ./install.sh [--jar PATH] [--java PATH] [--port N]

  --jar PATH    hub jar to install (default: hub/target/svcmon-hub-*.jar next to this script's repo)
  --java PATH   java 21+ to run it with (default: java from PATH)
  --port N      HTTP port for a new install (default 8080; an existing application.yml is kept as it is)
EOF
}

JAR=""
JAVA=""
PORT=8080
while [ $# -gt 0 ]; do
  case "$1" in
    --jar) [ $# -ge 2 ] || die "--jar needs a value"; JAR=$2; shift 2 ;;
    --java) [ $# -ge 2 ] || die "--java needs a value"; JAVA=$2; shift 2 ;;
    --port) [ $# -ge 2 ] || die "--port needs a value"; PORT=$2; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; die "unknown option: $1" ;;
  esac
done

[ "$(id -u)" -eq 0 ] || die "run as root (sudo ./install.sh)"
[ -d /run/systemd/system ] || die "systemd is not running on this machine"
command -v curl >/dev/null || die "curl is needed to check that the hub started"
case "$PORT" in ''|*[!0-9]*) die "--port must be a number" ;; esac
{ [ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ]; } || die "--port must be between 1 and 65535"

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
if [ -z "$JAR" ]; then
  for candidate in "$SCRIPT_DIR"/../../target/svcmon-hub-*.jar; do
    [ -f "$candidate" ] || continue
    [ -z "$JAR" ] || die "more than one jar in hub/target; pick one with --jar"
    JAR=$candidate
  done
  [ -n "$JAR" ] || die "no jar found; build it (cd hub && mvn -DskipTests package) or pass --jar"
fi
[ -f "$JAR" ] || die "jar not found: $JAR"

if [ -z "$JAVA" ]; then
  JAVA=$(command -v java || true)
  [ -n "$JAVA" ] || die "java not found on PATH; install Java 21 or pass --java"
fi
JAVA=$(readlink -f "$JAVA")
[ -x "$JAVA" ] || die "not an executable: $JAVA"
# The path goes into the unit file's ExecStart, where spaces and % mean something else.
case "$JAVA" in *[[:space:]\|\&\\%]*) die "java path has a character the unit file cannot hold: $JAVA" ;; esac
major=$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)
{ [ -n "$major" ] && [ "$major" -ge 21 ]; } || die "$JAVA is not Java 21 or newer"

# Everything below changes the machine.
if ! id -u $NAME >/dev/null 2>&1; then
  useradd --system --user-group --home-dir "$DATA_DIR" --no-create-home \
    --shell "$(command -v nologin || echo /usr/sbin/nologin)" $NAME
  echo "created system user $NAME"
fi

install -d -m 0755 -o root -g root "$APP_DIR"
install -d -m 0750 -o root -g $NAME "$CONF_DIR"
install -d -m 0750 -o $NAME -g $NAME "$DATA_DIR"

was_active=false
if systemctl is-active --quiet $NAME; then
  was_active=true
  systemctl stop $NAME
fi

# Copy beside the target and rename, so a half-written jar is never in place.
install -m 0644 -o root -g root "$JAR" "$APP_DIR/.$NAME.jar.new"
mv -f "$APP_DIR/.$NAME.jar.new" "$APP_DIR/$NAME.jar"

# The hub prints a new database's admin token once, into the journal. For a fresh install the installer
# instead puts a token of its own in the new settings file for the first start only, checks it works, shows
# it to you once and takes it out of the file again. A settings file that already exists is never edited.
TOKEN=""
CONF=$CONF_DIR/application.yml
if [ -f "$CONF" ]; then
  echo "kept existing $CONF"
  conf_port=$(sed -n 's/^  port: *\([0-9][0-9]*\).*/\1/p' "$CONF" | head -n 1)
  [ -z "$conf_port" ] || PORT=$conf_port
else
  token_line=""
  if [ ! -e "$DATA_DIR/svcmon.db" ]; then
    TOKEN="svm_a_$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    token_line="  bootstrap-admin-token: $TOKEN"
  fi
  umask 077
  cat >"$CONF" <<EOF
# Service Monitor hub settings. Every key in the README's configuration table can be set here;
# restart the service after editing (systemctl restart $NAME).
server:
  port: $PORT
svcmon:
  data-dir: $DATA_DIR
$token_line
EOF
  umask 022
  chown root:$NAME "$CONF"
  chmod 0640 "$CONF"
  [ -n "$token_line" ] || sed -i '${/^$/d}' "$CONF"
fi

drop_token() {
  # Never leave the bootstrap token in the settings file, whatever happened.
  if [ -n "$TOKEN" ] && [ -f "$CONF" ]; then
    sed -i '/^  bootstrap-admin-token:/d' "$CONF"
  fi
}
trap drop_token EXIT

sed "s|@JAVA@|$JAVA|" "$SCRIPT_DIR/$NAME.service" >"$UNIT"
chmod 0644 "$UNIT"
systemctl daemon-reload
systemctl enable $NAME >/dev/null 2>&1
systemctl restart $NAME

url=http://127.0.0.1:$PORT
up=false
for _ in $(seq 1 60); do
  if curl -fsS "$url/api/v1/health" >/dev/null 2>&1; then up=true; break; fi
  sleep 1
done
$up || die "the hub did not answer on $url within 60 seconds; see: journalctl -u $NAME -n 50"

if [ -n "$TOKEN" ]; then
  ok=false
  for _ in $(seq 1 10); do
    if curl -fsS -H "Authorization: Bearer $TOKEN" "$url/api/v1/hub" >/dev/null 2>&1; then ok=true; break; fi
    sleep 1
  done
  $ok || die "the hub is up but did not accept its new admin token; see: journalctl -u $NAME -n 50"
fi
drop_token

echo
echo "Service Monitor hub is running: $url  (service $NAME, starts at boot)"
echo "  settings  $CONF"
echo "  database  $DATA_DIR"
echo "  log       journalctl -u $NAME"
if [ -n "$TOKEN" ]; then
  echo
  echo "Admin token (shown once, store it somewhere safe; only its hash is kept):"
  echo "  $TOKEN"
elif [ -e "$DATA_DIR/svcmon.db" ] && [ "$was_active" = true ]; then
  echo "Upgraded; the existing database and admin token are unchanged."
else
  echo "No admin token was created by this run. A new database prints one to the journal on first start"
  echo "(journalctl -u $NAME | grep -A1 'New admin token'); for an existing database keep the token you have."
fi
