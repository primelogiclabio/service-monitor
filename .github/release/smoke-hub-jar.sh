#!/usr/bin/env bash
# Starts a release hub jar on a throwaway port and data folder and checks the things a release must get right:
# it starts, it reports the version it was built as, it serves the dashboard from inside itself, the dashboard's
# config.json comes from the settings, and "init" writes a settings file and then refuses to replace it.
# Nothing outside a temporary folder is touched. The release workflow runs it on every release jar; it also runs by
# hand:  .github/release/smoke-hub-jar.sh target/svcmon-hub-0.1.0.jar 0.1.0 [port]
set -euo pipefail

jar=${1:?usage: smoke-hub-jar.sh JAR VERSION [PORT]}
version=${2:?usage: smoke-hub-jar.sh JAR VERSION [PORT]}
port=${3:-18099}
token="smoke-admin-token-0123456789abcdef0123456789abcdef"
base="http://127.0.0.1:$port"

work=$(mktemp -d)
pid=""
cleanup() {
  if [ -n "$pid" ]; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
  rm -rf "$work" 2>/dev/null || true
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; [ ! -f "$work/hub.log" ] || tail -n 30 "$work/hub.log" >&2; exit 1; }
ok() { echo "ok: $*"; }

echo "== init"
java -jar "$jar" init "$work/conf" >"$work/init.out" 2>&1 || fail "init failed: $(cat "$work/init.out")"
[ -s "$work/conf/application.yml" ] || fail "init wrote no application.yml"
ok "init wrote application.yml"
if java -jar "$jar" init "$work/conf" >"$work/init2.out" 2>&1; then fail "a second init replaced the file"; fi
grep -q "already exists" "$work/init2.out" || fail "a second init did not say why it refused: $(cat "$work/init2.out")"
ok "a second init refuses to replace it"

echo "== start"
(cd "$work/conf" && exec java -jar "$jar" --server.port="$port" --svcmon.data-dir="$work/data" \
  --svcmon.bootstrap-admin-token="$token") >"$work/hub.log" 2>&1 &
pid=$!
up=false
for _ in $(seq 1 90); do
  if curl -fsS "$base/api/v1/health" >/dev/null 2>&1; then up=true; break; fi
  kill -0 "$pid" 2>/dev/null || fail "the hub exited while starting"
  sleep 1
done
[ "$up" = true ] || fail "the hub did not answer within 90 seconds"
ok "the hub answers on port $port"

echo "== version, dashboard and settings"
hub=$(curl -fsS -H "Authorization: Bearer $token" "$base/api/v1/hub")
case "$hub" in *"\"version\":\"$version\""*) ok "/api/v1/hub reports version $version" ;; *) fail "wrong version in: $hub" ;; esac
page=$(curl -fsS "$base/")
case "$page" in *'<div id="app">'*) ok "/ serves the dashboard from inside the jar" ;; *) fail "/ is not the dashboard: $page" ;; esac
asset=$(printf '%s' "$page" | grep -o 'assets/[^"]*\.js' | head -n 1)
[ -n "$asset" ] || fail "the page names no script"
curl -fsS "$base/$asset" >/dev/null || fail "the script $asset is not served"
ok "$asset is served"
cfg=$(curl -fsS "$base/config.json")
[ "$cfg" = '{"proxy":false}' ] || fail "config.json is $cfg"
ok "config.json is built from the settings: $cfg"
grep -q "inside the hub jar" "$work/hub.log" || fail "the start-up summary does not say the dashboard is inside the jar"
ok "the start-up summary says where the dashboard comes from"

echo "All checks passed."
