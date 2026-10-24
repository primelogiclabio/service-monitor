#Requires -Version 5.1
<#
.SYNOPSIS
Installs the Service Monitor hub as a Windows service with WinSW. Safe to run again to upgrade.

.DESCRIPTION
Layout: program in $InstallDir (svcmon-hub.exe is the WinSW wrapper, with svcmon-hub.xml and the jar);
settings, database and logs in $DataDir. The service runs as the virtual account NT SERVICE\svcmon-hub and
starts at boot. An existing application.yml and database are never touched, except for its port line when you
pass -Port. Nothing is written to the registry except the service entry that Windows itself creates for every
service. Installing needs an elevated PowerShell; -DryRun does not.

.PARAMETER JarPath
The hub jar. Default: hub\target\svcmon-hub-*.jar in this repository.

.PARAMETER JavaPath
java.exe of a Java 21 or newer. Default: JAVA_HOME, then PATH.

.PARAMETER Port
HTTP port (default 8080 for a new install). On an existing install it changes server.port in application.yml;
without -Port the port already in that file is kept.

.PARAMETER WinSWPath
A copy of the pinned WinSW release to use instead of downloading it. It must match the pinned SHA-256.

.PARAMETER DryRun
Print what the install would do and change nothing. In an elevated console it then asks whether to go ahead
(answer y or n, or type a different port number); otherwise it only prints the plan.
#>
[CmdletBinding()]
param(
    [string]$JarPath,
    [string]$JavaPath,
    [ValidateRange(1, 65535)][int]$Port = 8080,
    [string]$InstallDir = (Join-Path $env:ProgramFiles 'svcmon-hub'),
    [string]$DataDir = (Join-Path $env:ProgramData 'svcmon-hub'),
    [string]$WinSWPath,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$ServiceName = 'svcmon-hub'
# WinSW 2.x, the self-contained x64 build (no .NET Framework needed; 64-bit Windows on ARM runs it emulated).
$WinSWVersion = 'v2.12.0'
$WinSWAsset = 'WinSW-x64.exe'
$WinSWSha256 = '05B82D46AD331CC16BDC00DE5C6332C1EF818DF8CEEFCD49C726553209B3A0DA'
$WinSWUrl = "https://github.com/winsw/winsw/releases/download/$WinSWVersion/$WinSWAsset"

$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Find-Jar {
    if ($JarPath) {
        if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw "Jar not found: $JarPath" }
        return (Resolve-Path -LiteralPath $JarPath).Path
    }
    $found = @(Get-ChildItem -Path (Join-Path $PSScriptRoot '..\..\target') -Filter 'svcmon-hub-*.jar' -ErrorAction SilentlyContinue)
    if ($found.Count -eq 0) { throw 'No jar found. Build it (cd hub; mvn -DskipTests package) or pass -JarPath.' }
    if ($found.Count -gt 1) { throw 'More than one jar in hub\target. Pick one with -JarPath.' }
    return $found[0].FullName
}

function Find-Java {
    $java = $JavaPath
    if (-not $java -and $env:JAVA_HOME) { $java = Join-Path $env:JAVA_HOME 'bin\java.exe' }
    if (-not $java) {
        $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
        if ($cmd) { $java = $cmd.Source }
    }
    if (-not $java -or -not (Test-Path -LiteralPath $java -PathType Leaf)) {
        throw 'java.exe not found. Install Java 21 or newer, or pass -JavaPath.'
    }
    $java = (Resolve-Path -LiteralPath $java).Path
    # java -version writes to stderr, so read it from a process of our own instead of the pipeline.
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $java
    $psi.Arguments = '-version'
    $psi.UseShellExecute = $false
    $psi.RedirectStandardError = $true
    $psi.RedirectStandardOutput = $true
    $proc = [System.Diagnostics.Process]::Start($psi)
    $text = $proc.StandardError.ReadToEnd() + $proc.StandardOutput.ReadToEnd()
    $proc.WaitForExit()
    if ($text -notmatch 'version "(\d+)' -or [int]$Matches[1] -lt 21) { throw "$java is not Java 21 or newer." }
    $script:JavaMajor = [int]$Matches[1]
    return $java
}

function Get-WinSW {
    param([string]$Target)
    $source = $WinSWPath
    $temp = $null
    if (-not $source) {
        $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("winsw-" + [guid]::NewGuid().ToString('N') + '.exe')
        Write-Host "Downloading $WinSWAsset $WinSWVersion from github.com/winsw/winsw ..."
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $WinSWUrl -OutFile $temp -UseBasicParsing
        $source = $temp
    } elseif (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "WinSW file not found: $source"
    }
    try {
        $actual = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
        if ($actual -ne $WinSWSha256) {
            throw "WinSW $WinSWAsset does not match the pinned SHA-256 (expected $WinSWSha256, got $actual); not installing it."
        }
        Copy-Item -LiteralPath $source -Destination $Target -Force
    } finally {
        if ($temp -and (Test-Path -LiteralPath $temp)) { Remove-Item -LiteralPath $temp -Force }
    }
}

function Wait-ServiceGone {
    for ($i = 0; $i -lt 30; $i++) {
        if (-not (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue)) { return }
        Start-Sleep -Seconds 1
    }
    throw "Service $ServiceName is still registered (marked for deletion?). Close Services and other tools that have it open, then run this again."
}

function Lock-Folder {
    param([string]$Path, [string]$ServiceRights)
    # SIDs, not names, so this works on any Windows language: S-1-5-18 is SYSTEM, S-1-5-32-544 is Administrators.
    & icacls.exe $Path /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' "NT SERVICE\${ServiceName}:(OI)(CI)$ServiceRights" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "icacls failed on $Path (exit $LASTEXITCODE)." }
}

function YamlQuote([string]$s) { "'" + $s.Replace("'", "''") + "'" }

# ---- 1. Look at the machine and the inputs. Nothing here changes anything. ----

$InstallDir = [System.IO.Path]::GetFullPath($InstallDir)
$DataDir = [System.IO.Path]::GetFullPath($DataDir)
$wrapper = Join-Path $InstallDir "$ServiceName.exe"
$conf = Join-Path $DataDir 'application.yml'
$db = Join-Path $DataDir 'data\svcmon.db'
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
$portGiven = $PSBoundParameters.ContainsKey('Port')

$existing = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
$confExists = Test-Path -LiteralPath $conf
$dbExists = Test-Path -LiteralPath $db
$confPort = $null
if ($confExists) {
    try {
        $m = [regex]::Match((Get-Content -LiteralPath $conf -Raw -Encoding UTF8), '(?m)^(?:  port|server\.port):\s*(\d+)')
        if ($m.Success) { $confPort = [int]$m.Groups[1].Value }
    } catch { }   # a dry run without rights to the file cannot see the port in it
}

# Sets PlanPort and PlanPortNote: -Port, else the port in the settings file, else 8080.
function Resolve-Port {
    if ($portGiven) {
        $script:PlanPort = $Port
        $script:PlanPortNote = if ($confExists -and $Port -ne $confPort) { '-Port; changes the port in the settings file' } else { '-Port' }
    } elseif ($confExists) {
        $script:PlanPort = if ($confPort) { $confPort } else { 8080 }
        $script:PlanPortNote = "kept from $conf"
    } else {
        $script:PlanPort = 8080
        $script:PlanPortNote = 'default; change it with -Port'
    }
}

# Returns what holds TCP port $p, or $null when it is free. The hub's own running service does not count on its
# own port: it is stopped before the new one starts. Binding is the test, because that is what the hub will do
# (and it also catches the port ranges Windows reserves), then the owner is looked up for the message.
function Get-PortOwner([int]$p) {
    if ($existing -and $existing.Status -eq 'Running' -and $confPort -eq $p) { return $null }
    # Windows lets a wildcard bind succeed next to a listener on one address, so look at the listeners first.
    try {
        $c = Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction Stop | Select-Object -First 1
        $proc = Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue
        if ($proc) { return "$($proc.ProcessName) (pid $($c.OwningProcess))" }
        return 'another program'
    } catch { }   # nothing listening (the cmdlet errors when it finds none), or the cmdlet is missing
    try {
        $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any, $p)
        $listener.ExclusiveAddressUse = $true
        $listener.Start()
        $listener.Stop()
        return $null
    } catch {
        return 'another program, or a port range Windows has reserved'
    }
}

$problems = @()
if (-not $isAdmin -and -not $DryRun) { $problems += 'run this from an elevated PowerShell (Run as administrator); -DryRun works without' }
$jar = $null
$java = $null
$script:JavaMajor = $null
try { $jar = Find-Jar } catch { $problems += $_.Exception.Message }
try { $java = Find-Java } catch { $problems += $_.Exception.Message }
Resolve-Port
$script:Busy = Get-PortOwner $script:PlanPort
# A dry run shows a busy port in the plan instead, so you can pick another at the prompt.
if ($script:Busy -and -not $DryRun) { $problems += "port $($script:PlanPort) is already in use by $($script:Busy); choose another with -Port" }
if ($problems.Count -gt 0) {
    Write-Host 'Cannot install yet:'
    $problems | ForEach-Object { Write-Host "  - $_" }
    exit 1
}

# ---- 2. The plan ----

function Show-Plan {
    if ($existing) { $mode = 'upgrade: the service is installed; its settings and data are kept' }
    elseif ($confExists -or $dbExists) { $mode = 'reinstall over settings and data left by an earlier install' }
    else { $mode = 'new install' }
    Resolve-Port
    $effective = $script:PlanPort
    $note = $script:PlanPortNote
    $script:Busy = Get-PortOwner $effective
    $status = if ($script:Busy) { "IN USE by $($script:Busy)" } else { 'free' }
    $script:n = 0
    function Step([string]$text) { $script:n++; Write-Host ("    {0}. {1}" -f $script:n, $text) }
    Write-Host 'Install plan (nothing has been changed yet)'
    Write-Host ("  {0,-8} {1}" -f 'Mode', $mode)
    Write-Host ("  {0,-8} {1}" -f 'Jar', "$jar -> $(Join-Path $InstallDir "$ServiceName.jar")")
    Write-Host ("  {0,-8} {1}" -f 'Java', "$java (version $script:JavaMajor)")
    Write-Host ("  {0,-8} {1}" -f 'Port', "$effective ($note) - $status")
    Write-Host ("  {0,-8} {1}" -f 'URL', "http://localhost:$effective")
    Write-Host '  Steps:'
    if ($existing) { Step "stop the running $ServiceName service and remove its registration (files and data stay)" }
    if ($WinSWPath) { Step "copy WinSW from $WinSWPath after checking it matches the pinned SHA-256" }
    else { Step "download $WinSWAsset $WinSWVersion (about 18 MB) from github.com/winsw/winsw, check it matches the pinned SHA-256 and save it as $wrapper" }
    Step "create $InstallDir (program) and $DataDir (settings, data, logs), and copy the jar into the first"
    if (-not $confExists) { Step "write $conf with port $effective and the data folder $(Join-Path $DataDir 'data')" }
    elseif ($effective -ne $confPort) { Step "keep $conf, changing only its port line to $effective" }
    else { Step "keep $conf as it is" }
    Step "write $(Join-Path $InstallDir "$ServiceName.xml") and register the service $ServiceName with WinSW (starts at boot, restarts after a crash, runs $java)"
    Step "run it as the virtual account NT SERVICE\$ServiceName (no password) and lock both folders to SYSTEM, Administrators and that account"
    Step "start the service and wait up to 60 s for http://127.0.0.1:$effective/api/v1/health"
    if ($dbExists) { Step 'keep the existing database and its admin token' }
    elseif (-not $confExists) { Step 'make an admin token for the new database: put it in the new settings file for the first start, check it works, print it once, remove it from the file' }
    else { Step 'no admin token is made here (existing settings file); the hub prints one to its log on first start' }
    Write-Host "  Registry: only the service entry HKLM\SYSTEM\CurrentControlSet\Services\$ServiceName that Windows creates for every service."
    Write-Host "  Not touched: the firewall (open port $effective yourself if agents connect from other machines), other services."
    if (-not $isAdmin) { Write-Host '  A real install needs an elevated PowerShell (Run as administrator).' }
    if ($script:Busy) { Write-Host "  Blocked: port $effective is in use by $($script:Busy), so the install would stop. Choose another with -Port." }
}

if ($DryRun) {
    Show-Plan
    $canAsk = $isAdmin -and [Environment]::UserInteractive -and -not [Console]::IsInputRedirected -and -not [Console]::IsOutputRedirected
    if (-not $canAsk) {
        Write-Host ''
        if ($script:Busy) {
            Write-Host 'Dry run only. The port is taken: choose another with -Port before installing.'
            exit 1
        }
        Write-Host 'Dry run only. Run again without -DryRun (elevated) to install.'
        exit 0
    }
    while ($true) {
        Write-Host ''
        $answer = (Read-Host 'Proceed with the real installation? [y/N], or type a port number to change it').Trim()
        if ($answer -match '^(y|yes)$') {
            if ($script:Busy) {
                Write-Host "Port $($script:PlanPort) is in use by $($script:Busy). Type another port number, or n to cancel."
                continue
            }
            break
        }
        if ($answer -eq '' -or $answer -match '^(n|no)$') { Write-Host 'Cancelled; nothing was changed.'; exit 0 }
        if ($answer -match '^\d+$' -and [int]$answer -ge 1 -and [int]$answer -le 65535) {
            $Port = [int]$answer
            $portGiven = $true
            Write-Host ''
            Show-Plan
        } else {
            Write-Host 'Please answer y, n, or a port number from 1 to 65535.'
        }
    }
    Write-Host ''
}

# ---- 3. The install. Everything below changes the machine. ----

# An older install: stop it and remove its registration; files and data stay.
if ($existing) {
    Write-Host "Stopping the existing $ServiceName service for the upgrade ..."
    if ($existing.Status -ne 'Stopped') { Stop-Service -Name $ServiceName -Force; $existing.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(60)) }
    if (Test-Path -LiteralPath $wrapper) { & $wrapper uninstall | Out-Null } else { & sc.exe delete $ServiceName | Out-Null }
    Wait-ServiceGone
}

New-Item -ItemType Directory -Force -Path $InstallDir, $DataDir, (Join-Path $DataDir 'data'), (Join-Path $DataDir 'logs') | Out-Null
Get-WinSW -Target $wrapper
Copy-Item -LiteralPath $jar -Destination (Join-Path $InstallDir "$ServiceName.jar") -Force

# Settings. A new database gets an admin token of ours in the new file for the first start only; we check it
# works, show it once and take it out again. A settings file that already exists is only ever edited for
# -Port, and only its port line.
$token = $null
if (Test-Path -LiteralPath $conf) {
    Write-Host "Kept existing $conf"
    $text = Get-Content -LiteralPath $conf -Raw -Encoding UTF8
    $m = [regex]::Match($text, '(?m)^(?:  port|server\.port):\s*(\d+)')
    if (-not $portGiven) {
        $Port = if ($m.Success) { [int]$m.Groups[1].Value } else { 8080 }
    } elseif (-not $m.Success -or [int]$m.Groups[1].Value -ne $Port) {
        $one = 1
        if ($m.Success) {
            $text = ([regex]'(?m)^(  port|server\.port):[ \t]*\d+').Replace($text, "`${1}: $Port", $one)
        } elseif ($text -match '(?m)^server:[ \t]*\r?$') {
            $text = ([regex]'(?m)^server:[ \t]*\r?$').Replace($text, "server:`r`n  port: $Port", $one)
        } else {
            $text = $text.TrimEnd() + "`r`nserver:`r`n  port: $Port`r`n"
        }
        [System.IO.File]::WriteAllText($conf, $text, $Utf8NoBom)
        Write-Host "Set the port to $Port in $conf"
    }
} else {
    $lines = @(
        "# Service Monitor hub settings. Every key in the README's configuration table can be set here;",
        "# restart the service after editing (Restart-Service $ServiceName).",
        'server:',
        "  port: $Port",
        'svcmon:',
        "  data-dir: $(YamlQuote (Join-Path $DataDir 'data'))"
    )
    if (-not (Test-Path -LiteralPath $db)) {
        $bytes = New-Object byte[] 32
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        $rng.GetBytes($bytes)
        $rng.Dispose()
        $token = 'svm_a_' + (($bytes | ForEach-Object { $_.ToString('x2') }) -join '')
        $lines += "  bootstrap-admin-token: $token"
    }
    [System.IO.File]::WriteAllText($conf, (($lines -join "`r`n") + "`r`n"), $Utf8NoBom)
}

# Service definition from the template.
$configLocation = 'file:' + ($DataDir.Replace('\', '/').TrimEnd('/')) + '/'
$xml = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'svcmon-hub.xml'))
$values = @{
    '@JAVA@'            = $java
    '@JAR@'             = Join-Path $InstallDir "$ServiceName.jar"
    '@DATA_DIR@'        = $DataDir
    '@CONFIG_LOCATION@' = $configLocation
}
foreach ($key in $values.Keys) { $xml = $xml.Replace($key, [System.Security.SecurityElement]::Escape($values[$key])) }
[System.IO.File]::WriteAllText((Join-Path $InstallDir "$ServiceName.xml"), $xml, $Utf8NoBom)

try {
    & $wrapper install
    if ($LASTEXITCODE -ne 0) { throw "svcmon-hub.exe install failed (exit $LASTEXITCODE)." }
    # Run as a virtual account of its own instead of SYSTEM; it needs no password.
    & sc.exe config $ServiceName obj= "NT SERVICE\$ServiceName" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "sc config failed (exit $LASTEXITCODE)." }
    Lock-Folder -Path $InstallDir -ServiceRights 'RX'
    Lock-Folder -Path $DataDir -ServiceRights 'M'

    Start-Service -Name $ServiceName
    $url = "http://127.0.0.1:$Port"
    $up = $false
    for ($i = 0; $i -lt 60 -and -not $up; $i++) {
        try { Invoke-RestMethod -Uri "$url/api/v1/health" -TimeoutSec 3 | Out-Null; $up = $true } catch { Start-Sleep -Seconds 1 }
    }
    if (-not $up) { throw "The hub did not answer on $url within 60 seconds; see the logs in $DataDir\logs." }

    if ($token) {
        $ok = $false
        for ($i = 0; $i -lt 10 -and -not $ok; $i++) {
            try { Invoke-RestMethod -Uri "$url/api/v1/hub" -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 3 | Out-Null; $ok = $true } catch { Start-Sleep -Seconds 1 }
        }
        if (-not $ok) { throw "The hub is up but did not accept its new admin token; see the logs in $DataDir\logs." }
    }
} finally {
    # Never leave the bootstrap token in the settings file, whatever happened.
    if ($token -and (Test-Path -LiteralPath $conf)) {
        $kept = Get-Content -LiteralPath $conf -Encoding UTF8 | Where-Object { $_ -notmatch '^  bootstrap-admin-token:' }
        [System.IO.File]::WriteAllText($conf, (($kept -join "`r`n") + "`r`n"), $Utf8NoBom)
    }
}

Write-Host ''
Write-Host "Service Monitor hub is running: $url  (service $ServiceName, starts at boot)"
Write-Host "  settings  $conf"
Write-Host "  database  $(Join-Path $DataDir 'data')"
Write-Host "  logs      $(Join-Path $DataDir 'logs')"
if ($token) {
    Write-Host ''
    Write-Host 'Admin token (shown once, store it somewhere safe; only its hash is kept):'
    Write-Host "  $token"
} elseif ($existing -and (Test-Path -LiteralPath $db)) {
    Write-Host 'Upgraded; the existing database and admin token are unchanged.'
} else {
    Write-Host 'No admin token was created by this run. A new database prints one to the hub log on first start'
    Write-Host "(search $(Join-Path $DataDir 'logs') for 'New admin token'); for an existing database keep the token you have."
}
