#Requires -Version 5.1
#Requires -RunAsAdministrator
<#
Installs the hub as a service on this machine, checks it, upgrades it, then removes it. CI runs it on a
disposable runner; do not run it on a machine that already has a svcmon-hub service or data.

    .\smoke-test.ps1 [-JarPath path-to-jar]
#>
[CmdletBinding()]
param([string]$JarPath)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Name = 'svcmon-hub'
$Port = 18080
$Url = "http://127.0.0.1:$Port"
$InstallDir = Join-Path $env:ProgramFiles $Name
$DataDir = Join-Path $env:ProgramData $Name
$install = Join-Path $PSScriptRoot 'install.ps1'
$uninstall = Join-Path $PSScriptRoot 'uninstall.ps1'
$jarArgs = @{}
if ($JarPath) { $jarArgs.JarPath = $JarPath }

function Check([string]$What, [scriptblock]$Test) {
    if (-not (& $Test)) { throw "FAIL: $What" }
    Write-Host "ok: $What"
}
function Healthy { try { Invoke-RestMethod "$Url/api/v1/health" -TimeoutSec 3 | Out-Null; $true } catch { $false } }
function TokenWorks([string]$t) {
    try { Invoke-RestMethod "$Url/api/v1/hub" -Headers @{ Authorization = "Bearer $t" } -TimeoutSec 3 | Out-Null; $true } catch { $false }
}
function Capture([scriptblock]$Run) { (& $Run *>&1 | Out-String) }

if (Get-Service -Name $Name -ErrorAction SilentlyContinue) { throw "$Name is already installed here; refusing to touch it" }

try {
    Write-Host '== fresh install'
    $out = Capture { & $install @jarArgs -Port $Port }
    Write-Host $out
    $token = [regex]::Match($out, 'svm_a_[0-9a-f]{64}').Value
    Check 'install printed an admin token' { $token }
    $svc = Get-CimInstance Win32_Service -Filter "Name='$Name'"
    Check 'service is running' { $svc.State -eq 'Running' }
    Check 'service starts at boot' { $svc.StartMode -eq 'Auto' }
    Check 'runs as its own virtual account, not SYSTEM' { $svc.StartName -eq "NT SERVICE\$Name" }
    Check 'health answers on the chosen port' { Healthy }
    Check 'the printed token is accepted' { TokenWorks $token }
    Check 'the token is not left in the settings file' { -not (Select-String -Path "$DataDir\application.yml" -Pattern 'bootstrap-admin-token' -Quiet) }
    Check 'database is in the data folder' { Test-Path "$DataDir\data\svcmon.db" }
    Check 'the hub wrote a log' { @(Get-ChildItem "$DataDir\logs" -Filter '*.log').Count -gt 0 }
    Check 'the token is not in the logs' { -not (Select-String -Path "$DataDir\logs\*" -Pattern $token -Quiet) }
    Check 'ordinary users cannot read the settings' {
        -not ((Get-Acl "$DataDir\application.yml").Access | Where-Object { $_.IdentityReference.Value -match 'Users|Everyone' })
    }

    Write-Host '== a dry run now plans an upgrade and changes nothing'
    $plan = Capture { & $install @jarArgs -DryRun }
    Check 'the plan says upgrade' { $plan -match 'Mode\s+upgrade' }
    Check 'the plan keeps the port from the settings file' { $plan -match "Port\s+$Port \(kept from" }
    Check 'the service is still running' { (Get-Service -Name $Name).Status -eq 'Running' }

    Write-Host '== upgrade (run again)'
    $out = Capture { & $install @jarArgs }
    Write-Host $out
    Check 'upgrade keeps the old port from the settings file' { Healthy }
    Check 'upgrade keeps the admin token valid' { TokenWorks $token }
    Check 'upgrade prints no second token' { $out -notmatch 'svm_a_' }

    Write-Host '== change the port'
    $oldUrl = $Url
    $Port = 18081
    $Url = "http://127.0.0.1:$Port"
    Write-Host (Capture { & $install @jarArgs -Port $Port })
    Check 'health answers on the new port' { Healthy }
    Check 'the old port is closed' { try { Invoke-RestMethod "$oldUrl/api/v1/health" -TimeoutSec 3 | Out-Null; $false } catch { $true } }
    Check 'the settings file has the new port' { Select-String -Path "$DataDir\application.yml" -Pattern '^  port: 18081$' -Quiet }
    Check 'the admin token is still valid on the new port' { TokenWorks $token }

    Write-Host '== restart'
    Restart-Service -Name $Name
    for ($i = 0; $i -lt 60 -and -not (Healthy); $i++) { Start-Sleep -Seconds 1 }
    Check 'healthy after a restart' { Healthy }
    Check 'token still valid after a restart' { TokenWorks $token }

    Write-Host '== uninstall, keeping data'
    & $uninstall
    Check 'service is gone' { -not (Get-Service -Name $Name -ErrorAction SilentlyContinue) }
    Check 'program folder is gone' { -not (Test-Path $InstallDir) }
    Check 'the hub no longer answers' { -not (Healthy) }
    Check 'settings are kept' { Test-Path "$DataDir\application.yml" }
    Check 'database is kept' { Test-Path "$DataDir\data\svcmon.db" }

    Write-Host '== reinstall over kept data, then purge'
    $out = Capture { & $install @jarArgs }
    Write-Host $out
    Check 'reinstall prints no new token' { $out -notmatch 'svm_a_' }
    Check 'reinstall keeps the old token valid' { TokenWorks $token }
    & $uninstall -Purge
    Check 'data folder is deleted' { -not (Test-Path $DataDir) }
    Check 'service is gone' { -not (Get-Service -Name $Name -ErrorAction SilentlyContinue) }

    Write-Host 'All checks passed.'
} catch {
    Write-Host $_
    if (Test-Path "$DataDir\logs") {
        Get-ChildItem "$DataDir\logs" | ForEach-Object { Write-Host "--- $($_.Name)"; Get-Content $_.FullName -Tail 40 }
    }
    throw
}
