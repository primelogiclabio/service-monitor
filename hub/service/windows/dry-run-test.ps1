#Requires -Version 5.1
<#
Checks install.ps1 -DryRun: it prints the plan, rejects bad input and changes nothing. Safe on any machine,
because it never installs; CI runs it on every pull request.

    .\dry-run-test.ps1 [-JarPath path-to-jar]
#>
[CmdletBinding()]
param([string]$JarPath)

$ErrorActionPreference = 'Stop'

$Name = 'svcmon-hub'
$install = Join-Path $PSScriptRoot 'install.ps1'
$jarArgs = @()
if ($JarPath) { $jarArgs = @('-JarPath', $JarPath) }
$script:Out = ''
$script:Code = 0

function Check([string]$What, [scriptblock]$Test) {
    if (-not (& $Test)) { throw "FAIL: $What`n$script:Out" }
    Write-Host "ok: $What"
}
function Has([string]$Text) { $script:Out.Contains($Text) }
# Runs install.ps1 in a process of its own, as an admin would, so exit codes and parameter errors are real.
function Run([string[]]$Arguments) {
    $script:Out = (& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $install @Arguments | Out-String)
    $script:Code = $LASTEXITCODE
}
# Everything the installer would create or change, as one comparable string.
function State {
    $svc = Get-Service -Name $Name -ErrorAction SilentlyContinue
    "service=$([bool]$svc) program=$(Test-Path (Join-Path $env:ProgramFiles $Name)) data=$(Test-Path (Join-Path $env:ProgramData $Name))"
}

$before = State

Write-Host '== the plan'
Run ($jarArgs + '-DryRun')
Check 'exits 0' { $script:Code -eq 0 }
Check 'says nothing has been changed' { Has 'Install plan (nothing has been changed yet)' }
Check 'shows the default port' { $script:Out -match 'Port\s+8080 \(default' }
Check 'shows the URL' { Has 'http://localhost:8080' }
Check 'lists numbered steps' { Has '    1. ' }
Check 'names the WinSW download and its checksum check' { (Has 'WinSW-x64.exe') -and (Has 'pinned SHA-256') }
Check 'names the service account' { Has 'NT SERVICE\svcmon-hub' }
Check 'says it is a dry run' { Has 'Dry run only' }

Write-Host '== a chosen port'
Run ($jarArgs + '-DryRun', '-Port', '9090')
Check 'shows the chosen port' { $script:Out -match 'Port\s+9090 \(-Port\)' }
Check 'plans the health check on it' { Has 'http://127.0.0.1:9090/api/v1/health' }

Write-Host '== bad input is refused'
Run ($jarArgs + '-DryRun', '-Port', '0')
Check '-Port 0 fails' { $script:Code -ne 0 }
Run ($jarArgs + '-DryRun', '-Port', '70000')
Check '-Port 70000 fails' { $script:Code -ne 0 }
Run ('-DryRun', '-JarPath', 'C:\nonexistent\hub.jar', '-JavaPath', (Join-Path $env:SystemRoot 'System32\cmd.exe'))
Check 'a missing jar and a non-Java exit 1' { $script:Code -eq 1 }
Check 'names the missing jar' { Has 'Jar not found: C:\nonexistent\hub.jar' }
Check 'names the java problem' { Has 'is not Java 21 or newer' }
Check 'lists them under one heading' { Has 'Cannot install yet:' }

Write-Host '== a port that is already taken is flagged'
$listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, 19095)
$listener.Start()
try {
    Run ($jarArgs + '-DryRun', '-Port', '19095')
    Check 'a dry run on the taken port exits 1' { $script:Code -eq 1 }
    Check 'the plan marks the port IN USE' { $script:Out -match 'Port\s+19095 \(-Port\) - IN USE by' }
    Check 'the plan says the install would stop' { Has 'Blocked: port 19095 is in use by' }
} finally {
    $listener.Stop()
}
Run ($jarArgs + '-DryRun', '-Port', '19095')
Check 'once it is released the port is reported free' { $script:Out -match 'Port\s+19095 \(-Port\) - free' }
Check 'and the dry run exits 0' { $script:Code -eq 0 }

Write-Host '== nothing was changed'
$after = State
Check "service and folders are as they were ($before)" { $before -eq $after }

Write-Host 'All checks passed.'
