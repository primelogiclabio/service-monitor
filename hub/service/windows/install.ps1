#Requires -Version 5.1
#Requires -RunAsAdministrator
<#
.SYNOPSIS
Installs the Service Monitor hub as a Windows service with WinSW. Safe to run again to upgrade.

.DESCRIPTION
Layout: program in $InstallDir (svcmon-hub.exe is the WinSW wrapper, with svcmon-hub.xml and the jar);
settings, database and logs in $DataDir. The service runs as the virtual account NT SERVICE\svcmon-hub and
starts at boot. An existing application.yml and database are never touched. Nothing is written to the
registry except the service entry that Windows itself creates for every service.

.PARAMETER JarPath
The hub jar. Default: hub\target\svcmon-hub-*.jar in this repository.

.PARAMETER JavaPath
java.exe of a Java 21 or newer. Default: JAVA_HOME, then PATH.

.PARAMETER Port
HTTP port (default 8080 for a new install). On an existing install it changes server.port in application.yml;
without -Port the port already in that file is kept.

.PARAMETER WinSWPath
A copy of the pinned WinSW release to use instead of downloading it. It must match the pinned SHA-256.
#>
[CmdletBinding()]
param(
    [string]$JarPath,
    [string]$JavaPath,
    [ValidateRange(1, 65535)][int]$Port = 8080,
    [string]$InstallDir = (Join-Path $env:ProgramFiles 'svcmon-hub'),
    [string]$DataDir = (Join-Path $env:ProgramData 'svcmon-hub'),
    [string]$WinSWPath
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

$jar = Find-Jar
$java = Find-Java
$InstallDir = [System.IO.Path]::GetFullPath($InstallDir)
$DataDir = [System.IO.Path]::GetFullPath($DataDir)
$wrapper = Join-Path $InstallDir "$ServiceName.exe"
$conf = Join-Path $DataDir 'application.yml'
$db = Join-Path $DataDir 'data\svcmon.db'

# An older install: stop it and remove its registration; files and data stay.
$existing = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
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
    if (-not $PSBoundParameters.ContainsKey('Port')) {
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
