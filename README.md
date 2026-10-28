# Service Monitor

A free, cross-platform monitor for websites, ports, hosts, services, containers, CPU, memory and disk. The hub is one Java jar (REST API and dashboard); the agent and the command line are one small Go program. Agents connect out to the hub, so monitored servers open no inbound ports.

| Light theme | Dark theme |
|---|---|
| ![The dashboard in the light theme: overall status, then a card per server grouped by cluster](docs/assets/dashboard-light.png) | ![The dashboard in the dark theme](docs/assets/dashboard-dark.png) |

Pictures are from a demo hub with made-up servers. More of them, with the story behind each, are in the [stack guide](docs/service-monitor-stack-guide.md).

## Stack

| Part | Built with |
|---|---|
| Hub | Java 21, Spring Boot 4.1 (web, WebSocket, JDBC), Maven, SQLite with Flyway migrations, Angus Mail for email alerts |
| Agent and CLI (`svcmon`) | Go 1.27 with [gopsutil](https://github.com/shirou/gopsutil), [pro-bing](https://github.com/prometheus-community/pro-bing), `golang.org/x/sys` and go-winio; one self-contained binary, built for Windows, Linux, macOS and FreeBSD |
| Dashboard | Svelte 5, Vite 6 and uPlot, in its own repository, [service-monitor-ui](https://github.com/primelogiclabio/service-monitor-ui); built files go inside the hub jar |
| Running as a service | WinSW on Windows, systemd on Linux, launchd for the macOS agent |
| Build and test | JUnit and Maven, Go's test tooling, Vitest; GitHub Actions for CI and for releases |
| Licence | Apache-2.0, everything free and open source |

## Quick start

You need Java 21 or newer for the hub. The files come from the [latest release](https://github.com/primelogiclabio/service-monitor/releases/latest), whose page has the install and upgrade steps (the Windows zip and Linux tarball install the hub as a service), or build them as shown under *Build from source*. To upgrade, see [Upgrading](docs/service-monitor-reference.md#upgrading).

**1. Start the hub.** Open <http://localhost:8080> from the same network and sign in as `admin` with the password `admin`; the hub then asks you to choose a new password before anything else. The first start also prints an admin token once, for the CLI.

```bash
java -jar svcmon-hub-<version>.jar init   # optional: write application.yml, every setting commented out
java -jar svcmon-hub-<version>.jar        # run it; to start at boot see hub/service/README.md
```

**2. Add a server.** The dashboard's *Add server* page gives the exact command. On the server, rename the downloaded `svcmon-agent-<version>-<os>-<cpu>` to `svcmon` (`svcmon.exe` on Windows) and keep it where it will stay, since the service runs it from there; then, as Administrator or root:

```bash
svcmon agent install --hub http://hub:8080 --token svm_e_...
```

**3. Say what to watch**, on that server, with no hub login:

```bash
svcmon agent services --filter nginx            # what exists (nothing is monitored on its own)
svcmon agent check add service nginx.service
svcmon agent check add disk / --warn 80 --crit 90
svcmon agent check add http http://localhost:8080/health
```

Prefer a file? `svcmon agent check init` writes `checks.example.json` to edit into `checks.json`. Checks can also be added in the dashboard or with `svcmon check add` from anywhere.

**4. Read the result** in the dashboard, or with `svcmon login --hub ... --token ...` and then `svcmon status`. Alerts go to webhooks, Slack, Discord or email once you add a channel.

### Build from source

```bash
cd hub && mvn package                                    # target/svcmon-hub-*.jar (no dashboard inside)
cd agent && go build -trimpath -o svcmon ./cmd/svcmon    # the agent and CLI
cd ../service-monitor-ui && npm ci && npm run build      # dashboard; copy dist/ to hub/src/main/resources/ui before mvn package to put it in the jar
```

## How it fits together

- **Agents only connect out.** The hub is the portal: it stores results, rolls them up, sends alerts and serves the API and dashboard. A server counts as down when its heartbeats stop.
- **Someone lists what is monitored**, an admin in the hub or the person setting up the server in its `checks.json`. Nothing is discovered and monitored on its own.
- **TLS ends at your load balancer.** The hub speaks plain HTTP inside the network, so only the hub is exposed, and it works behind `X-Forwarded-*` headers.
- **Secrets stay in the hub**: only token hashes are stored, and webhook addresses, signing secrets and the SMTP password are masked in every answer and never logged.

## Documentation

| | |
|---|---|
| [Reference](docs/service-monitor-reference.md) | What is built and how far it has been tried, running and upgrading the hub, signing in (users, tokens, behind a sign-in gateway), servers and checks, alerts, the API, every setting |
| [Hub as a service](hub/service/README.md) | The Windows and Linux installers, their dry runs and the release packages |
| [Design](docs/service-monitor-design.md) | Why it is built this way ([HTML](docs/service-monitor-design.html)) |
| [Sign-in and access plan](docs/service-monitor-signin-plan.md) | What is built of people's sign-in and what comes next: internal-only access, an audit, a gateway that only gates ([HTML](docs/service-monitor-signin-plan.html)) |
| [Stack guide](docs/service-monitor-stack-guide.md) | The technology, explained for a Java developer ([HTML](docs/service-monitor-stack-guide.html)) |
| [Trust policy](docs/service-monitor-trust-policy.md) | Licences, what data it handles, what it connects to and installs ([HTML](docs/service-monitor-trust-policy.html)) |
| [RELEASING.md](RELEASING.md) | How a release is made |
| [CLAUDE.md](CLAUDE.md) | The working agreement for changing the code |

## Develop

```bash
cd hub && mvn test        # includes agent enrollment over HTTP and real webhook and SMTP alert delivery
cd agent && go test ./...
```

CI runs both on every pull request that changes more than documentation, dry-runs the hub installers, and cross-compiles the agent for every platform.

```text
hub/                 Java hub (Maven); hub/service/ has its installers
agent/               Go: svcmon agent and CLI (cmd/svcmon, internal/...)
docs/                reference, design, stack guide, trust policy (Markdown and HTML), pictures
.github/             CI, the release workflow and its smoke test
```

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
