#!/usr/bin/env bash
# Installs the Service Monitor hub as a systemd service. Run as root; safe to run again to upgrade.
#
#   sudo ./install.sh [--jar PATH] [--java PATH] [--port N] [--dry-run]
#
# Layout: program /opt/svcmon-hub, settings /etc/svcmon-hub/application.yml, database /var/lib/svcmon-hub,
# log in the journal (journalctl -u svcmon-hub). Runs as the system user svcmon-hub. An existing
# application.yml and database are never touched, except for its port line when you pass --port.
set -euo pipefail

NAME=svcmon-hub
APP_DIR=/opt/$NAME
CONF_DIR=/etc/$NAME
DATA_DIR=/var/lib/$NAME
CONF=$CONF_DIR/application.yml
UNIT=/etc/systemd/system/$NAME.service

die() { echo "error: $*" >&2; exit 1; }

usage() {
  cat <<'EOF'
Usage: sudo ./install.sh [--jar PATH] [--java PATH] [--port N] [--dry-run]

  --jar PATH    hub jar to install (default: hub/target/svcmon-hub-*.jar next to this script's repo)
  --java PATH   java 21+ to run it with (default: java from PATH)
  --port N      HTTP port (default 8080 for a new install). On an existing install it changes server.port in
                application.yml; without --port the port already in that file is kept.
  --dry-run     print what the install would do and change nothing. In a terminal, run as root, it then asks
                whether to go ahead (answer y or n, or type a different port number); otherwise it only
                prints the plan.
EOF
}

JAR=""
JAVA=""
PORT=""
DRY_RUN=false
while [ $# -gt 0 ]; do
  case "$1" in
    --jar) [ $# -ge 2 ] || die "--jar needs a value"; JAR=$2; shift 2 ;;
    --java) [ $# -ge 2 ] || die "--java needs a value"; JAVA=$2; shift 2 ;;
    --port) [ $# -ge 2 ] || die "--port needs a value"; PORT=$2; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; die "unknown option: $1" ;;
  esac
done

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
IS_ROOT=false
[ "$(id -u)" -eq 0 ] && IS_ROOT=true

valid_port() {
  case "$1" in ''|*[!0-9]*) return 1 ;; esac
  [ "$1" -ge 1 ] && [ "$1" -le 65535 ]
}

# ---- 1. Look at the machine and the inputs. Nothing here changes anything. ----

problems=()
problem() { problems+=("$*"); }

[ "$IS_ROOT" = true ] || [ "$DRY_RUN" = true ] || problem "run as root (sudo ./install.sh)"
[ -d /run/systemd/system ] || problem "systemd is not running on this machine"
command -v curl >/dev/null || problem "curl is needed to check that the hub started"
if [ -n "$PORT" ] && ! valid_port "$PORT"; then problem "--port must be a number from 1 to 65535"; fi

if [ -z "$JAR" ]; then
  matches=("$SCRIPT_DIR"/../../target/svcmon-hub-*.jar)
  if [ ! -f "${matches[0]}" ]; then
    problem "no jar found; build it (cd hub && mvn -DskipTests package) or pass --jar"
  elif [ "${#matches[@]}" -gt 1 ]; then
    problem "more than one jar in hub/target; pick one with --jar"
  else
    JAR=${matches[0]}
  fi
elif [ ! -f "$JAR" ]; then
  problem "jar not found: $JAR"
  JAR=""
fi

major=""
if [ -z "$JAVA" ]; then
  JAVA=$(command -v java || true)
  [ -n "$JAVA" ] || problem "java not found on PATH; install Java 21 or pass --java"
fi
if [ -n "$JAVA" ]; then
  JAVA=$(readlink -f "$JAVA" || echo "$JAVA")
  # The path goes into the unit file's ExecStart, where spaces and % mean something else.
  bad_chars=false
  case "$JAVA" in *[[:space:]\|\&\\%]*) bad_chars=true ;; esac
  if [ ! -x "$JAVA" ]; then
    problem "not an executable: $JAVA"
  elif [ "$bad_chars" = true ]; then
    problem "java path has a character the unit file cannot hold: $JAVA"
  else
    major=$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)
    { [ -n "$major" ] && [ "$major" -ge 21 ]; } || problem "$JAVA is not Java 21 or newer"
  fi
fi

if [ "${#problems[@]}" -gt 0 ]; then
  echo "Cannot install yet:" >&2
  printf '  - %s\n' "${problems[@]}" >&2
  exit 1
fi

user_exists=false; id -u $NAME >/dev/null 2>&1 && user_exists=true
unit_exists=false; [ -f "$UNIT" ] && unit_exists=true
was_active=false; systemctl is-active --quiet $NAME 2>/dev/null && was_active=true
conf_exists=false; [ -e "$CONF" ] && conf_exists=true
db_exists=false; [ -e "$DATA_DIR/svcmon.db" ] && db_exists=true
conf_port=""
# The settings file is readable by root only; a dry run as another user cannot see the port in it.
[ -r "$CONF" ] && conf_port=$(sed -n -e 's/^  port: *\([0-9][0-9]*\).*/\1/p' -e 's/^server\.port: *\([0-9][0-9]*\).*/\1/p' "$CONF" | head -n 1)

# ---- 2. The plan ----

print_plan() {
  local mode port note n=0
  if [ "$unit_exists" = true ]; then
    mode="upgrade: the service is installed; its settings and data are kept"
  elif [ "$conf_exists" = true ] || [ "$db_exists" = true ]; then
    mode="reinstall over settings and data left by an earlier install"
  else
    mode="new install"
  fi
  if [ -n "$PORT" ]; then
    port=$PORT
    if [ "$conf_exists" = true ] && [ "$PORT" != "$conf_port" ]; then
      note="--port; changes ${conf_port:-the port} in the settings file"
    else
      note="--port"
    fi
  elif [ "$conf_exists" = true ]; then
    port=${conf_port:-8080}
    note="kept from $CONF"
  else
    port=8080
    note="default; change it with --port"
  fi
  PLAN_PORT=$port

  step() { n=$((n + 1)); printf '    %d. %s\n' "$n" "$*"; }
  echo "Install plan (nothing has been changed yet)"
  printf '  %-8s %s\n' "Mode" "$mode"
  printf '  %-8s %s\n' "Jar" "$JAR -> $APP_DIR/$NAME.jar"
  printf '  %-8s %s\n' "Java" "$JAVA (version $major)"
  printf '  %-8s %s\n' "Port" "$port ($note)"
  printf '  %-8s %s\n' "URL" "http://localhost:$port"
  echo "  Steps:"
  if [ "$user_exists" = true ]; then
    step "use the existing system user $NAME"
  else
    step "create the system user $NAME (no login shell)"
  fi
  step "create or reuse $APP_DIR (root), $CONF_DIR (root:$NAME, 0750) and $DATA_DIR ($NAME, 0750)"
  [ "$was_active" = false ] || step "stop the running $NAME service"
  step "install the jar as $APP_DIR/$NAME.jar"
  if [ "$conf_exists" = false ]; then
    step "write $CONF with port $port and the data folder $DATA_DIR (0640)"
  elif [ "$port" != "$conf_port" ]; then
    step "keep $CONF, changing only its port line to $port"
  else
    step "keep $CONF as it is"
  fi
  step "write the systemd unit $UNIT (runs $JAVA as $NAME), reload systemd and enable it at boot"
  step "start the service and wait up to 60 s for http://127.0.0.1:$port/api/v1/health"
  if [ "$db_exists" = true ]; then
    step "keep the existing database and its admin token"
  elif [ "$conf_exists" = false ]; then
    step "make an admin token for the new database: put it in the new settings file for the first start, check it works, print it once, remove it from the file"
  else
    step "no admin token is made here (existing settings file); the hub prints one to the journal on first start"
  fi
  echo "  Not touched: the firewall (open port $port yourself if agents connect from other machines), other services."
  [ "$IS_ROOT" = true ] || echo "  A real install needs root: run it with sudo."
}

if [ "$DRY_RUN" = true ]; then
  print_plan
  if [ "$IS_ROOT" = false ] || [ ! -t 0 ] || [ ! -t 1 ]; then
    echo
    echo "Dry run only. Run again without --dry-run (as root) to install."
    exit 0
  fi
  while true; do
    echo
    printf 'Proceed with the real installation? [y/N], or type a port number to change it: '
    read -r answer || answer=n
    case "$answer" in
      y|Y|yes|YES) break ;;
      ''|n|N|no|NO) echo "Cancelled; nothing was changed."; exit 0 ;;
      *[!0-9]*) echo "Please answer y, n, or a port number." ;;
      *)
        if valid_port "$answer"; then
          PORT=$answer
          echo
          print_plan
        else
          echo "A port is a number from 1 to 65535."
        fi
        ;;
    esac
  done
  echo
fi

# ---- 3. The install. Everything below changes the machine. ----

if [ "$user_exists" = false ]; then
  useradd --system --user-group --home-dir "$DATA_DIR" --no-create-home \
    --shell "$(command -v nologin || echo /usr/sbin/nologin)" $NAME
  echo "created system user $NAME"
fi

install -d -m 0755 -o root -g root "$APP_DIR"
install -d -m 0750 -o root -g $NAME "$CONF_DIR"
install -d -m 0750 -o $NAME -g $NAME "$DATA_DIR"

if [ "$was_active" = true ]; then
  systemctl stop $NAME
fi

# Copy beside the target and rename, so a half-written jar is never in place.
install -m 0644 -o root -g root "$JAR" "$APP_DIR/.$NAME.jar.new"
mv -f "$APP_DIR/.$NAME.jar.new" "$APP_DIR/$NAME.jar"

# The hub prints a new database's admin token once, into the journal. For a fresh install the installer
# instead puts a token of its own in the new settings file for the first start only, checks it works, shows
# it to you once and takes it out of the file again. A settings file that already exists is only ever edited
# for --port, and only its port line.
TOKEN=""
if [ "$conf_exists" = true ]; then
  echo "kept existing $CONF"
  if [ -z "$PORT" ]; then
    PORT=${conf_port:-8080}
  elif [ "$PORT" != "$conf_port" ]; then
    if grep -q '^  port:' "$CONF"; then
      sed -i "s/^  port:.*/  port: $PORT/" "$CONF"
    elif grep -q '^server\.port:' "$CONF"; then
      sed -i "s/^server\.port:.*/server.port: $PORT/" "$CONF"
    elif grep -q '^server:' "$CONF"; then
      sed -i "/^server:/a\\  port: $PORT" "$CONF"
    else
      printf '\nserver:\n  port: %s\n' "$PORT" >>"$CONF"
    fi
    echo "set the port to $PORT in $CONF"
  fi
else
  PORT=${PORT:-8080}
  token_line=""
  if [ "$db_exists" = false ]; then
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
echo "  log       journalctl -u $NAME  (the start-up summary lists what it watches)"
if [ -n "$TOKEN" ]; then
  echo
  echo "Admin token (shown once, store it somewhere safe; only its hash is kept):"
  echo "  $TOKEN"
elif [ "$db_exists" = true ] && [ "$was_active" = true ]; then
  echo "Upgraded; the existing database and admin token are unchanged."
else
  echo "No admin token was created by this run. A new database prints one to the journal on first start"
  echo "(journalctl -u $NAME | grep -A1 'New admin token'); for an existing database keep the token you have."
fi
