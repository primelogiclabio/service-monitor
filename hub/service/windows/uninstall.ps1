#Requires -Version 5.1
#Requires -RunAsAdministrator
<#
.SYNOPSIS
Removes the Service Monitor hub Windows service.

.DESCRIPTION
Stops and unregisters the service and deletes the program files. The settings, database and logs stay so a
later install picks up where this one left off; -Purge deletes them too, which cannot be undone.
The install folder and data folder are read from the installed service; the parameters are only for an
install that is already half removed.
#>
[CmdletBinding()]
param(
    [switch]$Purge,
    [string]$InstallDir,
    [string]$DataDir
)

$ErrorActionPreference = 'Stop'
$ServiceName = 'svcmon-hub'

$svc = Get-CimInstance -ClassName Win32_Service -Filter "Name='$ServiceName'" -ErrorAction SilentlyContinue
if ($svc -and -not $InstallDir) {
    # PathName is the wrapper's quoted full path, e.g. "C:\Program Files\svcmon-hub\svcmon-hub.exe".
    $InstallDir = Split-Path -Parent ($svc.PathName.Trim().Trim('"'))
}
if (-not $InstallDir) { $InstallDir = Join-Path $env:ProgramFiles 'svcmon-hub' }
$xmlFile = Join-Path $InstallDir "$ServiceName.xml"
if (-not $DataDir -and (Test-Path -LiteralPath $xmlFile)) {
    $node = ([xml](Get-Content -LiteralPath $xmlFile -Raw)).service.env | Where-Object { $_.name -eq 'SVCMON_DATA_DIR' }
    if ($node) { $DataDir = $node.value }
}
if (-not $DataDir) { $DataDir = Join-Path $env:ProgramData 'svcmon-hub' }
$wrapper = Join-Path $InstallDir "$ServiceName.exe"

if (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) {
    $s = Get-Service -Name $ServiceName
    if ($s.Status -ne 'Stopped') { Stop-Service -Name $ServiceName -Force; $s.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(60)) }
    if (Test-Path -LiteralPath $wrapper) { & $wrapper uninstall | Out-Null } else { & sc.exe delete $ServiceName | Out-Null }
    for ($i = 0; $i -lt 30 -and (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue); $i++) { Start-Sleep -Seconds 1 }
    if (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) {
        throw "Service $ServiceName is still registered (marked for deletion?). Close Services and other tools that have it open, then run this again."
    }
    Write-Host "Removed service $ServiceName"
} else {
    Write-Host "Service $ServiceName is not installed"
}

# Only our own files, so a shared folder given with -InstallDir loses nothing else.
foreach ($f in "$ServiceName.exe", "$ServiceName.xml", "$ServiceName.jar") {
    Remove-Item -LiteralPath (Join-Path $InstallDir $f) -Force -ErrorAction SilentlyContinue
}
if ((Test-Path -LiteralPath $InstallDir) -and -not (Get-ChildItem -LiteralPath $InstallDir -Force)) {
    Remove-Item -LiteralPath $InstallDir -Force
}
Write-Host "Removed the program files from $InstallDir"

if ($Purge) {
    foreach ($p in 'application.yml', 'data', 'logs') {
        Remove-Item -LiteralPath (Join-Path $DataDir $p) -Recurse -Force -ErrorAction SilentlyContinue
    }
    if ((Test-Path -LiteralPath $DataDir) -and -not (Get-ChildItem -LiteralPath $DataDir -Force)) {
        Remove-Item -LiteralPath $DataDir -Force
    }
    Write-Host "Deleted the settings, database and logs in $DataDir"
} else {
    Write-Host "Kept the settings, database and logs in $DataDir; add -Purge to delete them"
}
