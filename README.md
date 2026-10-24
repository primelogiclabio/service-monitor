# Service Monitor

A free, cross-platform monitor for HTTP, TCP, ping, Windows/Linux/macOS services, CPU, memory and disk. A headless Java hub with a REST API, one small Go binary (`svcmon`) that is both the agent and the command-line client, and an optional web UI and multi-hub console still to come.

Design and rationale: [docs/service-monitor-design.md](docs/service-monitor-design.md) (also as [HTML](docs/service-monitor-design.html)).

## Status (v0.1)

| Piece | State |
|---|---|
| Hub: scheduler, SQLite, REST API, tokens, HTTP/TCP/ping checks, roll-up status, alerts | Built, 69 tests passing |
| Agent (`svcmon agent run`): service, CPU, memory, disk, plus HTTP/TCP/ping from the server's side | Built; unit tests and a run against a real hub on Windows 11 |
| CLI (`svcmon status`, `check`, `token`, ...) | Built, same binary as the agent |
| Windows service install, Linux systemd unit, macOS launchd plist | Written and cross-compiled; only the Windows not-elevated error path was run |
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

### Run the hub as a service

To start at boot and restart after a crash, install it as a systemd service (Linux) or a Windows service through [WinSW](https://github.com/winsw/winsw), and remove it again with one command each. See [hub/service/README.md](hub/service/README.md).

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
| `GET /status` | read | Roll-up: summary, clusters, servers, checks |
| `GET /servers/{id}/status` | read | One server with its checks |
| `GET/POST /checks`, `GET/PUT/DELETE /checks/{id}` | read / admin | Manage checks |
| `POST /checks/{id}/run` | admin | Run a hub-side check now |
| `GET /checks/{id}/results?since=&limit=` | read | History, newest first |
| `GET /servers`, `PATCH /servers/{id}`, `DELETE /servers/{id}` | read / admin | Servers and clusters |
| `GET/POST /tokens`, `DELETE /tokens/{id}` | admin | API tokens (ADMIN or READ) |
| `POST /enrollment-tokens` | admin | One-time token to register an agent |
| `GET/POST /alert-channels`, `GET/PUT/DELETE /alert-channels/{id}` | admin | Alert channels; secrets are always masked |
| `POST /alert-channels/{id}/test` | admin | Send one test alert now |
| `POST /agent/register`, `GET /agent/config`, `POST /agent/report` | enroll / agent | Agent protocol (agents only call out) |

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
| `svcmon.alert-retry-delays-ms` | `0,2000,5000` | Waits between delivery attempts for a failing alert channel, in milliseconds |
| `svcmon.allow-insecure-alert-urls` | false | Lets Slack and Discord URLs use http; for tests and local receivers only |

## Test

```bash
cd hub && mvn test        # 69 tests, including agent enrollment and real webhook and SMTP alert delivery
cd agent && go test ./...
```

## Layout

```text
docs/   design document (Markdown + HTML)
hub/    Java 21, Spring Boot 4.1, SQLite (Maven project)
agent/  Go 1.27: svcmon agent + CLI (cmd/svcmon, internal/...)
```

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
