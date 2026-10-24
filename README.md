# Service Monitor

A free, cross-platform monitor for HTTP, TCP, ping, Windows/Linux/macOS services, CPU, memory and disk. A headless Java hub with a REST API, one small Go binary (`svcmon`) that is both the agent and the command-line client, and an optional web UI and multi-hub console still to come.

Design and rationale: [docs/service-monitor-design.md](docs/service-monitor-design.md) (also as [HTML](docs/service-monitor-design.html)).

## Status (v0.1)

| Piece | State |
|---|---|
| Hub: scheduler, SQLite, REST API, tokens, HTTP/TCP/ping checks, roll-up status | Built, 23 tests passing |
| Agent (`svcmon agent run`): service, CPU, memory, disk, plus HTTP/TCP/ping from the server's side | Built; unit tests and a run against a real hub on Windows 11 |
| CLI (`svcmon status`, `check`, `token`, ...) | Built, same binary as the agent |
| Windows service install, Linux systemd unit, macOS launchd plist | Written and cross-compiled; only the Windows not-elevated error path was run |
| Alert channels | Status changes are logged and published as events; email/webhook/Slack/etc. not built |
| Web UI, multi-hub console | Not started |

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

## Test

```bash
cd hub && mvn test        # 23 tests, including a full agent enrollment over HTTP
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
