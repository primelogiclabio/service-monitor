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

The installer registers a service that starts at boot, waits until the hub answers, and prints the **admin token once**: keep it. Open <http://localhost:8080> (the port you chose) and sign in with the token; the hub's own `admin` account has a random password in the first start's log (`%ProgramData%\svcmon-hub\logs`, `journalctl -u svcmon-hub`). Settings are in `application.yml`; restart the service after changing it. Just trying it? `java -jar svcmon-hub-@VERSION@.jar` runs the hub without a service, and `... init` writes an `application.yml` with every setting commented out.

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
