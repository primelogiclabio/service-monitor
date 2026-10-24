# Service Monitor: Design and Stack

A free, cross-platform monitor for Windows services, web/HTTP servers, TCP ports, ping, CPU, memory and disk. One lightweight agent per server, one hub per site, and an optional admin console that shows every hub in one place.

## 1. Summary

| Decision | Choice |
|---|---|
| Build or fork | Build new. Do not fork Uptime Kuma. |
| Hub language | Java (Spring Boot, virtual threads). Java 21 is the minimum; 25 is the target. |
| Agent language | Go: one small static binary per OS, no runtime on monitored servers. |
| Storage | Embedded SQLite. No database server. |
| Topology | Agents connect out to a hub. A console registers hubs and aggregates them (admin server and managed servers). |
| UI | Optional. The hub is headless and API-first; a CLI and an optional web UI are both clients of the same API. |
| What is monitored | Only what the admin lists. Nothing is auto-discovered into monitoring. |
| JRE | Bundled runtime by default; admins can supply their own Java instead. |
| Cost | Everything is open source. No paid component, cloud service or SaaS dependency. |

## 2. The problem and what already exists

The goal is one place to see whether services, web servers, ports and hosts are healthy, without logging in to each server in a cluster. Some checks only make sense on the server itself (Windows service state, CPU, memory, disk), so a tiny agent has to run there. Checks that only need the network (HTTP, TCP, ping) can run from anywhere.

| Option | What it covers | Gap for this project |
|---|---|---|
| Uptime Kuma (MIT) | HTTP(s), TCP, keyword, JSON query, WebSocket, ping, DNS, push, Docker; "90+" notification services | No agent and no host CPU, memory, disk or Windows-service monitoring listed. Node.js >= 20.4 for non-Docker installs. |
| Beszel (MIT) | Hub plus agent; CPU, memory, disk, network, containers, temperatures | The README does not list Windows service checks or uptime-style HTTP/TCP/ping checks. Its install guide lists Windows (WinGet/Scoop, NSSM service) and macOS (Homebrew) agents. |
| Prometheus + windows_exporter (MIT) | `service`, `cpu`, `memory`, `logical_disk` collectors on by default; port 9182 | Needs Prometheus, Grafana and Alertmanager as well. Several parts to run, and no single "service status" product. |

Why not fork Uptime Kuma: the missing pieces (agents, host metrics, a hub-and-console topology) are the core of this project, not an add-on. A fork would mean reworking its architecture and carrying upstream merges. Its real strength, the notification library, is easy to match for the handful of channels that matter (see section 8).

## 3. Architecture

Three roles, one codebase:

- **Agent** runs on a monitored server. It connects out to its hub, fetches its list of checks, runs the local ones (services, CPU, memory, disk) and reports results.
- **Hub** is a managed server. It stores data, runs network checks (HTTP, TCP, ping), sends alerts and accepts agents.
- **Console** is the admin server. It registers hubs by URL and a read-scoped token and shows all of them. It has no checks of its own. On a single box one process can be hub and console together.

```text
  Server A agent --\
  Server B agent ---+--> Hub (site 1) --\
  Server C agent --/                     \
                                          +--> Console (admin) <-- CLI / web UI
  Server D agent --\                     /
  Server E agent ---+--> Hub (site 2) --/

  Arrows from agents: outbound connections only. The console pulls from hubs
  (default) or hubs connect out to it (for sites behind NAT).
```

Rules that keep it simple:

1. **Agents connect out.** Monitored servers open no inbound ports. A server counts as down when its heartbeats stop.
2. **The hub owns the config.** The agent fetches its check list on connect, so changes are made once, in one place.
3. **The hub is headless.** Everything, including the UI, goes through the REST API (plus a live SSE stream).
4. **Each hub is autonomous.** It keeps monitoring and alerting if the console or the link to it goes down. The console shows a hub that stops answering as "hub unreachable".
5. **The console stays light.** It holds current status and fetches history on demand from the hub that owns it.

### Scenario: a new Windows server joins

1. The admin runs `svcmon agent token` on the hub side and gets an enrollment token and an install command.
2. On the server, an admin runs `svcmon agent install --hub http://hub:8080 --token ...` (the hub's internal address). This registers a Windows service (Linux: a systemd unit).
3. The agent presents the one-time token, receives its own credential and appears in the dashboard.
4. The admin assigns it to a cluster and lists the checks to run. Until the admin lists checks, nothing is monitored beyond the heartbeat.
5. The agent fetches the list, starts reporting, and the hub rolls its status up into the cluster.

### Scenario: the hub restarts

The agent buffers a few minutes of results in memory and replays them when the connection returns, so a restart leaves no gap and raises no false alert.

## 4. What gets monitored

| Check | Runs from | Notes |
|---|---|---|
| HTTP(s) | Hub or agent | Expected status codes, body keyword, timeout, redirects, headers, skip-TLS-verify option; response time and certificate expiry recorded. |
| TCP | Hub or agent | Connect with timeout; connect time recorded. |
| Ping (ICMP) | Hub or agent | See section 7 for the portability notes. |
| Service running | Agent | Windows Service Control Manager; systemd on Linux; launchd on macOS. |
| CPU, memory, disk | Agent | Disk checks are per volume. |

Every check has an interval, a timeout, and "N failures before marking down" so a single dropped packet does not page anyone.

### The admin chooses what is monitored

Nothing is monitored by default. For services, disks and ports the admin lists exactly what to watch; the agent ignores everything else and does not report it. This keeps noise and data exposure down.

A read-only helper, `svcmon agent services`, shows what exists on a server so the admin can pick. It adds nothing by itself.

```yaml
server: web01
cluster: prod
checks:
  - type: service
    name: W3SVC              # Windows service name (Linux: nginx.service)
  - type: disk
    path: "C:\\"
    warn: 80
    crit: 90
  - type: memory
    warn: 85
  - type: http
    url: https://web01/health
    run_from: hub
    expect_status: 200
```

## 5. Stack

| Layer | Choice | Why |
|---|---|---|
| Hub runtime | Java, Spring Boot, virtual threads | Spring Boot enables virtual threads with `spring.threads.virtual.enabled=true` on Java 21 or newer. Many concurrent checks are cheap. |
| Network checks | `java.net.http.HttpClient`, `Socket` with timeout | Built into the JDK. |
| Storage | SQLite via `xerial/sqlite-jdbc`, Flyway migrations | The JAR bundles native libraries for Windows, macOS, Linux and several ARM/x86 architectures; no separate SQLite install. Licenses: Apache-2.0 and BSD-2-Clause. |
| Agent link | HTTPS requests from agent to hub (register, fetch config, post reports), JSON, bearer tokens | Outbound only. A WebSocket stream may replace polling later. The protocol is language-neutral, so the agent can be Go or Java. |
| Alerts | Own senders: webhook (optionally HMAC-signed), Slack, Discord and email (built); Telegram and ntfy can follow | Each is a simple HTTP call or SMTP. A watchdog also alerts when an agent goes silent. |
| Agent | Go with `gopsutil`, `x/sys/windows/svc` | One static binary, no runtime. `gopsutil` (BSD) covers CPU, memory and disk on Linux, Windows and macOS; `x/sys/windows/svc` builds Windows services. |
| CLI | `svcmon` (Go) or picocli (Java) | Talks to the API with a scoped token. A Go binary starts instantly. |
| UI (optional) | Svelte single-page app in its own repository (service-monitor-ui), charts with uPlot | Built with Node at build time only. The hub serves the built files from `svcmon.ui-dir`, or the app is hosted elsewhere and the hub allows its address with `svcmon.cors-allowed-origins`. Thymeleaf plus htmx is the zero-JS alternative. |
| Build | Maven or Gradle, Go toolchain, GitHub Actions matrix | |

### Server-side requirements

**Hub or console server** (sizes are estimates until measured):

- Windows, Linux or macOS, x64 or ARM.
- Java runtime: bundled by default, or an admin-supplied Java 21 or newer.
- About 512 MB RAM (1 GB comfortable with the UI) and about 200 MB disk plus the SQLite file.
- One inbound port for agents, CLI and UI: plain HTTP on 8080 by default. TLS is terminated at the load balancer in front of the hub, so the hub itself needs no certificate.
- The system `ping` command for ICMP checks. In Docker, the image needs `ping` and the `NET_RAW` capability.
- Run as a systemd unit, a Windows service through WinSW (MIT license) or a container. `hub/service/` has install and uninstall scripts for the first two; they run the hub as an unprivileged account and keep settings in a YAML file, not the registry.
- Not needed: a database server, web server, reverse proxy, Node or Python.

**Monitored server (agent):**

- The single `svcmon` binary (about 10 MB, 10 to 30 MB RAM, estimates).
- Outbound access to the hub port. Admin/root once, to install the service.
- Windows 10 or Server 2016 or newer; Linux kernel 3.2 or newer; macOS 12 or newer (Go's current minimums).

## 6. Packaging and the JRE question

Bundling a runtime does not add risk by itself. It moves the patching duty from the admin's operating system to the project: a bundled runtime is not updated by `apt` or Windows Update, so it only gets security fixes when a new release ships. The hub's fat JAR bundles Spring, Tomcat and other libraries with the same property, so a release pipeline is needed regardless.

Two downloads:

- **Bundled package (default):** a slim runtime built with `jlink`, which includes only the modules the hub needs. Runtimes must be built per target platform, so CI runs a build matrix.
- **Bare JAR:** for admins who run their own patched Java. The launcher uses, in order, an explicit `svcmon.java` setting, the bundled runtime, then `JAVA_HOME`/`PATH`. At startup the hub checks for Java 21 or newer and exits with a clear message otherwise.

Keeping it safe:

- CI rebuilds on each quarterly JDK security update and on Spring/Tomcat CVEs (Dependabot or Renovate plus a scheduled build).
- The hub reports its runtime version and warns in the CLI, UI and logs when it is older than a set age.
- Use a free OpenJDK distribution such as Eclipse Temurin (offered at no licensing fee); confirm its license text before redistributing it.
- The Go agent has the same property: it is built with Go's standard library, so Go security releases need a rebuild. One release pipeline covers the hub, the agent and their dependencies.

## 7. Cross-OS notes and gotchas

- **Ping is the main portability trap.**
  - Java's `InetAddress.isReachable` uses ICMP only if the privilege can be obtained, and otherwise tries TCP port 7, so it is not a reliable ping. The hub uses the system `ping` command (or JNA on Windows) instead.
  - On the Go side, `pro-bing` (MIT) pings unprivileged on Linux only after the `net.ipv4.ping_group_range` sysctl is set; otherwise it needs `CAP_NET_RAW` or root. On Windows it requires `SetPrivileged(true)` but is reported to work without elevated privileges (tested on Windows 10). Its page does not cover macOS.
  - The agent installer sets `CAP_NET_RAW` on Linux.
- **Load average** is not available on Windows in `gopsutil`, so it is not a cross-platform check.
- **Service names differ per OS:** a Windows service name, a systemd unit, or a launchd label. The check is one type with an OS-specific backend.
- **Cross-compiling:** Go builds all target binaries from one machine with `GOOS`/`GOARCH`. A `jlink` runtime image must be built for each target platform, so CI uses a matrix (the `jlink` manual does not describe cross-platform image builds).

## 8. Security and alerts

- Agents authenticate with a per-agent credential issued from a one-time enrollment token. TLS is terminated at the load balancer. Agents, hubs and consoles talk over the internal network in plain HTTP, so anyone who can sniff that network can read the bearer tokens; keep the hub port firewalled to the load balancer and internal ranges.
- The CLI and console use scoped API tokens. The console holds only read-scoped tokens for hubs.
- The hub (behind the load balancer) is the only exposed component; monitored servers open nothing. Because agents are internal, the load balancer can refuse `/api/v1/agent/*` from outside. It must also overwrite any client-supplied `X-Forwarded-For` header, which the hub trusts from private-range proxies so rejected requests are logged with the real client address.
- The admin's allowlist limits what an agent reports.
- Alert channels: webhook, Slack, Discord and email are built, with secrets masked in every response. The Shoutrrr project (Go, MIT) lists around thirty services and is a useful reference for what to add later, such as Telegram and ntfy.

## 9. Build order

1. Hub core: scheduler, SQLite, REST API, API tokens, HTTP/TCP/ping checks.
2. CLI.
3. Agent: registration, heartbeat, service/CPU/memory/disk checks.
4. Alerting channels.
5. Optional web UI.
6. Console role (multi-hub). The API is designed for it from the start: stable hub ID, read-scoped tokens, aggregate-friendly endpoints.

**Status:** steps 1 to 3 are built and tested: the hub (Spring Boot 4.1 on Java 21, 69 tests) and the Go `svcmon` binary that is both the agent and the CLI (Go 1.27, unit tests, and a run against a real hub on Windows). Alert channels (webhook, Slack, Discord, email) are built; the console role is not. The web UI is built in its own repository and the hub can serve it or allow it from another address.

## 10. Open questions and limits

- **Untested on real targets:** the agent was run end to end on Windows 11 only. The Linux (systemd) and macOS (launchd) service installers and service checks compile and pass `go vet` but have not been run on those systems. On Windows, `agent install` was only checked for its not-elevated error message; installing the service needs an Administrator prompt.
- **Java version:** the machine has Java 21, so v1 targets 21. This doc assumes 25 is a long-term-support release but that could not be confirmed from Oracle's roadmap page (it returned HTTP 403).
- **macOS ping:** unprivileged ICMP on macOS is not confirmed by the sources used.
- **Beszel on Windows services:** not confirmed either way from the pages read.
- **Resource figures** (RAM, disk, agent size) are estimates, not measurements.
- **Not covered:** high availability for the hub, long-term metric retention rules, and a public status page.

## References

**Tools compared**

- Uptime Kuma: https://github.com/louislam/uptime-kuma
- Beszel: https://github.com/henrygd/beszel and install guide https://beszel.dev/guide/agent-installation
- windows_exporter: https://github.com/prometheus-community/windows_exporter

**Libraries and runtimes**

- pro-bing (Go ping): https://github.com/prometheus-community/pro-bing
- gopsutil: https://github.com/shirou/gopsutil
- OSHI (all-Java alternative for host metrics): https://github.com/oshi/oshi
- sqlite-jdbc: https://github.com/xerial/sqlite-jdbc
- Shoutrrr (original): https://github.com/containrrr/shoutrrr, maintained fork https://github.com/nicholas-fedor/shoutrrr
- Go Windows services: https://pkg.go.dev/golang.org/x/sys/windows/svc
- WinSW: https://github.com/winsw/winsw

**Platform documentation**

- Spring Boot virtual threads: https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html
- `jlink`: https://docs.oracle.com/en/java/javase/25/docs/specs/man/jlink.html
- `InetAddress.isReachable`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/InetAddress.html
- Go minimum OS requirements: https://go.dev/wiki/MinimumRequirements

**To act on this doc**

- Eclipse Temurin (OpenJDK builds): https://adoptium.net/temurin/
- Go toolchain: https://go.dev/dl/

Use the official GitHub or vendor addresses above; avoid third-party mirrors or repackaged downloads.
