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
| What is monitored | Only what someone lists: an admin in the hub, or the person setting up the server in its `checks.json`. Nothing is auto-discovered into monitoring. |
| JRE | Planned: a bundled runtime by default, with admins able to supply their own Java instead. Today the hub is a plain jar that needs a Java 21 or newer on the machine. |
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
3. **The hub is headless.** Everything, including the UI, goes through the REST API (polling `GET /status` is the default; an optional WebSocket at `/api/v1/live` pushes the same roll-up when it changes, see the README).
4. **Each hub is autonomous.** It keeps monitoring and alerting if the console or the link to it goes down. The console shows a hub that stops answering as "hub unreachable".
5. **The console stays light.** It holds current status and fetches history on demand from the hub that owns it.

### Scenario: a new Windows server joins

1. The admin runs `svcmon agent token` on the hub side and gets an enrollment token and an install command. For many servers, a reusable join key (`svcmon join-key create`) gives one install command that works on all of them until it is revoked; it can pin the cluster its servers land in.
2. On the server, an admin runs `svcmon agent install --hub http://hub:8080 --token ...` (the hub's internal address). This registers a Windows service (Linux: a systemd unit).
3. The agent presents the one-time token (or the join key), receives its own credential and appears in the dashboard.
4. The admin assigns it to a cluster. The checks to run are listed on the server (`svcmon agent check add`, which fills `checks.json`) or by the admin in the hub. Until someone lists checks, nothing is monitored beyond the heartbeat.
5. The agent fetches the list, starts reporting, and the hub rolls its status up into the cluster.

### Scenario: the hub restarts

The agent buffers a few minutes of results in memory and replays them when the connection returns, so a restart leaves no gap and raises no false alert. Once the hub is ready again it logs a start-up summary (listening address, database, enabled alert channels, and the checks and servers it watches, by name and type only) so the service log shows what came back without calling the API.

### Checks declared by the agent

Checks are made in the hub, which stays the default, or declared by the agent in a file. Both work at once and the agent still polls one merged list. When they disagree, the agent wins. Built, and on unless the admin turns it off.

- **The file.** `checks.json` beside `agent.json`, validated as strictly as the admin API (unknown settings are rejected). It lists that server's checks and nothing else. The agent sends the whole list to the hub when it starts and whenever the file changes (looked at on the existing config poll), with its own agent token.
- **Identity.** A declared check stays the same check while its server, type and target (or an `id` the file gives it, for two checks of one type and target) stay the same, so history survives edits to intervals and thresholds. A different target is a new check.
- **The agent wins.** For every setting the file gives, the declared value replaces the hub's at each sync. A check made in the hub with the same identity is taken over, keeps its history, and is marked as declared by the agent from then on. The dashboard and API show that source and refuse edits to a declared check, with a message pointing at the file, because the next sync would undo them. The on/off switch stays with the hub (there is no separate mute flag), so a noisy check can be silenced during maintenance without editing a file on the server.
- **Removal.** A check removed from the file is disabled in the hub and marked as removed from the agent's file, not deleted, so its history stays until an admin deletes it. A missing file declares nothing, so deleting the file switches nothing off; an empty list does.
- **A bad file.** The hub refuses an invalid entry and says why in its answer; the agent logs it and keeps running the last good list. A hub that is down changes nothing: the agent keeps the last list it was given.
- **Trust.** A hub setting, `svcmon.agent-declared-checks`, is on by default: whoever sets up a server knows what it should run, which beats installing the agent and then going to the hub to say so. An admin who wants only the hub to list checks sets it to false and the hub refuses declarations. While it is on, an agent may declare checks only for its own server, only checks that run on the agent (never one the hub runs, so a server cannot make the hub call addresses of its choosing), up to `svcmon.agent-declared-checks-limit` (200) per server. It cannot create alert channels or touch another server's checks. Declared checks use the same alert channels and rules as any other.
- **On the server.** `svcmon agent check add|rm|list` edits the file (written in one step, refused if the file cannot be read, a repeat of the same type and target refused) and sends it to the hub at once, so a server's checks are managed from that server with its agent token and no admin login. The file is saved even when the hub cannot be reached or has the switch off; the running agent sends it later.
- **A hub that is down.** The agent keeps the list the hub last gave it (ids included, which a file alone cannot supply) in `assigned.json` beside `agent.json`. Started while the hub does not answer, or fails on its side, it runs that list at once and buffers results as it already does; the next config poll swaps in the hub's list. A hub that answers 4xx (token revoked, server deleted) is not worked around. Alerts stay on the hub, so a hub that is down alerts nobody; a check never declared to the hub has no id and is not in the cache.
- **Containers.** This suits container monitoring: the containers to watch are listed in the file that ships with the compose project. Nothing is discovered automatically.

Settled while building it: the file is JSON (no new dependency; a byte order mark from Windows editors is accepted), the limit is a hub setting that defaults to 200 per server, the switch is hub-wide rather than per server, and muting is the existing on/off switch. Not done yet: the dashboard does not show a check's source or lock its form (the API already answers an edit of a declared check with 409), and it has not run on an installed service.

## 4. What gets monitored

| Check | Runs from | Notes |
|---|---|---|
| HTTP(s) | Hub or agent | Expected status codes, body keyword, timeout, redirects, headers, skip-TLS-verify option; response time and certificate expiry recorded. |
| TCP | Hub or agent | Connect with timeout; connect time recorded. |
| Ping (ICMP) | Hub or agent | See section 7 for the portability notes. |
| Service running | Agent | Windows Service Control Manager; systemd on Linux; launchd on macOS. |
| Container | Agent | Docker or Podman, asked over the local socket with read-only requests; state and health, not resource use. Linux, macOS and Windows (Docker Desktop's named pipe). |
| CPU, memory, disk | Agent | Disk checks are per volume. |

Every check has an interval, a timeout, and "N failures before marking down" so a single dropped packet does not page anyone.

### What is monitored is listed, not discovered

Nothing is monitored by default. For services, disks and ports someone (an admin in the hub, or the person setting up the server in its `checks.json`) lists exactly what to watch; the agent ignores everything else and does not report it. This keeps noise and data exposure down.

A read-only helper, `svcmon agent services` (and `svcmon agent containers` for Docker or Podman), shows what exists on a server so the admin can pick. It adds nothing by itself.

The sketch below shows what a server's check list amounts to. It is not a config file the product reads: checks are created with `svcmon check add` or the API.

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
| Alerts | Own senders: webhook (optionally HMAC-signed), Slack, Discord and email (built; HTML with a plain-text part, or plain text only); Telegram and ntfy can follow | Each is a simple HTTP call or SMTP. A watchdog also alerts when an agent goes silent. |
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
- Run as a systemd unit, a Windows service through WinSW (MIT license) or a container. `hub/service/` has install and uninstall scripts for the first two; they run the hub as an unprivileged account and keep settings in a YAML file, not the registry. The Windows installer takes WinSW from `-WinSWPath`, else from the `WinSW-x64.exe` beside the script (the Windows hub package ships one, so installing from it needs no download), else downloads the pinned release. Whichever it uses must match the SHA-256 pinned in the script; a wrong copy is an error, never a silent download.
- Not needed: a database server, web server, reverse proxy, Node or Python.

**Monitored server (agent):**

- The single `svcmon` binary (about 10 MB, 10 to 30 MB RAM, estimates).
- Outbound access to the hub port. Admin/root once, to install the service.
- Windows 10 or Server 2016 or newer; Linux kernel 3.2 or newer; macOS 12 or newer (Go's current minimums).

## 6. Packaging and the JRE question

**Status: not built yet.** Today the hub is a plain jar that runs on a Java 21 or newer that the admin installs, and the service installers in `hub/service/` use that Java. The `jlink` bundle and the release pipeline below are the plan. The one piece that exists is a manually started workflow that builds the Windows hub package (the jar, the installer scripts and WinSW) and keeps it as a workflow artifact; it is not published as a release.

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
  - Java's `InetAddress.isReachable` uses ICMP only if the privilege can be obtained, and otherwise tries TCP port 7, so it is not a reliable ping. The hub uses the system `ping` command instead.
  - On the Go side, `pro-bing` (MIT) pings unprivileged on Linux only after the `net.ipv4.ping_group_range` sysctl is set; otherwise it needs `CAP_NET_RAW` or root. On Windows it requires `SetPrivileged(true)` but is reported to work without elevated privileges (tested on Windows 10). Its page does not cover macOS.
  - The agent installer sets `CAP_NET_RAW` on Linux.
- **Load average** is not available on Windows in `gopsutil`, so it is not a cross-platform check.
- **Service names differ per OS:** a Windows service name, a systemd unit, or a launchd label. The check is one type with an OS-specific backend.
- **Container runtimes are reached through a socket, and the socket is root.** Docker and Podman answer the same API on a unix socket, so one client covers both (Go standard library, no Docker SDK). Whoever can write to that socket can run anything as root, so the agent has only a GET code path for it and monitors state and health, nothing more. Reading it needs root or the runtime's group; an agent installed as root has that, and a future `--run-as` would need the group. On Windows, Docker Desktop and the Podman machine listen on a named pipe instead, which the agent reaches with go-winio (MIT; the one dependency this feature adds); the pipe's access rules decide whether the agent's account may use it.
- **Cross-compiling:** Go builds all target binaries from one machine with `GOOS`/`GOARCH`. A `jlink` runtime image must be built for each target platform, so CI uses a matrix (the `jlink` manual does not describe cross-platform image builds).

## 8. Security and alerts

- Agents authenticate with a per-agent credential issued from a one-time enrollment token or a reusable join key. A join key can only register agents, is never shown again after it is created, and revoking it does not affect the servers it registered. TLS is terminated at the load balancer. Agents, hubs and consoles talk over the internal network in plain HTTP, so anyone who can sniff that network can read the bearer tokens; keep the hub port firewalled to the load balancer and internal ranges.
- The CLI and console use scoped API tokens. The console holds only read-scoped tokens for hubs.
- People can also sign in with an OpenID Connect access token (Keycloak or another provider), which is off unless an issuer is configured. The hub checks the JWT itself, as every other API behind the same provider does: the signature against the keys in the issuer's discovery document, the issuer, the audience and the expiry. Only asymmetric algorithms are accepted, and nothing is trusted because of where the request came from, so the hub does not depend on network isolation. A token that holds the configured admin role gets the access of an admin token; any other valid token is refused. The hub's own tokens stay for agents and the CLI, and a bearer value is told apart by shape (a JWT is three dot-separated parts). A browser need never hold the token: nginx can ask a gateway to exchange the session for one and add it to the call. Because the session cookie is then what authenticates, the proxy must demand a header only the dashboard sends before it does so, and for the live WebSocket, which cannot carry that header, the hub requires the page to be on its own site, as the browser reports it (`Sec-Fetch-Site`, or the `Origin` against the hub's address). Agents never go through the sign-in.
- The hub (behind the load balancer) is the only exposed component; monitored servers open nothing. Because agents are internal, the load balancer can refuse `/api/v1/agent/*` from outside. It must also overwrite any client-supplied `X-Forwarded-For` header, which the hub trusts from private-range proxies so rejected requests are logged with the real client address.
- The hub also has one administrator of its own, so there is a way in when the provider behind the proxy is down. Its password is stored only as a salted PBKDF2-HMAC-SHA256 hash, set at first start (random and printed once, or configured) and changed through the API, which ends every sign-in made with the old one. Signing in answers with an ordinary ADMIN token that ends after a few hours, so nothing else in the hub needs to know about passwords. Guessing is throttled per client address and in total, a wrong user name looks exactly like a wrong password, and the proxy should not expose the sign-in address publicly.
- The admin's allowlist limits what an agent reports.
- Alert channels: webhook, Slack, Discord and email are built, with secrets masked in every response. The Shoutrrr project (Go, MIT) lists around thirty services and is a useful reference for what to add later, such as Telegram and ntfy.

## 9. Build order

1. Hub core: scheduler, SQLite, REST API, API tokens, HTTP/TCP/ping checks.
2. CLI.
3. Agent: registration, heartbeat, service/CPU/memory/disk checks.
4. Alerting channels.
5. Optional web UI.
6. Console role (multi-hub). The API is designed for it from the start: stable hub ID, read-scoped tokens, aggregate-friendly endpoints.

**Status:** steps 1 to 3 are built and tested: the hub (Spring Boot 4.1 on Java 21) and the Go `svcmon` binary that is both the agent and the CLI (Go 1.27, unit tests, and a run against a real hub on Windows). Alert channels (webhook, Slack, Discord, email) are built; the console role is not. The web UI is built in its own repository and the hub can serve it or allow it from another address. The hub can be installed as a service on Windows (WinSW) and Linux (systemd), with a dry run first, and logs a start-up summary of its address and what it watches. The bundled runtime and release pipeline (section 6) are not built.

## 10. Open questions and limits

- **Untested on real targets:** the agent was run end to end on Windows 11 (service installed from an elevated prompt) and on Ubuntu 26.04 (systemd unit installed as root; HTTP, CPU, memory, disk and service checks ran). The macOS (launchd) service installer and service checks compile and pass `go vet` but have not been run on that system.
- **Agent-declared checks:** built (section 3). Run against a throwaway hub and Docker Desktop on Windows, not yet on an installed service; the dashboard does not show where a check comes from.
- **Container checks:** tested against a stand-in for the runtime's API; run by hand against Docker Desktop on Windows (the lister), but not yet as an installed service, on Linux or against Podman. Not built: per-container CPU and memory.
- **Hub service installers:** CI only dry-runs them. The real install, upgrade, port change and uninstall were run on GitHub's Ubuntu and Windows runners (the manually started "Installer smoke test"), not on a long-lived server; its last install runs from the Windows hub package. There is no macOS installer.
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
