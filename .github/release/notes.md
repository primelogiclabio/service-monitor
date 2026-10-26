## Service Monitor @VERSION@

The hub is one jar; the agent is one file. There is no code-signing certificate, so Windows and macOS may warn about an unknown publisher. Check a download against `SHA256SUMS`, and to see that this project's release workflow built it, run `gh attestation verify <file> --repo primelogiclabio/service-monitor`.

| File | What it is |
|---|---|
| `svcmon-hub-@VERSION@.jar` | The hub, with the dashboard inside. Needs Java 21 or newer: `java -jar svcmon-hub-@VERSION@.jar`. `java -jar svcmon-hub-@VERSION@.jar init` writes an `application.yml` with every setting commented out. |
| `svcmon-hub-@VERSION@-windows-x64.zip` | The same jar with the Windows service wrapper (WinSW) and the installer: unzip, run `install.ps1` from an elevated PowerShell (add `-DryRun` first). |
| `svcmon-hub-@VERSION@-linux.tar.gz` | The same jar with the systemd installer: unpack, run `sudo ./install.sh` (add `--dry-run` first). |
| `svcmon-agent-@VERSION@-<os>-<cpu>` | The agent and command line, one self-contained file per platform. Rename it to `svcmon` (`svcmon.exe` on Windows) and keep it where it will stay: the service runs it from there. Platforms: `windows-amd64.exe`, `windows-arm64.exe`, `linux-amd64`, `linux-arm64`, `darwin-amd64`, `darwin-arm64`, `freebsd-amd64`. On Linux and macOS run `chmod +x` on it. |
| `service-monitor-ui-@VERSION@-dist.zip` | The dashboard alone, for hosting it on another web server. The hub jar already has it inside. |
| `SHA256SUMS` | SHA-256 of every file above. |

Check a download:

```bash
sha256sum --check --ignore-missing SHA256SUMS      # Linux
shasum -a 256 --check --ignore-missing SHA256SUMS   # macOS
```

```powershell
(Get-FileHash .\svcmon-hub-@VERSION@.jar -Algorithm SHA256).Hash   # compare with the line in SHA256SUMS
```

How to use them is in the [README](https://github.com/primelogiclabio/service-monitor#readme).
