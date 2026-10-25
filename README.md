# Service Monitor

A free, cross-platform monitor for HTTP, TCP, ping, Windows/Linux/macOS services, CPU, memory and disk. A headless Java hub with a REST API, one small Go binary (`svcmon`) that is both the agent and the command-line client, an optional web dashboard in its own repository, and a multi-hub console that is still to come.

Design and rationale: [docs/service-monitor-design.md](docs/service-monitor-design.md) (also as [HTML](docs/service-monitor-design.html)).

## Status (v0.1)

| Piece | State |
|---|---|
| Hub: scheduler, SQLite, REST API, tokens, HTTP/TCP/ping checks, roll-up status, alerts | Built and tested; logs a start-up summary with its address and what it watches |
| Agent (`svcmon agent run`): service, CPU, memory, disk, plus HTTP/TCP/ping from the server's side | Built; unit tests and a run against a real hub on Windows 11 |
| CLI (`svcmon status`, `check`, `token`, ...) | Built, same binary as the agent |
| Agent service install (`svcmon agent install`: Windows service, systemd unit, macOS launchd plist) | Written and cross-compiled; only the Windows not-elevated error path was run |
| Hub service install (`hub/service/`: WinSW on Windows, systemd on Linux), with `--dry-run` | Built. CI dry-runs it on every pull request; a manually started workflow does the real install, upgrade and uninstall on Ubuntu and Windows runners; another builds a Windows package with the jar and WinSW so the install needs no download (not published as a release yet). No macOS installer |
| Alert channels | Webhook (optionally signed), Slack, Discord and email, plus an alert when an agent goes silent; managed through the API and `svcmon alert` |
| Web UI | Built in its own repository, [service-monitor-ui](https://github.com/primelogiclabio/service-monitor-ui); the hub can serve it (`svcmon.ui-dir`) or allow it from another address (`svcmon.cors-allowed-origins`) |
| Multi-hub console | Not started |

## Deployment model

TLS is terminated at your load balancer; only the hub is exposed. The hub speaks plain HTTP on port 8080. Agents, the CLI inside the network, and any future console talk to it over the internal network. Firewall the hub port to the load balancer and the internal ranges, have the load balancer overwrite any client-supplied `X-Forwarded-For`, and optionally block `/api/v1/agent/*` from outside. The hub trusts `X-Forwarded-*` from private-range proxies (`server.forward-headers-strategy: native`; set `server.tomcat.remoteip.internal-proxies` if yours is elsewhere), so rejected requests are logged with the real client address. Use `GET /api/v1/health` for load balancer health checks.

## Run the hub

Needs Java 21 or newer and Maven to build.

```bash
cd hub
mvn package
java -jar target/svcmon-hub-0.1.0-SNAPSHOT.jar
```

On first start the hub creates `./data/svcmon.db` and prints an admin token once; only its hash is stored. To get a new one later start with `--svcmon.new-admin-token=true`; to choose your own set `svcmon.bootstrap-admin-token` (32+ characters) on a fresh database.

When it is ready the hub logs one summary: the address it listens on, the database file, whether the dashboard and CORS are on, the enabled alert channels, and what it watches (checks by type, which the hub runs and which agents run, and each enrolled server with its checks). It names things but never prints check targets, alert settings or tokens.

```text
Service Monitor hub 0.1.0 is ready
  URL:       http://localhost:8080, http://hub01:8080 (plain HTTP; TLS belongs on the load balancer)
  Database:  /var/lib/svcmon-hub/svcmon.db
  Dashboard: not served (set svcmon.ui-dir to serve the web UI)
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

To start at boot and restart after a crash, install it as a systemd service (Linux) or a Windows service through [WinSW](https://github.com/winsw/winsw) (the Windows package includes it, so that install needs no download), and remove it again with one command each. Add `--dry-run` (`-DryRun` on Windows) to see exactly what the install would do first, change the port with `--port` (`-Port`), and accept or cancel at the prompt. See [hub/service/README.md](hub/service/README.md).

```bash
sudo hub/service/linux/install.sh        # prints the admin token once
```

```powershell
powershell -ExecutionPolicy Bypass -File hub\service\windows\install.ps1
```

## Host the web UI

The dashboard lives in its own repository, [service-monitor-ui](https://github.com/primelogiclabio/service-monitor-ui). Build it there (`npm run build`), then choose one of:

1. **Let the hub serve it**, so everything is at one address: start the hub with `--svcmon.ui-dir=/path/to/service-monitor-ui/dist`, open the hub's address in a browser and sign in with an API token. The files are public; the API still needs a token.
2. **Host it elsewhere**, for example a static file server: list its address in `svcmon.cors-allowed-origins` (exact origins such as `https://dashboard.example.com`; `*`, paths and trailing slashes are refused and stop the hub from starting), then enter the hub's address under "Hub address" when signing in. If you also open the dashboard through the hub's own address behind a load balancer, list that address too.

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

## Local admin account

The hub keeps one administrator of its own, for when nothing else works: the provider behind the proxy is down, or none is set up. It is on unless `svcmon.local-admin.enabled` is `false`, and its user name is `admin` unless `svcmon.local-admin.username` says otherwise.

- **First start:** the password is `svcmon.local-admin.password` (12 to 128 characters) if you set it, otherwise a random one that is printed once next to the first admin token. Only a salted PBKDF2-HMAC-SHA256 hash (600,000 rounds) is stored, in the hub's database; it is never logged or returned. The setting is used only to create the account.
- **Signing in:** `POST /api/v1/login` with `{"username": "...", "password": "..."}` answers with `{"token", "expiresAt", "scope": "ADMIN", "name": "local-login"}`. The token is an ordinary ADMIN token that lasts `svcmon.local-admin.session-hours` (12 by default, at most 168), so the dashboard, the live view and revoking work as for any token; it appears in the token list as `local-login`. The dashboard's sign-in page has a user name and password form for it.
- **Changing it:** `PUT /api/v1/local-admin/password` with `{"newPassword": "..."}` and an ADMIN token (or sign-in). Every `local-login` token is ended, so a stolen session does not survive it.
- **Recovery:** start the hub once with `--svcmon.local-admin.new-password=true` to print a new password (this also ends the sign-ins made with the old one), then start it normally again.
- **Guessing:** each client address may get the password wrong 5 times in 15 minutes, and all addresses together 20 times. After that every attempt, even with the right password, is refused with 429 and a `Retry-After` until the oldest failure ages out. A wrong user name and a wrong password get the same answer and take the same time. Behind a load balancer the client address is the one it forwards (see the note on `X-Forwarded-For` in the design document).
- **Where to reach it:** treat the password as the way in on the hub's internal address, next to the API tokens, and keep it off the public site. With nginx in front, `location = /api/v1/login { return 404; }` does that, so only the proxy's sign-in is public.

## Build the agent and CLI

Needs Go 1.27 or newer (https://go.dev/dl/). One command builds every platform, with no C toolchain:

```bash
cd agent
go build -trimpath -ldflags "-s -w" -o bin/svcmon ./cmd/svcmon
GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o bin/svcmon-linux-amd64 ./cmd/svcmon
```

Binaries are about 7 MB. To keep Go's caches off the system drive set `GOPATH` and `GOCACHE` to another location.

## Monitor a server

Nothing is monitored on a server until an admin lists what to watch.

```bash
# on your machine: log in once, then create a one-time enrollment token
svcmon login --hub http://hub:8080 --token svm_a_...
svcmon agent token --name web01

# on web01 (Administrator / root): enroll, install the service, start it
svcmon agent install --hub http://hub:8080 --token svm_e_... --cluster prod

# see what services exist on web01, then choose
svcmon agent services --filter iis
svcmon check add service W3SVC --server web01
svcmon check add cpu --server web01 --warn 85 --crit 95
svcmon check add disk C: --server web01 --warn 80 --crit 90
svcmon check add http https://web01/health --server web01 --run-from agent
svcmon check add ping db01 --name "database host"      # runs on the hub

svcmon status                  # exit code 0 ok, 1 warning, 2 down, 3 unknown or error
svcmon status --cluster prod --watch
```

`svcmon agent run --once --config FILE` runs every assigned check once and prints the results, which is handy for testing. The agent keeps its config in `%ProgramData%\svcmon\agent.json` (Windows) or `/etc/svcmon/agent.json`, readable only by SYSTEM, Administrators and the installing user (Windows) or the owner (Unix). It buffers up to 5000 results while the hub is unreachable and sends failures within about a second.

**Enrollment tokens are single use and short lived** (60 minutes by default, up to 24 hours: `svcmon agent token --ttl 1440`, or the "Valid for" choice on the dashboard's Add server page). A token lets a machine join the hub, so it should not sit around unused; it is shown once and only its hash is kept, so a lost or used one is replaced by creating another (revoke unused ones on the Tokens page). Once an agent has enrolled it holds its own permanent agent token in `agent.json`, and the enrollment token is no longer needed.

**Join keys register many servers with one command.** Instead of a token per server, create a reusable join key (`svcmon join-key create --name "app servers"`, or Add server in the dashboard) and run the same `svcmon agent install --hub http://hub:8080 --token svm_j_...` on every server; each one registers itself. A join key works until you revoke it (`svcmon join-key revoke <id>`), unless you give it `--expires-days`. With `--cluster prod` every server it registers lands in that cluster, and a server that asks for another cluster is refused. `svcmon join-key list` shows how many servers each key has registered and when it was last used, never the key itself. Anyone holding a join key can add servers, so keep it like a password and revoke it if it leaks; servers it already registered keep their own credentials and are not affected. A name that is already registered is still refused.

**An agent that is already enrolled** (its `agent.json` exists and the server is still on the hub) needs no token: `svcmon agent install` just installs the service from that file. To enroll it again, for example after the server was deleted on the hub or the hub was rebuilt, pass a new enrollment token: `svcmon agent install --token svm_e_...` replaces the old enrollment (`--hub` defaults to the hub in the old file, and the old file is kept if the hub refuses the token). A hub refuses a name that is already registered, so delete the old server entry first (or pass `--name`).

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
svcmon agent uninstall           # stop and remove the OS service
```

`svcmon help` lists every command and flag.

## Alerts

An alert channel is a place the hub sends a message when something changes status. There are four types: **webhook** (JSON, optionally signed), **Slack**, **Discord** and **email** (SMTP). Channels are managed through the API (admin only) or the CLI. Each says which new statuses it wants; the default is DOWN, WARN and UP, so you also hear about recoveries.

```bash
svcmon alert add slack --name ops --url https://hooks.slack.com/services/T000/B000/XXXX
svcmon alert add discord --name chat --url https://discord.com/api/webhooks/1/xxxx --on down,up
svcmon alert add webhook --name pager --url https://alerts.internal/hook --header "Authorization: Bearer abc" --signing-secret s3cret
svcmon alert add email --name mail --smtp-host smtp.example.com --smtp-user svcmon --from svcmon@example.com --to ops@example.com --to oncall@example.com
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
- With a signing secret, webhook bodies are signed: the hub sends `X-Svcmon-Signature: sha256=<HMAC-SHA256 of the body>`. The JSON has `event`, `kind` (`check`, `server` or `test`), `hub`, `time`, `subject`, `from`, `to` and `message`.
- Names and messages can come from agents, so Slack markup is escaped, Discord mentions are switched off, and mail headers cannot be injected.

## API (all under `/api/v1`, JSON, `Authorization: Bearer <token>`)

| Method and path | Scope | Purpose |
|---|---|---|
| `GET /health` | none | Liveness probe |
| `GET /hub` | read | Hub ID, version, Java runtime age and warning |
| `POST /login` | none | Password sign-in for the local admin: answers with an ADMIN token that ends after a few hours; wrong attempts are throttled |
| `PUT /local-admin/password` | admin | Change the local admin password; ends the sign-ins made with the old one |
| `GET /me` | read | Who is calling: `method` (`token` or `jwt`), `name` and `scope`; lets a dashboard that holds no token show who is signed in |
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
| `POST /agent/register`, `GET /agent/config`, `POST /agent/report` | enroll or join key / agent | Agent protocol (agents only call out) |

### Live view (WebSocket)

Polling `GET /status` is how every client works by default. For a dashboard someone is watching, `GET /api/v1/live` upgrades to a WebSocket that pushes the roll-up as it changes, so there is no refresh delay and no repeated requests. The web UI has a **Live** switch for it and turns it off again after 5 minutes without activity.

A browser cannot set an `Authorization` header on a WebSocket, and a token in the address would end up in access logs, so the first frame carries the token: `{"type":"auth","token":"..."}`, within 5 seconds, from a READ or ADMIN token. The hub then sends `{"type":"status","data":{...}}` (the body of `GET /status`) at once and again after each change, checked once a second; a client that sends `{"type":"ping"}` gets `{"type":"pong"}` (the dashboard does so every 25 seconds, which keeps the link open through load balancers that drop quiet connections). A wrong or unsuitable token, an invalid frame, a revoked or expired token and 90 seconds of silence close the connection; the token is never echoed or logged. No cookies are used, so any origin may open the socket and the CORS list does not apply to it. With JWT sign-in on, a JWT works in place of a hub token: in the first frame, or as `Authorization: Bearer` on the upgrade request when a proxy adds it (then no first frame is needed, the page must be on the hub's own address, and the connection closes with code 1001 when the token expires). `svcmon.live-max-sessions` caps how many may be open (more are refused with close code 1013).

Behind a load balancer it must pass WebSocket upgrades (`Upgrade` and `Connection` headers) to the hub and allow idle connections for at least 30 seconds. If it does not, the Live switch tells the user it is unavailable and the dashboard keeps polling.

Check types: `HTTP`, `TCP`, `PING` run on the hub or on an agent; `SERVICE`, `CPU`, `MEMORY`, `DISK` run on an agent and need a server.

## Configuration (`hub/src/main/resources/application.yml`, or `--svcmon.*` flags)

| Key | Default | Meaning |
|---|---|---|
| `server.port` | 8080 | HTTP port |
| `svcmon.data-dir` | `./data` | Where the SQLite database lives |
| `svcmon.retention-days` | 30 | Raw results older than this are deleted |
| `svcmon.agent-heartbeat-seconds` | 30 | How often agents are told to report |
| `svcmon.agent-stale-seconds` | 90 | Silence after which a server shows as offline |
| `svcmon.ui-dir` | unset | Folder with the built web UI, served at `/`. A missing folder only logs a warning |
| `svcmon.cors-allowed-origins` | empty | Exact origins allowed to call the API from a browser; empty turns CORS off |
| `svcmon.live-max-sessions` | 50 | Most dashboards that may hold the live WebSocket open at once; more are refused (close code 1013) and keep polling |
| `svcmon.jwt.issuer` | unset | Issuer address of an OpenID Connect provider (https; http only for localhost). Setting it turns on sign-in with JWT access tokens; see above |
| `svcmon.jwt.audience` | unset | The audience (`aud`) a token must contain; required with the issuer |
| `svcmon.jwt.admin-role` | unset | The role that makes a user an administrator of the hub, who is the only kind let in; required with the issuer |
| `svcmon.jwt.roles-claim` | `realm_access.roles` | Where the roles are in the token; dots walk into nested objects |
| `svcmon.jwt.jwks-uri` | from discovery | Where the provider's signing keys are, if not the address in its discovery document |
| `svcmon.local-admin.enabled` | true | The hub's own administrator account with a password; see above |
| `svcmon.local-admin.username` | `admin` | Its user name |
| `svcmon.local-admin.password` | unset | Password to create the account with, 12 to 128 characters; unset means a random one is printed once at first start |
| `svcmon.local-admin.new-password` | false | Print a new random password at start-up and end earlier sign-ins (recovery) |
| `svcmon.local-admin.session-hours` | 12 | How long a password sign-in lasts, 1 to 168 |
| `svcmon.alert-retry-delays-ms` | `0,2000,5000` | Waits between delivery attempts for a failing alert channel, in milliseconds |
| `svcmon.allow-insecure-alert-urls` | false | Lets Slack and Discord URLs use http; for tests and local receivers only |
| `svcmon.bootstrap-admin-token` | unset | Use this value (32+ characters) as the first admin token on a fresh database instead of generating one; ignored once an admin token exists |
| `svcmon.new-admin-token` | false | Print an additional new admin token at startup (token recovery) |

## Test

```bash
cd hub && mvn test        # includes agent enrollment over HTTP and real webhook and SMTP alert delivery
cd agent && go test ./...
```

CI runs both on every pull request that changes more than documentation (Markdown-only, `docs/`, `LICENSE` and `NOTICE` changes skip the jobs), and also dry-runs the hub service installers (`hub/service/*/dry-run-test.*`). The real install tests (`smoke-test.*`) run only from the manually started **Installer smoke test** workflow; see [hub/service/README.md](hub/service/README.md).

## Layout

```text
docs/                design document (Markdown + HTML)
hub/                 Java 21, Spring Boot 4.1, SQLite (Maven project)
hub/service/         install and uninstall scripts for the hub as a service (Windows, Linux) and their tests
agent/               Go 1.27: svcmon agent + CLI (cmd/svcmon, internal/...)
.github/workflows/   CI, and the manually started installer smoke test
```

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
