## Service Monitor @VERSION@

The hub is one jar; the agent is one file. There is no code-signing certificate, so Windows and macOS may warn about an unknown publisher. Check a download against `SHA256SUMS`, and to see that this project's release workflow built it, run `gh attestation verify <file> --repo primelogiclabio/service-monitor`.

## Install

Hub first, then an agent on each server you want to watch. The hub needs Java 21 or newer; the agent needs nothing.

**1. The hub.** Download `svcmon-hub-@VERSION@-windows-x64.zip` (or `svcmon-hub-@VERSION@-linux.tar.gz`) and `SHA256SUMS`, and check the download (see *Check a download* below).

```powershell
# Windows: unzip, then in an elevated PowerShell inside the folder
.\install.ps1 -DryRun     # shows the plan and changes nothing
.\install.ps1             # -JavaPath, -Port, -InstallDir and -DataDir change the defaults
```

```bash
# Linux: unpack, then
sudo ./install.sh --dry-run
sudo ./install.sh         # --java, --port
```

The installer registers a service that starts at boot, waits until the hub answers, and prints an **admin token once**, for the command line: keep it. Open <http://localhost:8080> (the port you chose) from the same network and sign in as `admin` with the password `admin`; the hub then asks you to choose a new password before anything else. That first password works only from the internal network. Settings are in `application.yml`; restart the service after changing it. Just trying it? `java -jar svcmon-hub-@VERSION@.jar` runs the hub without a service, and `... init` writes an `application.yml` with every setting commented out.

**2. An agent on each server.** In the dashboard open *Add server* to get an enrollment token. Download the agent for the server's platform, check it, rename it to `svcmon` (`svcmon.exe` on Windows), put it where it will stay (the service runs it from there), run `chmod +x` on Linux and macOS, then as Administrator or root:

```bash
svcmon agent install --hub http://your-hub:8080 --token svm_e_...
```

**3. Say what to watch**, on that server, with no hub login (nothing is monitored on its own):

```bash
svcmon agent services --filter nginx
svcmon agent check add service nginx.service
svcmon agent check add disk / --warn 80 --crit 90
```

## Upgrade

An upgrade keeps the hub's database, settings and tokens, and each agent's `agent.json`, `checks.json` and `assigned.json`; nothing is enrolled again. Do the hub first, then the agents. Copy the hub's data folder first if you want a way back.

**The hub.** Unpack the new package and run the installer again with the same options as the first time (`-InstallDir`, `-DataDir`, `--port` when you set them). The dry run says "upgrade" and lists what is kept; the real run stops the service, replaces the jar and starts it again, and the database is migrated at start. The dashboard is inside the jar, so it is upgraded too; if your `application.yml` still has a `ui-dir:` line the hub serves that older folder instead, so remove the line. Check with `svcmon hub` or `GET /api/v1/hub`.

**From 0.1 (people signed in with a token or the local admin account).** People now sign in as users. The local admin account becomes the user `admin` with the same password, and its old sign-ins end. Old settings stop the hub with a message naming what replaced them, also when they come from environment variables: `svcmon.local-admin.*`, `svcmon.jwt.*` (a sign-in provider's tokens are no longer accepted) and `svcmon.ui.proxy.*` (the dashboard always signs in with hub accounts). Remove them. A sign-in gateway in front of the hub becomes a gate only: see "Behind a sign-in gateway" in the reference. The hub now keeps an audit of sign-ins (30 days) and changes (365 days), which administrators read in Settings > Activity.

**From 0.2.1.** Nothing to do. The sign-in page looks the same from everywhere: there is no refusal screen, and a sign-in refused for where the browser is says so like a wrong password, without saying whether the hub counts the browser as inside or outside its internal network. `GET /api/v1/session` answers `network` and `address` only to someone signed in, and no longer has `signInAllowed`, `signInRefusal` or the provider's `allowed`. Settings > Access still shows how the hub sees the browser to people signed in.

**From 0.2.0.** Nothing to do. Sign-in with Keycloak works now: Keycloak refused 0.2.0's proof (the sign-in ended with `provider_refused` and the hub logged `400 invalid_client`), because it had no issue time and lasted five minutes. The hub's proof now lasts a minute and carries its issue time, and the hub's log says why when a provider refuses it.

**From 0.1.0.** Nothing to do. The hub can now sign people in with an identity provider (OpenID Connect: Keycloak, Entra ID, Okta, Auth0 and others), with its own key pair instead of a client secret; it stays off until `svcmon.oidc.issuer` is set, and the database gains its columns at start (migration V7). With a provider, people outside the internal network sign in with it only. See "Sign-in with an identity provider" in the reference.

**A hub that people reach from outside the internal network** refuses them until `svcmon.access.allow-outside: true` is set in `application.yml`, and then lets them sign in over HTTPS only. The proxy in front of it must send `X-Forwarded-For` and `X-Forwarded-Proto`. The command line's API tokens work from inside only. Agents are not affected.

**An agent.** Do not run `agent install` again. Stop the service, replace the program file with the new one (renamed to `svcmon`), start it:

```powershell
# Windows, elevated PowerShell
Stop-Service svcmon-agent
Copy-Item .\svcmon-agent-@VERSION@-windows-amd64.exe C:\path\to\the\installed\svcmon.exe -Force
Start-Service svcmon-agent
```

```bash
# Linux, as root
systemctl stop svcmon-agent
cp svcmon-agent-@VERSION@-linux-amd64 /path/to/the/installed/svcmon
systemctl start svcmon-agent
```

A stop of a few seconds is far below the 90 seconds before the hub shows a server offline. Check with `svcmon version`. More in the [README](https://github.com/primelogiclabio/service-monitor#readme) and its [reference](https://github.com/primelogiclabio/service-monitor/blob/main/docs/service-monitor-reference.md#upgrading).

## Files

GitHub adds two "Source code" archives to every release; they are not needed to install.

| File | What it is |
|---|---|
| `svcmon-hub-@VERSION@.jar` | The hub, with the dashboard inside. Needs Java 21 or newer: `java -jar svcmon-hub-@VERSION@.jar`. |
| `svcmon-hub-@VERSION@-windows-x64.zip` | The same jar with the Windows service wrapper (WinSW) and the installer. |
| `svcmon-hub-@VERSION@-linux.tar.gz` | The same jar with the systemd installer. |
| `svcmon-agent-@VERSION@-<os>-<cpu>` | The agent and command line, one self-contained file per platform: `windows-amd64.exe`, `windows-arm64.exe`, `linux-amd64`, `linux-arm64`, `darwin-amd64`, `darwin-arm64`, `freebsd-amd64`. |
| `service-monitor-ui-@VERSION@-dist.zip` | The dashboard alone, for hosting it on another web server. The hub jar already has it inside. |
| `SHA256SUMS` | SHA-256 of every file above. |

## Check a download

```bash
sha256sum --check --ignore-missing SHA256SUMS      # Linux
shasum -a 256 --check --ignore-missing SHA256SUMS   # macOS
```

```powershell
(Get-FileHash .\svcmon-hub-@VERSION@-windows-x64.zip -Algorithm SHA256).Hash   # compare with the line in SHA256SUMS
```
