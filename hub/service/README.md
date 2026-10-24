# Run the hub as a service

Scripts that install the hub so it starts at boot, restarts if it crashes, and can be removed again. Both do the same job; run the one for your operating system from an elevated prompt (a dry run needs none). The hub needs Java 21 or newer, and a built jar (`cd hub; mvn -DskipTests package`) or one passed with `--jar` / `-JarPath`. On Windows you can instead use the [package](#windows-package-no-download) that has the jar and the service wrapper in it.

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
| Options | `--jar PATH`, `--java PATH`, `--port N`, `--dry-run` | `-JarPath`, `-JavaPath`, `-Port`, `-InstallDir`, `-DataDir`, `-WinSWPath`, `-DryRun` |

## See the plan first (dry run)

`--dry-run` / `-DryRun` prints what the install would do and changes nothing: the mode (new install, upgrade, or reinstall over kept data), the jar and Java it found, the port and URL, every step in order (user, folders, files, the service, the admin token), and what it does not touch. It also works without root or an elevated prompt, so you can read the plan on any machine; a missing jar, a Java older than 21 or a bad port are listed together as problems instead.

It also checks that the port is free. The plan marks the Port line `free` or `IN USE by <program> (pid N)` and, if it is taken, ends with a `Blocked:` line and exits 1. A real install refuses to start on a taken port and lists it with any other problem, instead of timing out after 60 seconds. The hub's own running service does not count on its own port, so an upgrade is not blocked by itself. At the prompt, `y` is refused while the port is taken, and typing another port number checks that one.

In an interactive root or elevated session the dry run then asks `Proceed with the real installation? [y/N], or type a port number to change it`. `y` installs exactly what the plan showed, `n` (or Enter) cancels, and a number changes the port and prints the plan again. Without `--dry-run` the installer does not ask anything, so scripts and automation are unaffected.

```bash
sudo hub/service/linux/install.sh --dry-run --port 9090
```

```powershell
powershell -ExecutionPolicy Bypass -File hub\service\windows\install.ps1 -DryRun -Port 9090
```

## What the install does

1. Finds the jar (on Windows `-JarPath`, then the one beside the script, then `hub\target`) and a Java 21+ (`--java`/`-JavaPath`, then `JAVA_HOME`, then `PATH`) and writes the absolute path into the service definition, because a service does not see your shell's `PATH`.
2. Creates the service user, the folders and a locked-down settings folder (only administrators, the service account and, on Linux, root can read it).
3. Writes `application.yml` with the port and data folder, **only if there is none**. An existing database is never changed, and an existing settings file only changes its port line when you pass a new port, so running the install again is an upgrade.
4. Registers and starts the service, waits for `GET /api/v1/health`, and for a new database prints the **admin token once**. The token is put in the new settings file for the first start only, checked, and removed again, so it never reaches the service log; only its hash is stored. Lose it and start the hub once with `--svcmon.new-admin-token=true`.

The port is `8080` unless you choose another: pass `--port N` / `-Port N` on the first install, or run the install again with a different value to change it later (it edits only the `server.port` line of an existing `application.yml` and restarts the service). Without the option the port already in the file is kept. The scripts do not open any firewall port; allow the hub's port from your load balancer and the internal network yourself.

Settings changes: edit `application.yml` (every key of the configuration table in the main README works there) and restart the service (`systemctl restart svcmon-hub` / `Restart-Service svcmon-hub`). Once it is up the hub logs a start-up summary (address, database, alert channels, and the checks and servers it watches) to the journal or `logs` folder. If you move the data folder on Linux, also change `ReadWritePaths` in the installed unit.

## Windows and the registry

Every Windows service, WinSW's included, is registered by the Service Control Manager under `HKLM\SYSTEM\CurrentControlSet\Services\svcmon-hub`; no installer can avoid that. Nothing else is written: no MSI, no Add/Remove Programs entry, no settings in the registry (the settings are the YAML file). The uninstall removes the service key. Installing is the only step that needs an elevated prompt; day to day the hub needs no special rights.

The wrapper is [WinSW](https://github.com/winsw/winsw) v2.12.0 (`WinSW-x64.exe`, MIT license, about 18 MB; it is self-contained and needs no .NET Framework, and 64-bit Windows on ARM runs it emulated). `install.ps1` takes it from, in this order: the file given with `-WinSWPath`; a `WinSW-x64.exe` beside the script (the [Windows package](#windows-package-no-download) has one); otherwise it downloads that exact release from GitHub, which is what running from a source checkout does. Whichever it uses must match the SHA-256 pinned in the script. A copy that does not match is an error that names both checksums; the installer never replaces it with a download. The dry run's WinSW step says which of the three it will use. To move to a newer WinSW, change the version, file name and checksum at the top of `install.ps1` together, after checking the checksum against a copy you trust; `build-package.ps1` reads them from there.

## Windows package (no download)

The "Windows hub package" workflow builds `svcmon-hub-<version>-windows-x64.zip` and keeps it as a workflow artifact for 14 days, next to a `.sha256` file. It runs only when someone starts it from the Actions tab and publishes nothing; there is no release yet. Unzip it anywhere, open an elevated PowerShell in the folder and run `.\install.ps1` (add `-DryRun` first). The folder holds the hub jar, `install.ps1`, `uninstall.ps1`, `svcmon-hub.xml`, `WinSW-x64.exe`, this README, `LICENSE`, `NOTICE` and WinSW's license text (`WinSW-LICENSE.txt`). The installer finds the jar and WinSW beside itself, so the install needs no internet access; only Java 21 is still yours to provide. All options of the table above work the same.

WinSW is not stored in the git repository. The workflow downloads it, checks the SHA-256 pinned in `install.ps1` and stops if it does not match. To build the same package on your own machine run `hub\service\windows\build-package.ps1` after `cd hub; mvn -DskipTests package` (the zip lands in `dist`; `-WinSWPath` uses a copy you already have instead of downloading it). The package is not signed.

## Troubleshooting

- **The hub did not answer within 60 seconds.** Read the log (table above). The usual cause, a taken port, is caught before installing (see the dry run), unless something took the port after the check; run the install again with `--port`/`-Port` to pick another. Java older than 21 is also caught first.
- **Windows: access denied starting the service.** The service account must be able to read `java.exe` and its folder. A Java under a user profile or a restricted drive is the usual culprit; pass `-JavaPath` for a system-wide one.
- **Windows: "marked for deletion".** A tool such as Services or Task Manager has the service open. Close it and run the script again.
- **macOS** is not covered by these scripts.

## Tests

CI (the "installer dry run" job on Ubuntu and Windows runners, part of **CI passed**) runs `linux/dry-run-test.sh` and `windows/dry-run-test.ps1`: the plan is printed, bad input is refused with every problem named, the prompt can be cancelled and re-planned with another port, and nothing on the machine changes. On Windows it also checks where WinSW comes from (`-WinSWPath`, then the copy beside the script, then the download; a copy with the wrong checksum is refused, never replaced by a download) and builds a package from a dummy WinSW to check what it contains and that its installer plans from the unpacked folder. CI never installs the service.

`linux/smoke-test.sh` and `windows/smoke-test.ps1` do the real thing on a throwaway machine: install (on Linux through the dry-run prompt), check (health, the token, ownership, permissions, no token left behind), upgrade, change the port, restart, remove with and without the data, and check that nothing is left. On Windows the reinstall at the end comes from a freshly built package, so that route is exercised for real too. They run only when someone starts the "Installer smoke test" workflow by hand from the Actions tab, for example after changing the installers. Do not run them on a machine that has a real `svcmon-hub` service; they refuse to start if one exists, but they will install and delete one.
