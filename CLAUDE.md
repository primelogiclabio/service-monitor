# service-monitor

A free, cross-platform monitor: a Java hub, a small Go agent and CLI, and a separate web dashboard. This file is the working agreement for anyone (human or Claude) changing the code. `README.md` is the short front page (stack and quick start), `docs/service-monitor-reference.md` is the full user guide and `docs/service-monitor-design.md` explains why it is built this way; read the design doc before changing architecture.

## Layout

| Path | What | Stack |
|---|---|---|
| `hub/` | The hub: scheduler, SQLite, REST API, tokens, alerts, optional UI hosting | Java 21, Spring Boot 4.1, Maven, Flyway |
| `agent/` | `svcmon`: the agent (service, CPU, memory, disk, network checks) and the CLI, one binary | Go 1.27, stdlib plus gopsutil, pro-bing, x/sys |
| `hub/service/` | Install and uninstall scripts for the hub as a service, with their dry-run and smoke tests, and `windows/build-package.ps1` for the Windows package (jar, scripts, WinSW) | Bash, PowerShell, WinSW, systemd |
| `docs/` | Reference (the full user guide), design document, stack guide, trust policy and the sign-in and access plan, each as Markdown and self-contained HTML (keep both in sync; the HTML of the reference, the guide, the plan and the policy is generated from their Markdown with `node docs/tools/md2html.mjs <in.md> <out.html>`, the policy with `docs/tools/policy-config.json` as a third argument; the design HTML is edited by hand). Pictures are in `docs/assets/`, made from a throwaway demo hub with made-up servers (`docs/tools/screenshots/README.md` says how); never put a screenshot of a real hub there. The trust policy states what the software does with data, the network and installs: change it in the same pull request as any change that adds an outbound connection, stored data, a download, a new dependency or a new licence | |
| `../service-monitor-ui` | The web dashboard: its own repository, `primelogiclabio/service-monitor-ui` | Svelte 5, Vite 6, uPlot |

Both repos are public, Apache-2.0, under the GitHub organisation `primelogiclabio`.

## Ideas that must not be broken

- **Agents only connect out** to the hub. Monitored servers open no inbound ports.
- **Someone lists what is monitored:** an admin in the hub, or the person setting up a server in its `checks.json` (on by default, `svcmon.agent-declared-checks: false` turns it off). Nothing is auto-discovered into monitoring; `svcmon agent services` only lists what exists.
- **TLS is terminated at a load balancer.** The hub speaks plain HTTP on the internal network and only the hub is exposed. Keep working behind `X-Forwarded-*`.
- **Secrets never leave the hub unmasked and never appear in errors or logs:** API tokens and session cookies (only SHA-256 hashes are stored), users' passwords (only PBKDF2 hashes; a name nobody has is not logged, it may be a password), alert webhook URLs, header values, signing secrets, the SMTP password. Updates that send a masked value back keep the stored one.
- **Validate strictly and reject unknown settings**, so a typo fails loudly. Treat names and messages from agents as untrusted (Slack markup is escaped, Discord mentions off, mail headers cannot be injected).
- **Everything is free and open source**, and the hub and agent must run on Windows, Linux and macOS.
- The hub is headless and API-first; the CLI and the dashboard are clients of the same API.

## Build and test

Run these from the folder shown. Windows is the main development machine, in PowerShell.

- Hub: `cd hub; mvn -B -ntp test` (Java 21; `mvn -B -ntp -DskipTests package` builds `hub/target/svcmon-hub-0.1.0-SNAPSHOT.jar`). Integration tests start the real hub on random ports with temp data folders; alert tests use a local HTTP receiver and a fake SMTP server.
- Agent and CLI: `cd agent` then `go vet ./...` and `go test ./... -count=1`. Go is **not on PATH**: call `F:\software\go\bin\go.exe` and set per process `GOPATH=F:\software\go-cache\gopath`, `GOCACHE=F:\software\go-cache\build`, `GOTOOLCHAIN=local` to keep caches off C:. Run `gofmt -l .` before committing (CI checks it; on Windows a CRLF working copy of an untouched file can show up in the list, which is not a real problem).
- UI (separate repo): `npm test`, `npm run build`; set `npm_config_cache=F:\software\npm-cache`.
- CI (`.github/workflows/ci.yml`) runs hub tests on Ubuntu and Windows, agent tests on Ubuntu, Windows and macOS, a gofmt check, a cross-compile of every platform, and an installer dry run (`hub/service/*/dry-run-test.*`) on Ubuntu and Windows. The aggregate check **CI passed** is required on `main`. A change that only touches Markdown, `docs/`, `LICENSE` or `NOTICE` skips those jobs (a "changed files" job decides); **CI passed** still runs and then requires them all to be skipped.
- A test that proves a fix should fail on the old code; check that when you fix a bug.
- Releases: a `v*` tag builds every file and a draft GitHub release (`.github/workflows/release.yml`, steps in `RELEASING.md`); the hub jar carries the dashboard (`hub/src/main/resources/ui`, git-ignored, filled by the release build) and its version comes from the tag. Rehearse with the workflow's manual run; `.github/release/smoke-hub-jar.sh` can be run by hand against any jar.

## Git and pull requests

- `main` is protected in both repos: a pull request is required (zero approvals, admins included), force pushes and deletion are blocked, and **CI passed** must be green. Never push to `main`; work on a branch, open a PR with `gh pr create`.
- **Do not merge a PR unless the person asked you to.** When asked: `gh pr merge <n> --squash --delete-branch` with a subject ending `(#n)`, then update local `main`.
- End commit messages with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`; end PR bodies with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- In PowerShell write the message to a file and use `git commit -F <file>` (a here-string passed as an argument is read as file names), and pass `--body-file` to `gh pr create`.
- Do not poll CI. Read the PR with the app's PR status tool; if nothing wakes you on success, say CI is still running and stop. Turn on Auto-fix only when asked.
- The git identity is set; do not change git config.

## Conventions

- Add tests with every change, in the style of the neighbouring tests. Keep comments for the non-obvious "why".
- Docs: change `docs/service-monitor-reference.md` (and `README.md` when the stack or quick start changes), `docs/service-monitor-design.md` and `docs/service-monitor-design.html` together; do not put test counts in prose that will go stale.
- New hub settings go in `application.yml` (commented examples), the configuration table in `docs/service-monitor-reference.md`, and `HubProperties`.
- Database changes are new Flyway migrations (`hub/src/main/resources/db/migration`); never edit an applied one.
- Prefer small, reviewable pull requests, one concern each.

## Working on this machine

- Windows 11, PowerShell. Java 21 at `F:\java\java21`, Maven 3.9, Node 20.11 (so Vite is pinned to 6), Go 1.27 at `F:\software\go`. Install large things on F:, not C:. Ask before installing software, installing a service, or doing anything that needs an elevated prompt; the shell is normally not elevated.
- The agent installs itself with `svcmon agent install` (Windows service through the Service Control Manager, systemd unit, launchd daemon). The Windows install has run for real on the main development machine (elevated, by the person) and the Linux install on an Ubuntu 26.04 server (as root; the unit has no `User=`); the macOS installer is compiled and untested.
- A Windows jar that is running is locked: run a demo from a copy of the jar, not from `hub/target`.
- To see the whole thing working: start the hub with `--svcmon.ui-dir=<ui repo>/dist`, then run the UI repo's `scripts/seed-demo.mjs --token <admin token> --live` against it. Only use that on a throwaway hub.
- The PowerShell tool swallows a bare `--` argument and cuts a pipeline into `Select-Object -First n` short (it can stop a command before it writes its files); redirect output to a file and read it instead.

## Known gaps

- People's sign-in is being built in phases (`docs/service-monitor-signin-plan.md`; keep its status table current). Done: strict settings, users with cookie sessions, the Settings screen, `users reset`, internal-only access (`svcmon.access.*`, `security/NetworkPolicy`; the `/agent/` calls are never refused for where they come from), the audit (`audit/AuditService`, migration V6; never put a request body, password, token or typed-in unknown name in it). Next: the gateway as a gate only. Where a request comes from may only refuse it, never grant anything. Never trust an identity header from a proxy.
- The multi-hub console role is not built. The dashboard has no alerts page yet (the API is ready).
- Container monitoring (Docker and Podman) is built as the `CONTAINER` check (state and health, `agent/internal/checks/container.go`) plus `svcmon agent containers`; the agent talks to the runtime's unix socket with GET requests only, and that must stay true (the socket is root). On Windows it dials Docker Desktop's named pipe (go-winio, `container_windows.go`). It has run against a fake runtime in tests and by hand against Docker Desktop on Windows (the lister), but not as an installed service, on Linux or against Podman. Not built: per-container CPU and memory. An agent that does not run as root needs the runtime's group (ties in with a future `--run-as`).
- The hub service installers (`hub/service/`: WinSW on Windows, systemd on Linux) are only dry-run in CI (the `dry-run-test` scripts, job "installer dry run"). Their real install/uninstall tests (`smoke-test` scripts) run only from the manually started "Installer smoke test" workflow, never on this machine: installing needs an elevated prompt, so do not install the service here without asking. `--dry-run` / `-DryRun` is safe to run here. Change the installers together with both test scripts. macOS has none.
- WinSW is never committed (`*.exe` is gitignored). `install.ps1` pins its version and SHA-256 once; it uses `-WinSWPath`, then a `WinSW-x64.exe` beside the script, then a download, and a copy with the wrong hash is an error. The manually started "Windows hub package" workflow runs `build-package.ps1` and only uploads the zip as a workflow artifact; there is no release process yet.
- Checks declared by the agent (`checks.json` beside `agent.json`) are built: agent `agent/internal/agent/declared.go`, hub `DeclaredChecksService` and migration V4; on unless `svcmon.agent-declared-checks` is set to false; `svcmon agent check add|rm|list` edits the file and the agent keeps the hub's last list in `assigned.json`; the agent's definition wins and the hub only keeps a check's on/off switch. Run against a throwaway hub and Docker Desktop, never on an installed service or the droplet. The dashboard does not show a check's `source` or lock the form of a declared check yet (the API answers an edit with 409). An agent that declares sends only its own server's checks and only ones that run on the agent; keep it that way.
- The macOS agent installer and its service checks have never run on a real system. Windows (Windows 11) and Linux (Ubuntu 26.04, systemd: the HTTP, CPU, memory, disk and service checks) have.
