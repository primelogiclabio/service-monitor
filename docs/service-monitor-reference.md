# Service Monitor: Reference

Everything the [README](../README.md) leaves out: what is built and how far it has been tried, running and upgrading the hub, signing in, adding servers and checks, alerts, the API and every setting. The design is explained in [service-monitor-design.md](service-monitor-design.md), the technology in [service-monitor-stack-guide.md](service-monitor-stack-guide.md), and what the software does with data and the network in [service-monitor-trust-policy.md](service-monitor-trust-policy.md).

## Status (v0.1)

| Piece | State |
|---|---|
| Hub: scheduler, SQLite, REST API, tokens, HTTP/TCP/ping checks, roll-up status, alerts | Built and tested; logs a start-up summary with its address and what it watches |
| Agent (`svcmon agent run`): service, container, CPU, memory, disk, plus HTTP/TCP/ping from the server's side | Built; unit tests and a run against a real hub on Windows 11. The container check has been run by hand against Docker Desktop on Windows (the lister) and in tests against a stand-in for the runtime's API; not yet as an installed service, on Linux or against Podman |
| CLI (`svcmon status`, `check`, `token`, ...) | Built, same binary as the agent |
| Checks declared by the agent (`checks.json` beside `agent.json`) | Built, on by default. Hub and agent tests, and a run of `svcmon agent declare` and `run --once` against a throwaway hub and Docker Desktop on Windows; not yet on an installed service. The dashboard does not show where a check comes from yet |
| Agent service install (`svcmon agent install`: Windows service, systemd unit, macOS launchd plist) | Built. The Windows service install and the Linux systemd install (Ubuntu 26.04) have run for real; the macOS launchd one is only compiled |
| Hub service install (`hub/service/`: WinSW on Windows, systemd on Linux), with `--dry-run` | Built. CI dry-runs it on every pull request; a manually started workflow does the real install, upgrade and uninstall on Ubuntu and Windows runners; another builds a Windows package with the jar and WinSW so the install needs no download (not published as a release yet). No macOS installer |
| Alert channels | Webhook (optionally signed), Slack, Discord and email (HTML with a plain-text part, or plain text only), plus an alert when an agent goes silent; managed through the API and `svcmon alert` |
| Web UI | Built in its own repository, [service-monitor-ui](https://github.com/primelogiclabio/service-monitor-ui); the hub serves it from inside its jar when built with it, or from a folder (`svcmon.ui-dir`), or it is allowed from another address (`svcmon.cors-allowed-origins`) |
| Multi-hub console | Not started |

## Deployment model

TLS is terminated at your load balancer; only the hub is exposed. The hub speaks plain HTTP on port 8080. Agents, the CLI inside the network, and any future console talk to it over the internal network. Firewall the hub port to the load balancer and the internal ranges, have the load balancer overwrite any client-supplied `X-Forwarded-For`, and optionally block `/api/v1/agent/*` from outside. The hub trusts `X-Forwarded-*` from private-range proxies (`server.forward-headers-strategy: native`; set `server.tomcat.remoteip.internal-proxies` if yours is elsewhere), so rejected requests are logged with the real client address. Use `GET /api/v1/health` for load balancer health checks.

## Run the hub

The hub is one jar and needs Java 21 or newer. Run it and it starts: no installer, no other file.

```bash
java -jar svcmon-hub-<version>.jar init    # optional: writes application.yml with every setting at its default, commented out
java -jar svcmon-hub-<version>.jar         # starts the hub; it reads application.yml from this folder
```

`init` takes a folder (`init /etc/svcmon`), refuses to replace an existing file unless you add `--force`, and `--stdout` prints the template instead. Everything the hub can be set to is in that one file, including how the dashboard signs people in (no file inside the jar is ever edited). A wrong or unknown value stops the hub at start-up, before it touches the database, with a message that names it and where it came from (file and line, or the command line). Environment variables are not checked, because the CLI's `SVCMON_HUB` and `SVCMON_TOKEN` may be set on the same machine. Any setting can also be passed as `--svcmon.<name>=<value>`.

To build the jar from source: `cd hub; mvn package`, then `java -jar target/svcmon-hub-0.1.0-SNAPSHOT.jar`. That jar has no dashboard inside unless you add one (see "Host the web UI").

On first start the hub creates `./data/svcmon.db` and prints an admin token once; only its hash is stored. To get a new one later start with `--svcmon.new-admin-token=true`; to choose your own set `svcmon.bootstrap-admin-token` (32+ characters) on a fresh database.

When it is ready the hub logs one summary: the address it listens on, the database file, whether the dashboard and CORS are on, the enabled alert channels, and what it watches (checks by type, which the hub runs and which agents run, and each enrolled server with its checks). It names things but never prints check targets, alert settings or tokens.

```text
Service Monitor hub 0.1.0 is ready
  URL:       http://localhost:8080, http://hub01:8080 (plain HTTP; TLS belongs on the load balancer)
  Database:  /var/lib/svcmon-hub/svcmon.db
  Dashboard: served from inside the hub jar (svcmon.ui-dir would use a folder instead)
  CORS:      off
  Alerts:    1 enabled of 1: ops-slack (SLACK)
  Watching:  4 check(s) (HTTP 1, TCP 1, SERVICE 1, CPU 1): 2 run by the hub, 2 by agents on 1 enrolled server(s)
  Hub checks:
    HTTP  Website
    TCP  db-port
  Servers:
    web01 [prod]: 2 check(s): IIS (SERVICE), cpu (CPU)
```

### Run the hub as a service

To start at boot and restart after a crash, install it as a systemd service (Linux) or a Windows service through [WinSW](https://github.com/winsw/winsw) (the Windows package includes it, so that install needs no download), and remove it again with one command each. Add `--dry-run` (`-DryRun` on Windows) to see exactly what the install would do first, change the port with `--port` (`-Port`), and accept or cancel at the prompt. See [hub/service/README.md](../hub/service/README.md).

```bash
sudo hub/service/linux/install.sh        # prints the admin token once
```

```powershell
powershell -ExecutionPolicy Bypass -File hub\service\windows\install.ps1
```

## Host the web UI

The dashboard lives in its own repository, [service-monitor-ui](https://github.com/primelogiclabio/service-monitor-ui). Choose one of:

1. **Inside the hub jar.** A jar built with the dashboard serves it at the hub's address with no setting at all: open the address in a browser and sign in with a user name and password (see "Users and sign-in"). Nothing in it needs editing; what the page needs to know is set in `application.yml` (below). To build such a jar yourself: build the dashboard (`npm run build` in its repository), copy its `dist` folder to `hub/src/main/resources/ui`, then `cd hub; mvn package` (that folder is ignored by git). `svcmon.ui-enabled: false` makes the hub serve no dashboard, only the API.
2. **From a folder.** Start the hub with `--svcmon.ui-dir=/path/to/service-monitor-ui/dist` to serve that build instead of, or without, the one inside the jar. The folder then has its own `config.json`. The files are public; the API still needs a sign-in or a token.
3. **Host it elsewhere**, for example a static file server: the dashboard then has to reach the hub under its own address, so put both behind one site and send `/api/` to the hub (a reverse proxy rule). The sign-in cookie never works across sites, which is deliberate. `svcmon.cors-allowed-origins` (exact origins such as `https://dashboard.example.com`; `*`, paths and trailing slashes are refused and stop the hub from starting) only lets other sites' scripts call the API with a token.

**Sign-in through a reverse proxy.** Where nginx and a gateway sign people in and the hub accepts the token the proxy adds (see the Keycloak section below), the dashboard inside the jar is switched to that mode in `application.yml`; the hub builds the page's `config.json` from it:

```yaml
svcmon:
  ui:
    proxy:
      enabled: true
      api-base: /web-api            # the proxy's path to the hub's /api
      headers:
        X-Web-Client: "1"           # sent on every call; the proxy must require it before it turns the session into a token
      sign-in-url: /oauth2/start
      sign-out-url: /oauth2/sign_out
```

Only `enabled` is needed; the other values shown are the defaults. `api-base` must be a path on the same site, the sign-in and sign-out addresses a path or an http(s) address, and `headers` cannot set `Authorization`; anything else stops the hub at start-up. With `ui-dir` the folder's own `config.json` is used instead (see the dashboard's README).

CORS is off unless origins are listed. When on, only those origins may call `/api/` from a browser, with the `Authorization`, `Content-Type` and `Accept` headers, never with cookies; requests without an `Origin` header (the CLI, agents, scripts) are unaffected. Every response carries `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` and `Referrer-Policy: no-referrer`.

## Sign in with Keycloak or another OpenID Connect provider

Off by default. The hub can accept an OpenID Connect access token (a JWT) sent as `Authorization: Bearer <JWT>` and checks it itself: the signature against the keys the provider publishes (found through the issuer's discovery document), the issuer, the audience and the expiry. Nothing is trusted because of where a request came from, so it does not matter who can reach the hub's port. A token whose role claim holds the admin role gets the access of an ADMIN token (except the `/agent/` calls); any other valid token gets 403. The hub's own tokens (join keys, ADMIN and READ API tokens) work unchanged for agents, the CLI and setups without a provider; the two are told apart by shape, since a JWT is three dot-separated parts that start with `eyJ`.

```yaml
svcmon:
  jwt:
    issuer: https://auth.example.com/realms/main    # setting this turns it on
    audience: https://monitor.example.com/api       # the token's aud must contain this
    admin-role: platform-admin                      # the role that makes a user an administrator
    # roles-claim: realm_access.roles               # Keycloak's default location; dots walk into objects
    # jwks-uri: https://...                         # only if you do not want the discovery document's
```

At the provider, give the hub an API client whose audience is the `audience` value, and have the token carry the admin role (in Keycloak a realm role is in `realm_access.roles` by default). The issuer must be https; `http` is accepted only for `localhost` while developing. The hub refuses to start if the audience or admin role is missing. If the provider cannot be reached the first time the hub needs its keys, callers get 503 (not 401) and the hub tries again after a few seconds; once fetched, the keys are cached and keep working through a provider outage, and a key the hub has not seen triggers a refresh.

Typically a browser never holds the token: the dashboard calls a path on its own site, nginx asks a gateway or session service which checks the session and the role and exchanges it for a token with the hub's audience, and nginx forwards the call to the hub with that token:

```nginx
location /web-api/ {
    if ($http_x_web_client != "1") { return 403; }       # see the note on forged requests below
    auth_request     /oauth2/auth-api;                   # your gateway: session -> token for this hub
    auth_request_set $api_token $upstream_http_x_access_token;
    proxy_set_header Authorization "Bearer $api_token";
    # rewrite /web-api/ to /api/ and proxy_pass to the hub
}
location = /api/v1/live {                                # the live view: the same check on the upgrade request
    auth_request     /oauth2/auth-api;
    auth_request_set $api_token $upstream_http_x_access_token;
    proxy_set_header Authorization "Bearer $api_token";
    proxy_set_header Host              $host;
    proxy_set_header X-Forwarded-Host  $http_host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_pass http://hub:8080;
}
location /api/ { proxy_pass http://hub:8080; }           # agents, the CLI: their own tokens, no sign-in
```

How this is kept safe:
- The session cookie is sent by the browser on any request, including one a foreign page makes it send, so the proxy must only exchange a session for a token when the request carries a custom header that only your dashboard adds (the example's `X-Web-Client`); a page on another site cannot add it without a CORS preflight, which you never approve.
- A browser cannot add that header to a WebSocket, so for a JWT on the live view's upgrade request the hub checks that the page is on its own site, as the browser itself says: `Sec-Fetch-Site` when the browser sends it (Chrome does not on WebSocket requests), otherwise the `Origin` must be this hub's address as the proxy passed it (`X-Forwarded-Host` and `X-Forwarded-Proto`, or else `Host`) or be listed in `svcmon.cors-allowed-origins`. A request with neither header, such as a script, is not affected. Pass those headers in the live location, as in the example; a refused connection is logged with what was compared.
- Only asymmetric signatures are accepted, so an unsigned token or one signed with the public key as an HMAC secret is refused. Tokens and secrets are never logged or echoed; the user's name is logged when a valid user is refused.
- Agents never go through the sign-in, so nothing is needed from the provider for them and the provider being down does not affect them.
- A signed-in user cannot call the `/agent/` endpoints.

The live connection ends with close code 1001 when its JWT expires; the dashboard reconnects and the proxy adds a fresh token to the new request.

## Users and sign-in

People sign in to the dashboard with a user name and password. Each user is an **administrator** (`ADMIN`, may change anything) or **read-only** (`READ`, may look at everything but the user list, tokens and alert channels, and change only their own password). API tokens stay for the CLI and scripts; agents keep their own credentials. Turn user sign-in off with `svcmon.users.enabled: false` (then only the proxy sign-in and tokens work).

- **First start:** a hub without users creates the administrator `admin` (`svcmon.users.initial-admin-username`) with the password `admin` (`svcmon.users.initial-admin-password`). Both settings are used only to create that account. That password, and any temporary one an administrator gives out, works **only from the internal network** and must be changed at the first sign-in: until then the hub answers everything else with 403 `password_change_required`. The log says so at every start while an administrator still has one. A hub upgraded from the local admin account keeps that account's password, as user `admin` (or `svcmon.users.initial-admin-username`); the old `svcmon.local-admin.*` settings stop the hub with a message that names their replacements.
- **Passwords:** 12 to 128 characters, not the user name; no composition rules. Only a salted PBKDF2-HMAC-SHA256 hash (600,000 rounds) is stored; a password is never logged or returned.
- **Signing in:** `POST /api/v1/session` with `{"username", "password"}` (JSON only) sets a cookie and answers with who is signed in. Every failure gets the same 401 `wrong_credentials`, whether the name, the password or a disabled account was the problem, and takes the same time; a name nobody has is not even written to the log, as it may be a password typed into the wrong box.
- **The cookie:** `svcmon`, or `__Host-svcmon` with `Secure` over HTTPS (as the load balancer reports it in `X-Forwarded-Proto`); `HttpOnly`, `SameSite=Strict`, no `Domain`, and gone when the browser closes. The page never sees it. A request with the cookie that changes something must also send `X-Svcmon-Request: 1` and not come from another site by the browser's `Sec-Fetch-Site`, which together stop other sites' pages from using it.
- **How long:** a sign-in ends after `svcmon.sessions.max-hours` (12), or after `svcmon.sessions.idle-minutes` (60) without use. The dashboard's own refreshing (marked `X-Svcmon-Background: 1`) does not count, so a page left open is signed out too; raise `idle-minutes` for a screen that is meant to stay on the dashboard. Signing out, changing your password (your other sign-ins end and this one gets a new cookie), a password reset, a role change, disabling and deleting end sign-ins at once, the live view included.
- **Users:** administrators add users with a role (`POST /api/v1/users`), which answers with a temporary password shown once; change a role or turn an account off and on (`PATCH /api/v1/users/{id}`); give a new temporary password (`POST /api/v1/users/{id}/password-reset`); and delete (`DELETE /api/v1/users/{id}`). Names are 1 to 64 letters, digits and `. _ @ -`, unique whatever their case. Nobody can demote, disable or delete the last administrator (409 `last_admin`), or their own account (409 `own_account`; another administrator can).
- **Guessing:** each client address may get a password wrong 5 times in 15 minutes, each account 10 times, and all addresses together 20 times; the account and total counts are kept apart for the internal network and the outside, so guessing from outside cannot lock out the people inside. After that every attempt, even with the right password, is refused with 429 and a `Retry-After` until the oldest failure ages out. Behind a load balancer the client address is the one it forwards (see the note on `X-Forwarded-For` in the design document).
- **Lost every administrator password:** `java -jar svcmon-hub.jar users reset <name>`, run from the hub's folder (or with the hub's `--svcmon.data-dir`) as someone who may write its data folder, prints a temporary password once, turns the account back on, makes it an administrator if none is left and ends its sign-ins.

## Build the agent and CLI

Needs Go 1.27 or newer (https://go.dev/dl/). One command builds every platform, with no C toolchain:

```bash
cd agent
go build -trimpath -ldflags "-s -w" -o bin/svcmon ./cmd/svcmon
GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o bin/svcmon-linux-amd64 ./cmd/svcmon
```

Binaries are about 7 MB. To keep Go's caches off the system drive set `GOPATH` and `GOCACHE` to another location.

## Monitor a server

Nothing is monitored on a server until someone lists what to watch: an admin in the hub, or the person setting up the server in its `checks.json` (see "Checks declared by the agent").

```bash
# on your machine: log in once, then create a one-time enrollment token
svcmon login --hub http://hub:8080 --token svm_a_...
svcmon agent token --name web01

# on web01 (Administrator / root): enroll, install the service, start it
svcmon agent install --hub http://hub:8080 --token svm_e_... --cluster prod

# see what services exist on web01, then choose
svcmon agent services --filter iis
svcmon check add service W3SVC --server web01
svcmon agent containers --filter web     # Docker or Podman containers, running or not
svcmon check add container web --server web01
svcmon check add cpu --server web01 --warn 85 --crit 95
svcmon check add disk C: --server web01 --warn 80 --crit 90
svcmon check add http https://web01/health --server web01 --run-from agent
svcmon check add ping db01 --name "database host"      # runs on the hub

svcmon status                  # exit code 0 ok, 1 warning, 2 down, 3 unknown or error
svcmon status --cluster prod --watch
```

`svcmon agent run --once --config FILE` runs every assigned check once and prints the results, which is handy for testing. The agent keeps its config (`agent.json`) and, when it runs as a service, its log (`agent.log`) in the same folder as the `svcmon` program itself, so give the program a folder of its own before you install it (for example `F:\services\svcmon-agent` or `/opt/svcmon`); `--config FILE` puts the config somewhere else. The installed service runs the program where it sits, so do not move it afterwards. The config holds the agent's token and is readable only by SYSTEM, Administrators and the installing user (Windows) or the owner (Unix). It buffers up to 5000 results while the hub is unreachable and sends failures within about a second.

**Enrollment tokens are single use and short lived** (60 minutes by default, up to 24 hours: `svcmon agent token --ttl 1440`, or the "Valid for" choice on the dashboard's Add server page). A token lets a machine join the hub, so it should not sit around unused; it is shown once and only its hash is kept, so a lost or used one is replaced by creating another (revoke unused ones on the Tokens page). Once an agent has enrolled it holds its own permanent agent token in `agent.json`, and the enrollment token is no longer needed.

**Join keys register many servers with one command.** Instead of a token per server, create a reusable join key (`svcmon join-key create --name "app servers"`, or Add server in the dashboard) and run the same `svcmon agent install --hub http://hub:8080 --token svm_j_...` on every server; each one registers itself. A join key works until you revoke it (`svcmon join-key revoke <id>`), unless you give it `--expires-days`. With `--cluster prod` every server it registers lands in that cluster, and a server that asks for another cluster is refused. `svcmon join-key list` shows how many servers each key has registered and when it was last used, never the key itself. Anyone holding a join key can add servers, so keep it like a password and revoke it if it leaks; servers it already registered keep their own credentials and are not affected. A name that is already registered is still refused.

**An agent that is already enrolled** (its `agent.json` exists and the server is still on the hub) needs no token: `svcmon agent install` just installs the service from that file. To enroll it again, for example after the server was deleted on the hub or the hub was rebuilt, pass a new enrollment token: `svcmon agent install --token svm_e_...` replaces the old enrollment (`--hub` defaults to the hub in the old file, and the old file is kept if the hub refuses the token). A hub refuses a name that is already registered, so delete the old server entry first (or pass `--name`).

### Checks declared by the agent

Checks are normally made in the hub (the dashboard, `svcmon check add`, the API) and the agent runs what the hub assigns. A server can also declare its own: put a `checks.json` beside `agent.json` and the agent sends it to the hub when it starts and whenever the file has changed (it looks on each config poll, once a minute). The hub creates, updates and switches off checks to match, and the agent runs them from its ordinary list. This suits a file that ships with the server's own deployment, for example next to a compose project.

```json
{
  "checks": [
    { "type": "container", "target": "web", "intervalSeconds": 30, "failuresBeforeDown": 2 },
    { "type": "service", "target": "nginx.service", "name": "Nginx" },
    { "type": "disk", "target": "/", "config": { "warn": 80, "crit": 90 } }
  ]
}
```

- **On by default:** whoever sets up a server knows what it should run, so they list it there instead of installing the agent and then going to the hub. An admin who wants only the hub to list checks sets `svcmon.agent-declared-checks: false`; the hub then refuses a declaration and the agent logs that once. An agent can declare checks only for its own server and only ones that run on the agent (a declared `http` check runs from the agent, never from the hub), up to `svcmon.agent-declared-checks-limit` (200).
- **Settings:** `type` (required), `target`, `name` (made up from the type and target when missing), `config`, `intervalSeconds`, `timeoutMs`, `failuresBeforeDown` and `id`. Unknown settings are errors. A check is told apart by its type and target, or by its `id` when you give one, which you need for two checks of the same type and target. Editing the interval or thresholds keeps the check and its history; a different target is a new check.
- **The agent wins.** A declared check replaces the hub's settings for that check on every sync, and a check made in the hub with the same type and target is taken over, with its history. A check shows where it comes from (`source` is `HUB` or `AGENT` in the API). The hub refuses to edit or delete a declared check, since the next sync would undo it, except its on/off switch, so a noisy one can be muted without touching the server.
- **Taking a check out of the file** switches it off and marks it `removedFromFile`; it is not deleted, so its history stays, and it can be deleted in the hub. It comes back on when the file lists it again. A missing file declares nothing, so deleting the file switches nothing off; an empty list (`{"checks":[]}`) does.
- **Mistakes:** the hub refuses a bad entry and says why, and the others still apply. A broken file is logged by the agent and the hub keeps the last list it was given.
- **Manage it from the server:** `svcmon agent check add <type> [target] [flags]`, `svcmon agent check rm <name|id|type [target]>` and `svcmon agent check list [--json]` edit the file and send it to the hub at once, with the agent's own token, so no CLI or dashboard login is needed. `add` takes the flags of `svcmon check add` (`--name --interval --timeout --failures --warn --crit --keyword --expect-status --ignore-tls --no-redirects --cert-warn-days --socket --set k=v`) plus `--id`, `--config FILE` and `--no-declare` (only change the file). The same type and target twice is refused unless you give `--id`; to change a check, `rm` and `add` it again (it keeps its history). The file is saved even if the hub cannot be reached or has the switch off, and the command then exits with a warning; the running agent sends it later.
- **Start from a template:** `svcmon agent check init` writes `checks.example.json` beside `agent.json` with one example of every kind of check (service, container, CPU, memory, disk, HTTP, TCP, ping). Edit it down to what the server needs and save it as `checks.json`, or skip the file and add checks one at a time with `svcmon agent check add`. The agent never reads the example, so writing it declares nothing. It refuses to replace an existing example unless you add `--force`; `--stdout` prints it, for example `svcmon agent check init --stdout > checks.json`.
- **If the hub is down:** the agent keeps the check list the hub last gave it in `assigned.json` beside `agent.json` (private to the owner, safe to delete). An agent started while the hub does not answer runs that list at once, buffers results and sends them when the hub is back; the next config poll swaps in the hub's list. A hub that answers with a refusal (token revoked, server deleted) is not worked around. Alerts are still sent by the hub, so a hub that is down alerts nobody. A check the hub never saw has no id and is not in the cache.
- `svcmon agent declare [--config FILE]` sends the file now and shows what the hub did with each entry; `--dry-run` only checks the file. `svcmon agent run --once` declares first. A UTF-8 byte order mark from Notepad or PowerShell is fine.

### More commands

```bash
svcmon hub                       # hub identity, version and Java runtime age
svcmon server web01              # one server with its checks
svcmon check list                # also: show, run, rm <check>
svcmon check history <check> --since 2h
svcmon token create --name ci --scope read
svcmon token list                # also: token revoke <id>
svcmon join-key create --name "app servers" --cluster prod   # reusable; also: join-key list, join-key revoke <id>
svcmon agent enroll --hub http://hub:8080 --token svm_e_...   # enroll without installing the service
svcmon agent check add cpu --warn 80 --crit 90   # on the server: edit checks.json and send it; also: check rm, check list, check init
svcmon agent declare             # send checks.json to the hub now; --dry-run only checks the file
svcmon agent uninstall           # stop and remove the OS service
```

`svcmon help` lists every command and flag.

## Upgrading

An upgrade keeps everything that identifies the installation: the hub's database, settings and tokens, and each agent's `agent.json` (its token), `checks.json` and `assigned.json`. Nothing is enrolled again. Do the hub first (it carries the dashboard), then the agents, and check each with `svcmon hub` or `svcmon version` afterwards. The release page has the same steps for the downloaded files.

**From a hub with the local admin account (0.1).** People now sign in as users. The local admin becomes the administrator `admin` with its password, and its old sign-ins end. Settings under `svcmon.local-admin` stop the hub with a message: remove them, or move `username` to `svcmon.users.initial-admin-username` before the upgrade (it names the account the old one turns into) and `session-hours` to `svcmon.sessions.max-hours`. API tokens and agents are unchanged.

**The hub.** Build or unpack the new jar and run the installer again, with `--dry-run` / `-DryRun` first: the plan says "upgrade" and lists what is kept (see [hub/service](../hub/service/README.md); on Windows run `install.ps1` from the new [package](../hub/service/README.md#windows-package-no-download) or pass `-JarPath`). It stops the service, replaces the jar and starts it again; the database is migrated when the hub starts. Copy the data folder first if you want a way back. Without the installer, stop the hub and start it with the new jar. One change to know about: a hub built after the agent-declared checks work accepts them by default, so set `svcmon.agent-declared-checks: false` if only the admin should list checks.

**The dashboard.** It is inside the hub jar, so upgrading the hub upgrades it. If your `application.yml` still has a `ui-dir:` line, the hub serves that older folder instead: remove the line and restart the service. A dashboard hosted elsewhere, or served from a folder on purpose, is upgraded by replacing `index.html` and the `assets/` folder in the folder the hub serves (`svcmon.ui-dir`) with the new build's, keeping your `config.json`. No restart is needed: `index.html` is never cached and the other files carry a hash in their names, so a normal reload shows the new one.

**The agent.** The service runs the program file where `svcmon agent install` put it, so an upgrade replaces that file while the service is stopped. Do not run `agent install` again: on Windows it says the service is already installed. A stop of a few seconds is far below the 90 seconds (`svcmon.agent-stale-seconds`) before the hub shows a server offline.

```powershell
# Windows, elevated PowerShell (run on the Windows machine used for the first install)
Stop-Service svcmon-agent
Copy-Item .\svcmon-agent-<version>-windows-amd64.exe C:\path\to\the\installed\svcmon.exe -Force
Start-Service svcmon-agent
```

```bash
# Linux (not tried on a real system), as root: same idea with systemd
systemctl stop svcmon-agent
cp svcmon-agent-<version>-linux-amd64 /path/to/the/installed/svcmon
systemctl start svcmon-agent

# macOS (not tried on a real system), as root
launchctl unload /Library/LaunchDaemons/io.primelogiclab.svcmon.agent.plist
cp svcmon-agent-<version>-darwin-arm64 /path/to/the/installed/svcmon
launchctl load -w /Library/LaunchDaemons/io.primelogiclab.svcmon.agent.plist
```

The service is shown as "Service Monitor agent" (the hub's is "Service Monitor hub"). An agent installed by an earlier build is listed as "svcmon monitoring agent" and keeps that name through an upgrade; on Windows rename it from an elevated prompt with `sc.exe config svcmon-agent DisplayName= "Service Monitor agent"`.

The Windows steps are the ones that have been run for real (hub and agent upgraded in place on the development machine); the Linux and macOS commands follow from how the installer registers the service.

## Alerts

An alert channel is a place the hub sends a message when something changes status. There are four types: **webhook** (JSON, optionally signed), **Slack**, **Discord** and **email** (SMTP). Channels are managed through the API (admin only) or the CLI. Each says which new statuses it wants; the default is DOWN, WARN and UP, so you also hear about recoveries.

```bash
svcmon alert add slack --name ops --url https://hooks.slack.com/services/T000/B000/XXXX
svcmon alert add discord --name chat --url https://discord.com/api/webhooks/1/xxxx --on down,up
svcmon alert add webhook --name pager --url https://alerts.internal/hook --header "Authorization: Bearer abc" --signing-secret s3cret
svcmon alert add email --name mail --smtp-host smtp.example.com --smtp-user svcmon --from svcmon@example.com --to ops@example.com --to oncall@example.com
svcmon alert add email --name sms --smtp-host smtp.example.com --from svcmon@example.com --to 5551234@sms.example.com --format text   # plain text only
svcmon alert test ops            # sends one test message and says whether it arrived
svcmon alert list                # state, last success and last error for every channel
svcmon alert set ops --on down   # changes only what you give; stored secrets are kept
svcmon alert disable ops         # also: enable, rm, show
```

What triggers a message:
- A check changing status, after its "failures before down" count is reached.
- An agent going silent for longer than `svcmon.agent-stale-seconds`, or reporting again. When an agent dies its checks stop reporting and never change status, so without this the most common failure would raise no alert.

A failing delivery is retried (after 0, 2 and 5 seconds by default) when the problem looks temporary, and then the error is kept and shown by `svcmon alert list`. Deliveries run in the background, so a slow receiver never delays checks or other channels.

Safety:
- Webhook URLs, header values, signing secrets and the SMTP password are secrets. The API and CLI show them masked, error messages never repeat a URL, and an update that sends a masked value back keeps the stored one. They are stored in the hub's database, so protect the data folder. On the command line prefer the environment variables `SVCMON_ALERT_URL`, `SVCMON_SMTP_PASSWORD` and `SVCMON_ALERT_SIGNING_SECRET`, which stay out of shell history and process lists.
- Slack and Discord URLs must be https. Email defaults to STARTTLS and refuses to fall back to clear text; `--security none` is for a trusted internal relay.
- An email carries a plain-text part and an HTML part, so every client shows something readable: a coloured status band with the status written out, what happened, the check's message, a details table and, when `svcmon.public-url` is set, an "Open in dashboard" button to the server's page. The HTML has no images, scripts, web fonts or tracking; every name, target and message an agent supplies is escaped, and text-direction tricks are removed. A channel's `format` (`--format`) is `html` by default; `text` sends the plain text alone, for pagers and email-to-SMS gateways. Channels made before this setting existed get HTML.
- With a signing secret, webhook bodies are signed: the hub sends `X-Svcmon-Signature: sha256=<HMAC-SHA256 of the body>`. The JSON has `event`, `kind` (`check`, `server` or `test`), `hub`, `time`, `subject`, `from`, `to` and `message`.
- Names and messages can come from agents, so Slack markup is escaped, Discord mentions are switched off, and mail headers cannot be injected.

## API (all under `/api/v1`, JSON, `Authorization: Bearer <token>` or a signed-in user's cookie)

A user's role counts as the scope of the same name. Errors are `{"error": "<code>", "message": "..."}`.

| Method and path | Scope | Purpose |
|---|---|---|
| `GET /health` | none | Liveness probe |
| `GET /hub` | read | Hub ID, version, Java runtime age and warning |
| `GET /session`, `POST /session`, `DELETE /session` | none | Who is signed in; sign in with a user name and password (sets the cookie; wrong attempts are throttled); sign out |
| `POST /session/live-ticket` | a user | A one-time ticket for the live view, valid 30 seconds |
| `PUT /me/password` | a user | Change your own password: `{"currentPassword", "newPassword"}` |
| `GET /sessions`, `DELETE /sessions/{id}` | a user | Your sign-ins (an administrator sees and ends everyone's) |
| `GET/POST /users`, `PATCH/DELETE /users/{id}`, `POST /users/{id}/password-reset` | admin | Users; new and reset passwords appear once in the answer |
| `GET /me` | read | Who is calling: `method` (`session`, `token` or `jwt`), `name` and `scope`; lets a dashboard that holds no token show who is signed in |
| `GET /status` | read | Roll-up: summary, clusters, servers, checks |
| `GET /live` (WebSocket) | read, sent in the first frame | Pushes the same roll-up whenever it changes, for the dashboard's Live switch; see below |
| `GET /servers/{id}/status` | read | One server with its checks |
| `GET/POST /checks`, `GET/PUT/DELETE /checks/{id}` | read / admin | Manage checks |
| `POST /checks/{id}/run` | admin | Run a hub-side check now |
| `GET /checks/{id}/results?since=&limit=` | read | History, newest first |
| `GET /servers`, `GET /servers/{id}`, `PATCH /servers/{id}`, `DELETE /servers/{id}` | read / admin | Servers and clusters |
| `GET/POST /tokens`, `DELETE /tokens/{id}` | admin | API tokens (ADMIN or READ) |
| `POST /enrollment-tokens` | admin | One-time token to register an agent |
| `GET/POST /join-keys`, `DELETE /join-keys/{id}` | admin | Reusable join keys: register any number of agents until revoked or expired; optional cluster |
| `GET/POST /alert-channels`, `GET/PUT/DELETE /alert-channels/{id}` | admin | Alert channels; secrets are always masked |
| `POST /alert-channels/{id}/test` | admin | Send one test alert now |
| `POST /agent/register`, `GET /agent/config`, `POST /agent/report`, `PUT /agent/checks` | enroll or join key / agent | Agent protocol (agents only call out). `PUT /agent/checks` declares the agent's own checks and is refused when `svcmon.agent-declared-checks` is set to false |

### Live view (WebSocket)

Polling `GET /status` is how every client works by default. For a dashboard someone is watching, `GET /api/v1/live` upgrades to a WebSocket that pushes the roll-up as it changes, so there is no refresh delay and no repeated requests. The web UI has a **Live** switch for it and turns it off again after 5 minutes without activity.

A browser cannot set an `Authorization` header on a WebSocket, and a token in the address would end up in access logs, so the first frame carries the credential, within 5 seconds: a signed-in user's page sends `{"type":"auth","ticket":"..."}` with a ticket from `POST /session/live-ticket` (one use, 30 seconds; the connection closes when that sign-in ends), and a client with a READ or ADMIN token sends `{"type":"auth","token":"..."}`. The hub then sends `{"type":"status","data":{...}}` (the body of `GET /status`) at once and again after each change, checked once a second; a client that sends `{"type":"ping"}` gets `{"type":"pong"}` (the dashboard does so every 25 seconds, which keeps the link open through load balancers that drop quiet connections). A wrong or unsuitable token, an invalid frame, a revoked or expired token and 90 seconds of silence close the connection; the token is never echoed or logged. The socket never reads cookies, so any origin may open it and the CORS list does not apply to it. With JWT sign-in on, a JWT works in place of a hub token: in the first frame, or as `Authorization: Bearer` on the upgrade request when a proxy adds it (then no first frame is needed, the page must be on the hub's own address, and the connection closes with code 1001 when the token expires). `svcmon.live-max-sessions` caps how many may be open (more are refused with close code 1013).

Behind a load balancer it must pass WebSocket upgrades (`Upgrade` and `Connection` headers) to the hub and allow idle connections for at least 30 seconds. If it does not, the Live switch tells the user it is unavailable and the dashboard keeps polling.

Check types: `HTTP`, `TCP`, `PING` run on the hub or on an agent; `SERVICE`, `CONTAINER`, `CPU`, `MEMORY`, `DISK` run on an agent and need a server.

**Container checks** (`CONTAINER`, target = the container's name) ask Docker or Podman about one container through its local socket. The agent does this on the server, so the hub still opens no connection to it, and it only sends read requests (list and inspect): it cannot start, stop or change a container. A running container is UP, and when it has a health check it must report healthy; a health check that is still starting or a container in a restart loop is WARN; unhealthy, paused, exited, dead, created or missing is DOWN. A runtime that cannot be reached (no socket, no permission) is UNKNOWN, not DOWN. The message shows the image, uptime and restart count, never the container's environment. The agent finds the socket itself (`DOCKER_HOST` when it is a `unix://` address, `/var/run/docker.sock`, Podman's system and per-user sockets, Docker Desktop's on macOS, and on Windows the named pipes of Docker Desktop and the Podman machine); `--socket PATH` on `check add` (config key `socket`, the only key the hub accepts for this type) names one, and a Windows pipe is written `\\.\pipe\docker_engine` (or `npipe:////./pipe/docker_engine`). Reading the socket needs root or membership of the runtime's group, which an agent running as root has, and the socket is as powerful as root, which is why the agent never writes to it. Nothing is discovered automatically: `svcmon agent containers` only lists. If the account the agent runs as is refused by a Windows pipe's access rules, the check is UNKNOWN with a note saying so.

## Configuration (`hub/src/main/resources/application.yml`, or `--svcmon.*` flags)

| Key | Default | Meaning |
|---|---|---|
| `server.port` | 8080 | HTTP port |
| `svcmon.data-dir` | `./data` | Where the SQLite database lives |
| `svcmon.retention-days` | 30 | Raw results older than this are deleted |
| `svcmon.agent-heartbeat-seconds` | 30 | How often agents are told to report |
| `svcmon.agent-stale-seconds` | 90 | Silence after which a server shows as offline |
| `svcmon.ui-enabled` | true | Serve the dashboard (from `ui-dir`, else from inside the jar if it has one). false makes the hub headless |
| `svcmon.ui-dir` | unset | Folder with the built web UI, served at `/` instead of the one inside the jar. A missing folder only logs a warning and serves none |
| `svcmon.ui.proxy.*` | off | Sign-in through a reverse proxy for the dashboard inside the jar: `enabled`, `api-base`, `headers`, `sign-in-url`, `sign-out-url` (see "Host the web UI") |
| `svcmon.embedded-ui` | `classpath:/ui/` | Where inside the jar the dashboard sits. Only tests change it |
| `svcmon.cors-allowed-origins` | empty | Exact origins allowed to call the API from a browser; empty turns CORS off |
| `svcmon.live-max-sessions` | 50 | Most dashboards that may hold the live WebSocket open at once; more are refused (close code 1013) and keep polling |
| `svcmon.public-url` | unset | The address people open the dashboard at (`https://monitor.example.com`, a path is fine, no query or fragment). Alert emails then get an "Open in dashboard" button; unset means no button. A wrong value stops the start-up |
| `svcmon.agent-declared-checks` | true | Lets agents declare their own checks from a `checks.json` beside their config (see "Checks declared by the agent"). Set to false and the hub refuses declarations, so only the admin lists what is monitored |
| `svcmon.agent-declared-checks-limit` | 200 | Most checks one server may declare (1 to 1000); more is refused. A value outside the range stops the start-up |
| `svcmon.jwt.issuer` | unset | Issuer address of an OpenID Connect provider (https; http only for localhost). Setting it turns on sign-in with JWT access tokens; see above |
| `svcmon.jwt.audience` | unset | The audience (`aud`) a token must contain; required with the issuer |
| `svcmon.jwt.admin-role` | unset | The role that makes a user an administrator of the hub, who is the only kind let in; required with the issuer |
| `svcmon.jwt.roles-claim` | `realm_access.roles` | Where the roles are in the token; dots walk into nested objects |
| `svcmon.jwt.jwks-uri` | from discovery | Where the provider's signing keys are, if not the address in its discovery document |
| `svcmon.users.enabled` | true | Sign-in with a user name and password; see "Users and sign-in" |
| `svcmon.users.initial-admin-username` | `admin` | Name of the first administrator, created on a hub without users |
| `svcmon.users.initial-admin-password` | `admin` | Its password, which works only from the internal network and must be changed at the first sign-in |
| `svcmon.sessions.idle-minutes` | 60 | A sign-in not used this long ends, 5 to 1440; the dashboard's own refreshing does not count |
| `svcmon.sessions.max-hours` | 12 | A sign-in ends this long after it began, 1 to 168 |
| `svcmon.alert-retry-delays-ms` | `0,2000,5000` | Waits between delivery attempts for a failing alert channel, in milliseconds |
| `svcmon.allow-insecure-alert-urls` | false | Lets Slack and Discord URLs use http; for tests and local receivers only |
| `svcmon.bootstrap-admin-token` | unset | Use this value (32+ characters) as the first admin token on a fresh database instead of generating one; ignored once an admin token exists |
| `svcmon.new-admin-token` | false | Print an additional new admin token at startup (token recovery) |
