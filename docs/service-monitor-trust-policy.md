# Service Monitor: stack, licences, data and trust

This document is for anyone deciding whether to run Service Monitor, or who has been asked to approve it. It says in plain words what the software is built from, whether any of it needs a paid licence, what data it handles, who it talks to, and what it installs or downloads. For how the pieces work together see `service-monitor-stack-guide.md`.

**How to read it.** Everything here comes from reading the source and build files of the hub (`main` at `1630d17`) and the dashboard (`main` at `1ae864d`) on 2026-10-25, and from running the programs. Where it says "none found", that means a search of the source found none; the code is open, so you can repeat the search (section 9). This is a description of the software, not a legal opinion and not an independent audit.

## 1. At a glance

| Question | Answer |
|---|---|
| Does it need a paid licence or an account with anyone? | No. The project is Apache-2.0 and every library it uses is open source. You do need a Java 21 or newer runtime for the hub, which you choose and supply (free builds exist). There is no registration, licence key or licence server |
| Does it send usage data, analytics or crash reports to its authors or anyone else? | None found. There is no telemetry, analytics, tracking or "phone home" code in the hub, the agent or the dashboard |
| Does it update itself? | No. Nothing downloads or applies an update; you install new versions yourself |
| Does it download anything while it runs? | No. The hub and agent make only the connections listed in section 5 |
| Does it download anything when you install it? | One case: the Windows hub installer fetches the service wrapper WinSW from GitHub only when you have not supplied a copy, and refuses it unless it matches a pinned SHA-256 (section 6). Nothing else |
| What does it install on a server? | The agent registers itself as a service when an admin runs `svcmon agent install`; the hub installers register the hub the same way. No package manager is used and no other software is added |
| What data does it keep? | Names and settings of what you chose to monitor, and the results of those checks for 30 days (section 4). No personal data about your users |
| Who receives data from it? | Only the alert channels and sign-in provider you configure (section 5) |
| Does a monitored server open a port for it? | No. Agents only connect out |
| Has it been independently audited? | No |

## 2. What it is made of

| Piece | Built with | Notes |
|---|---|---|
| The hub | Java 21, Spring Boot 4.1.2, Maven. SQLite for storage, Flyway for database changes | A jar of about 37 MB that runs on any Java 21 or newer runtime. It serves the API, the optional dashboard files and a live WebSocket |
| The agent and the CLI | Go 1.27.3. One program, `svcmon`, about 12 MB | No runtime needed on the monitored server. Builds for Windows, Linux and macOS |
| The dashboard | Svelte 5, Vite 6, uPlot, Node.js 20 or newer to build | Plain static files once built. Node is not needed to run it |
| Optional | An OpenID Connect identity provider of your choice for sign-in (Keycloak is the one tried; the hub is configured with the provider's address, so nothing is tied to it); a load balancer for TLS | Not part of this project; you run them yourself if you want them |

## 3. Licences

**The project itself** is licensed under the Apache License 2.0 (`LICENSE`), for the hub, agent and dashboard repositories alike.

**Nothing needs a paid licence.** The hub jar contains 52 libraries. Their licences, read from each library's own build file:

| Licence | Count | Examples |
|---|---|---|
| Apache License 2.0 | 42 | Spring Boot and Spring Framework, Tomcat, Jackson, Flyway, `sqlite-jdbc`, Nimbus JOSE+JWT |
| MIT | 2 | SLF4J and its JUL bridge |
| Eclipse Public License 2.0 (Logback also offers LGPL 2.1) | 2 | Logback |
| EPL 2.0 or GPL 2 with the Classpath Exception, and the Eclipse Distribution License (BSD-style) | 5 | Jakarta Mail and Angus Mail, Jakarta Activation and its Angus implementation, Jakarta Annotations |

One entry, Spring Boot's own `jarmode-tools`, has no build file in the local cache; it ships with Spring Boot, which is Apache-2.0. SQLite itself is in the public domain, and the Java driver that carries it is Apache-2.0.

**The agent** compiles in a few small Go modules, all open source:

| Module | Licence | Built into |
|---|---|---|
| `gopsutil` v4.26.9 | BSD | All platforms |
| `pro-bing` v0.9.1 | MIT | All platforms |
| `golang.org/x/sys`, `x/net`, `x/sync` | BSD-style (Go authors) | All platforms |
| `google/uuid` v1.6.0 | BSD | All platforms |
| `go-winio` v0.6.4, `go-ole` v1.2.6, `wmi` v1.2.4 | MIT | Windows only |
| `tklauser/go-sysconf` v0.4.0 | BSD 3-clause | Linux and macOS |
| `tklauser/numcpus` v0.12.0 | Apache-2.0 | Linux only |
| `ebitengine/purego` v0.11.1 | Apache-2.0 | macOS only |

**The dashboard** ships its own code plus Svelte's small runtime and uPlot, both MIT. Vite, Vitest and the Svelte compiler are used to build and test it and are not part of what runs.

**WinSW** (MIT) is the wrapper that turns the hub jar into a Windows service. It is not stored in the repository; its licence text is kept in `hub/service/windows/WinSW-LICENSE.txt` and in `NOTICE`.

**The Java runtime** is the one thing you supply. The project does not bundle or require a particular vendor. Open-source builds such as Eclipse Temurin are available at no cost; whichever you choose, read that vendor's terms.

The copyleft-style licences above (Logback, Jakarta Mail) apply to those libraries themselves and are used unmodified. Running the hub does not ask you to publish anything. If you redistribute a modified copy of those libraries, their own terms apply. This is not legal advice.

## 4. What data it handles

### What an agent sends to the hub

| Data | When | Notes |
|---|---|---|
| Server name (by default the host name), optional cluster name | Once, when registering | You can choose the name with `--name` |
| Operating system description (for example "Microsoft Windows 11 Home 26H2 (amd64)") and the agent version | Registering and with each report | Shown on the server's page |
| Each check's result: status, response time or percentage, a short message (at most 500 characters), a time stamp | Every time a check runs | Messages are short facts such as `HTTP 200`, `running, up 29m, 0 restart(s)` or `C:\ 61.3% used` |
| A heartbeat | About every 30 seconds | An empty report |

The hub also sees the network address a request came from. It does not store that with servers or results; it appears in the log lines for rejected requests and sign-ins.

### What an agent reads on the server

Only what a check the admin listed needs, and nothing is discovered on its own. The commands that list what exists (`svcmon agent services`, `svcmon agent containers`) print to the terminal and send nothing.

| Check | What is read |
|---|---|
| CPU, memory, disk | Overall percentages for the named volume or path. No file names or contents |
| Service | Whether the named Windows service, systemd unit or launchd job is running |
| Container | The named container's state, health, image name, uptime and restart count, asked of the local Docker or Podman with read-only requests. Its environment variables, mounts and labels are never read into a message |
| HTTP, TCP, ping | Whether the address answers, how fast, the status code, and (HTTP) a keyword in the body, which is checked in memory and not kept. For https addresses, the certificate's expiry date |

### What the hub stores

In one SQLite file in its data folder:

- the servers (name, cluster, operating system text, agent version, last seen) and the checks (type, target, settings, interval);
- every check result for 30 days (`svcmon.retention-days`), then it is deleted;
- API tokens, as SHA-256 fingerprints only, never the tokens themselves;
- alert channels, including their secrets: webhook and Slack or Discord addresses, signing secrets and the SMTP password;
- the local admin account's password, as a salted PBKDF2 hash.

### What it does not collect

Files or their contents, documents, passwords or keys of your systems, environment variables, process lists, network traffic, screenshots, location, or anything about the people who use the dashboard beyond the sign-in name that appears in the hub's log. There are no user profiles and no advertising or tracking identifiers.

### In the browser

The dashboard keeps a few items in the browser's own storage: the sign-in token (in session storage, or in local storage if "remember" is chosen), the theme, which help panels are open, and the sign-in mode. It sets no cookies of its own and makes no requests except to the hub it was served from or pointed at. It loads no fonts, scripts or images from other sites.

### Logs

The hub's log records start-up facts (address, database path, the names and types of checks), sign-ins and refused requests with the client address and user name, and failures. By design it does not write tokens, alert addresses or check targets (the start-up summary has a test for exactly that). The agent's log (`agent.log` beside the program when it runs as a service) records what it fetched and sent, by name and count.

## 5. Who it connects to

| From | To | Why |
|---|---|---|
| Agent | Your hub (the address you give it) | Register, fetch its check list, send results. Plain HTTP unless you put TLS in front |
| Agent | The addresses of the HTTP, TCP and ping checks you listed | To run those checks from the server's side |
| Agent | The local Docker or Podman, through a local socket or Windows named pipe | Container checks. Nothing leaves the machine |
| Hub | The addresses of hub-run HTTP, TCP and ping checks you listed (ping uses the operating system's `ping` command) | To run those checks |
| Hub | The alert channels you set up: webhook, Slack and Discord addresses, or your SMTP server | To send alerts. The text sent includes the check name, status, message, server name and the check's target address |
| Hub | Your sign-in provider's discovery address and key list, only if you turn on OpenID Connect | To verify the signature of sign-in tokens |
| Browser | The hub | The dashboard's data and live updates |

Nothing else was found. In particular there is no connection to the project's authors, to a licence service, to an update server or to an analytics service.

Inbound: only the hub accepts connections (one port, 8080 by default). Monitored servers open nothing for the monitor.

## 6. What it installs, downloads or changes

**Running the hub or the agent** installs nothing and downloads nothing. There is no self-update.

**Installing the agent** (`svcmon agent install`, which needs an administrator or root) does these things, and only these:

- saves `agent.json` (the hub address and the agent's own token) beside the program, readable only by the owner, or on Windows by SYSTEM, Administrators and the installing user;
- registers a service: a Windows service, a systemd unit file, or a macOS launchd file;
- runs the system's own service tools to do so (`systemctl`, `launchctl`, `icacls`).

The agent runs as the Windows `LocalSystem` account and, on Linux, as root with `NoNewPrivileges` set and the single extra capability needed to send pings. It needs that to read service states and, for containers, the Docker socket. Removing it is `svcmon agent uninstall`.

**Installing the hub as a service** runs the scripts in `hub/service/`. Both can first be run with a dry-run switch that prints every step and changes nothing. They create an unprivileged account for the hub (a virtual account on Windows, a system user on Linux), register the service, lock the program and data folders to that account and administrators, and wait for the hub to answer on its own local address. They do not change the firewall and they use no package manager.

**The one download:** the Windows installer needs the service wrapper WinSW 2.12.0. It uses, in order, the copy you pass with `-WinSWPath`, a copy beside the script, and only then downloads it from the project's GitHub releases page. Whichever it uses must match the SHA-256 written in the script; a mismatch stops the install. The Windows hub package (built by a manually started workflow) already contains the copy, so installing from it downloads nothing.

**When you build from source** your build tools download libraries: Maven from Maven Central for the hub, the Go module proxy for the agent, the npm registry for the dashboard. That is the normal way these tools work and it happens on the machine that builds, not where the hub or agent runs.

## 7. How secrets are protected, and where they are not

Protected:

- API tokens are stored only as SHA-256 fingerprints, shown once when created.
- Alert channel secrets are masked in every API answer, and an update that sends the masked value back keeps the stored one.
- The hub's local admin password is stored only as a salted PBKDF2-HMAC-SHA256 hash. Sign-in attempts are throttled.
- Sign-in with an OpenID Connect provider checks the token's signature, issuer, audience and expiry in the hub; nothing is trusted because of where a request came from.
- Names and messages from agents are treated as untrusted: Slack markup is escaped, Discord mentions are off, mail headers cannot be injected, and the HTML in alert emails has no scripts, images or tracking.
- Strict settings: an unknown setting is an error, so a typo does not silently weaken something.

Things to know and handle yourself:

- **Secrets in the database are not encrypted at rest.** Alert channel secrets sit in the SQLite file as stored. Keep the data folder and any backup of it readable only by the hub's account and administrators (the installers do this for the folders they create).
- **Check settings are not masked.** A check's address and settings, including any HTTP headers you add, are shown to anyone holding a read token and are sent to the agent that runs the check. Do not put passwords or tokens in a check's URL or headers. The check's address also appears in alert messages.
- **Agent to hub traffic is plain HTTP inside your network** by design (TLS ends at your load balancer). Anyone who can read that network can see the bearer tokens. Firewall the hub's port to the load balancer and the internal networks.
- **A new hub prints one random admin password in its log at first start** (unless you set one). Read it once and keep the log folder restricted.
- **The agent is powerful by nature.** It runs with high privileges to read service states and the container socket. A container socket is as powerful as root, so the agent only sends read requests to it.
- **A stolen agent token** can report results for that one server and, only if you turn on `svcmon.agent-declared-checks`, declare checks for that server. Revoke the token to stop it.

## 8. Limits of this statement

- It has not been reviewed by an independent party, and this repository has no separate security-reporting process yet.
- The macOS agent installer and service checks have never run on a real Mac. Container checks and agent-declared checks have run against Docker Desktop on Windows but not yet as an installed service, on Linux or against Podman.
- The licence information for libraries comes from their published build files and licence files; check them again when you update a dependency.
- Counts and versions will change as the code does. The date at the top says when they were checked.

## 9. Check it yourself

| To see | Do this |
|---|---|
| Every outbound connection in the hub | Search `hub/src/main/java` for `HttpClient`, `Socket`, `URL` and `ProcessBuilder`. They are the alert senders, the check runners, the mail sender, the JWT key fetch and the `ping` command |
| Every outbound connection in the agent | Search `agent` for `http.Client`, `net.Dial` and `exec.Command`. They are the hub client, the check runners, the local container socket and the service tools |
| Telemetry | Search for `telemetry`, `analytics`, `tracking` and `beacon`; the only hit is a comment saying alert emails have none |
| What the dashboard requests | Open the browser's network panel while using it: every request goes to the hub. Or search `dist/` for `http`; only error-message links to svelte.dev appear, as text |
| The libraries in the hub | `jar tf hub/target/svcmon-hub-0.1.0-SNAPSHOT.jar` and look under `BOOT-INF/lib` |
| The modules in the agent | `go version -m svcmon` |
| The WinSW pin | Read `$WinSWSha256` in `hub/service/windows/install.ps1` and compare it with the release on https://github.com/winsw/winsw |
| What a running hub or agent connects to | Watch its connections with `netstat -ano` (Windows) or `ss -tnp` (Linux) |
| What an install would change | Run the installer with `-DryRun` (Windows) or `--dry-run` (Linux) |

## 10. References

**Project**

- Main repository: https://github.com/primelogiclabio/service-monitor (licence in `LICENSE`, third-party notice in `NOTICE`, user guide in `README.md`, design in `docs/service-monitor-design.md`)
- Dashboard repository: https://github.com/primelogiclabio/service-monitor-ui
- The companion explainer: `docs/service-monitor-stack-guide.md`

**Licences**

- Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0
- MIT: https://opensource.org/license/mit
- BSD 3-clause: https://opensource.org/license/bsd-3-clause
- Eclipse Public License 2.0: https://www.eclipse.org/legal/epl-2.0/
- Eclipse Distribution License 1.0: https://www.eclipse.org/org/documents/edl-v10.php
- GNU LGPL 2.1: https://opensource.org/license/lgpl-2-1
- GNU GPL 2.0: https://opensource.org/license/gpl-2-0 , with the Classpath Exception: https://openjdk.org/legal/gplv2+ce.html
- SQLite's public-domain statement: https://www.sqlite.org/copyright.html

**Components named above**

- Spring Boot: https://spring.io/projects/spring-boot
- SQLite JDBC driver: https://github.com/xerial/sqlite-jdbc
- Flyway: https://github.com/flyway/flyway
- Nimbus JOSE+JWT: https://connect2id.com/products/nimbus-jose-jwt
- Angus Mail: https://eclipse-ee4j.github.io/angus-mail/
- gopsutil: https://github.com/shirou/gopsutil
- pro-bing: https://github.com/prometheus-community/pro-bing
- go-winio: https://github.com/microsoft/go-winio
- Svelte: https://svelte.dev
- Vite: https://vite.dev
- uPlot: https://github.com/leeoniya/uPlot
- WinSW (the one thing the installer may download): https://github.com/winsw/winsw
- Eclipse Temurin, one free Java runtime: https://adoptium.net/
- Keycloak, the sign-in provider tried with the hub (any OpenID Connect provider that issues JWT access tokens can be configured instead): https://www.keycloak.org
