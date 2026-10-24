# Run the hub as a service

Scripts that install the hub so it starts at boot, restarts if it crashes, and can be removed again. Both do the same job; run the one for your operating system from an elevated prompt. The hub needs Java 21 or newer, and a built jar (`cd hub; mvn -DskipTests package`) or one passed with `--jar` / `-JarPath`.

| | Linux (systemd) | Windows (WinSW) |
|---|---|---|
| Install | `sudo hub/service/linux/install.sh` | `powershell -ExecutionPolicy Bypass -File hub\service\windows\install.ps1` |
| Remove, keep data | `sudo hub/service/linux/uninstall.sh` | `powershell -ExecutionPolicy Bypass -File hub\service\windows\uninstall.ps1` |
| Remove and delete data | `... uninstall.sh --purge` | `... uninstall.ps1 -Purge` |
| Program | `/opt/svcmon-hub` | `%ProgramFiles%\svcmon-hub` (`svcmon-hub.exe` is the WinSW wrapper, with `svcmon-hub.xml` and the jar) |
| Settings | `/etc/svcmon-hub/application.yml` | `%ProgramData%\svcmon-hub\application.yml` |
| Database | `/var/lib/svcmon-hub` | `%ProgramData%\svcmon-hub\data` |
| Log | `journalctl -u svcmon-hub` | `%ProgramData%\svcmon-hub\logs` (rolled by WinSW) |
| Runs as | system user `svcmon-hub`, no login shell | virtual account `NT SERVICE\svcmon-hub`, no password |
| Options | `--jar PATH`, `--java PATH`, `--port N` | `-JarPath`, `-JavaPath`, `-Port`, `-InstallDir`, `-DataDir`, `-WinSWPath` |

## What the install does

1. Finds the jar and a Java 21+ (`--java`/`-JavaPath`, then `JAVA_HOME`, then `PATH`) and writes the absolute path into the service definition, because a service does not see your shell's `PATH`.
2. Creates the service user, the folders and a locked-down settings folder (only administrators, the service account and, on Linux, root can read it).
3. Writes `application.yml` with the port and data folder, **only if there is none**. An existing settings file and database are never changed, so running the install again is an upgrade.
4. Registers and starts the service, waits for `GET /api/v1/health`, and for a new database prints the **admin token once**. The token is put in the new settings file for the first start only, checked, and removed again, so it never reaches the service log; only its hash is stored. Lose it and start the hub once with `--svcmon.new-admin-token=true`.

Settings changes: edit `application.yml` (every key of the configuration table in the main README works there) and restart the service (`systemctl restart svcmon-hub` / `Restart-Service svcmon-hub`). If you move the data folder on Linux, also change `ReadWritePaths` in the installed unit.

## Windows and the registry

Every Windows service, WinSW's included, is registered by the Service Control Manager under `HKLM\SYSTEM\CurrentControlSet\Services\svcmon-hub`; no installer can avoid that. Nothing else is written: no MSI, no Add/Remove Programs entry, no settings in the registry (the settings are the YAML file). The uninstall removes the service key. Installing is the only step that needs an elevated prompt; day to day the hub needs no special rights.

The wrapper is [WinSW](https://github.com/winsw/winsw) v2.12.0 (`WinSW-x64.exe`, MIT license, about 18 MB; it is self-contained and needs no .NET Framework, and 64-bit Windows on ARM runs it emulated). `install.ps1` downloads that exact release from GitHub and refuses to use it unless its SHA-256 matches the value pinned in the script. For a machine without internet access download the file elsewhere and pass `-WinSWPath`. To move to a newer WinSW, change the version, file name and checksum at the top of `install.ps1` together, after checking the checksum against a copy you trust.

## Troubleshooting

- **The hub did not answer within 60 seconds.** Read the log (table above). Usual causes: the port is taken (`--port`/`-Port` for a new install, or edit `server.port`), or Java is too old.
- **Windows: access denied starting the service.** The service account must be able to read `java.exe` and its folder. A Java under a user profile or a restricted drive is the usual culprit; pass `-JavaPath` for a system-wide one.
- **Windows: "marked for deletion".** A tool such as Services or Task Manager has the service open. Close it and run the script again.
- **macOS** is not covered by these scripts.

## Tests

`linux/smoke-test.sh` and `windows/smoke-test.ps1` install the service on a throwaway machine, check it (health, the token, ownership, permissions, no token left behind), upgrade it, restart it, remove it with and without the data, and check that nothing is left. CI runs them on Ubuntu and Windows runners (job "hub service"). Do not run them on a machine that has a real `svcmon-hub` service; they refuse to start if one exists, but they will install and delete one.
