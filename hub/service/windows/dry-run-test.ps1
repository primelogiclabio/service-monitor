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
# Runs a script in a process of its own, as an admin would, so exit codes and parameter errors are real.
function RunScript([string]$Script, [string[]]$Arguments) {
    $script:Out = (& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $Script @Arguments | Out-String)
    $script:Code = $LASTEXITCODE
}
function Run([string[]]$Arguments) { RunScript $install $Arguments }
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
Check 'names where WinSW comes from and its checksum check' { (Has 'WinSW-x64.exe') -and (Has 'pinned SHA-256') }
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

Write-Host '== WinSW: -WinSWPath, then the copy beside the script, then a download'
# These run against throwaway copies of the scripts whose pinned SHA-256 is that of a dummy file, so the lookup order
# and the package can be tested without the 18 MB binary. The real pinned value is only used for the first check.
$work = Join-Path ([System.IO.Path]::GetTempPath()) ('svcmon-dryrun-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $work | Out-Null
try {
    $repo = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
    $dummyJar = Join-Path $work 'svcmon-hub-0.0.0-test.jar'
    $good = Join-Path $work 'good\WinSW-x64.exe'
    $bad = Join-Path $work 'bad\WinSW-x64.exe'
    New-Item -ItemType Directory -Force -Path (Split-Path $good), (Split-Path $bad) | Out-Null
    Set-Content -LiteralPath $dummyJar -Value 'not a real jar'
    Set-Content -LiteralPath $good -Value 'not the real WinSW'
    Set-Content -LiteralPath $bad -Value 'something else'
    $goodHash = (Get-FileHash -LiteralPath $good -Algorithm SHA256).Hash

    # A repository-shaped copy (<root>\hub\service\windows, the README, LICENSE and NOTICE) pinned to the dummy WinSW.
    function New-Tree([string]$Name) {
        $root = Join-Path $work $Name
        $dir = Join-Path $root 'hub\service\windows'
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        foreach ($f in 'install.ps1', 'uninstall.ps1', 'svcmon-hub.xml', 'build-package.ps1', 'WinSW-LICENSE.txt') {
            Copy-Item -LiteralPath (Join-Path $PSScriptRoot $f) -Destination $dir
        }
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\README.md') -Destination (Join-Path $root 'hub\service')
        Copy-Item -LiteralPath (Join-Path $repo 'LICENSE'), (Join-Path $repo 'NOTICE') -Destination $root
        $path = Join-Path $dir 'install.ps1'
        $text = [System.IO.File]::ReadAllText($path)
        $pinned = [regex]::Replace($text, "(?m)^(\`$WinSWSha256 = ')[0-9A-Fa-f]{64}(')", "`${1}$goodHash`${2}")
        if ($pinned -eq $text) { throw 'FAIL: could not pin the copy of install.ps1 to the dummy WinSW' }
        [System.IO.File]::WriteAllText($path, $pinned)
        return $dir
    }

    Run ($jarArgs + '-DryRun', '-WinSWPath', $bad)
    Check 'the real script refuses a WinSW that is not the pinned release' { ($script:Code -eq 1) -and (Has 'does not match the pinned SHA-256') }

    $dir = New-Tree 'lookup'
    $copy = Join-Path $dir 'install.ps1'
    $beside = Join-Path $dir 'WinSW-x64.exe'
    $plan = @('-DryRun', '-Port', '19096', '-JarPath', $dummyJar)

    RunScript $copy $plan
    Check 'with no copy given and none beside the script it plans the download' {
        ($script:Code -eq 0) -and (Has 'download WinSW-x64.exe v') -and (Has 'no -WinSWPath, and no WinSW-x64.exe beside this script')
    }

    Copy-Item -LiteralPath $good -Destination $beside
    RunScript $copy $plan
    Check 'a matching copy beside the script is used, without a download' {
        ($script:Code -eq 0) -and (Has 'from the copy beside this script') -and (Has 'nothing is downloaded') -and -not (Has 'download WinSW-x64.exe v')
    }

    Copy-Item -LiteralPath $bad -Destination $beside -Force
    RunScript $copy $plan
    Check 'a copy beside the script with the wrong checksum is an error' {
        ($script:Code -eq 1) -and (Has 'The WinSW-x64.exe beside this script') -and (Has 'does not match the pinned SHA-256') -and (Has 'Cannot install yet:')
    }
    Check 'and it is not replaced by a download' { -not (Has 'download WinSW-x64.exe v') }
    RunScript $copy ($plan + '-WinSWPath', $good)
    Check '-WinSWPath comes before the copy beside the script' { ($script:Code -eq 0) -and (Has 'from -WinSWPath') -and -not (Has 'beside this script (') }

    Copy-Item -LiteralPath $good -Destination $beside -Force
    RunScript $copy ($plan + '-WinSWPath', $bad)
    Check 'a -WinSWPath with the wrong checksum is an error, even with a good copy beside the script' {
        ($script:Code -eq 1) -and (Has 'The -WinSWPath file') -and (Has 'does not match the pinned SHA-256') -and -not (Has 'beside this script (')
    }
    RunScript $copy ($plan + '-WinSWPath', (Join-Path $work 'missing\WinSW-x64.exe'))
    Check 'a missing -WinSWPath file is an error' { ($script:Code -eq 1) -and (Has 'WinSW file not found') }

    Write-Host '== the jar beside the script'
    Copy-Item -LiteralPath $dummyJar -Destination $dir
    RunScript $copy @('-DryRun', '-Port', '19096')
    Check 'is used when -JarPath is not given' { ($script:Code -eq 0) -and (Has 'svcmon-hub-0.0.0-test.jar -> ') }
    Copy-Item -LiteralPath $dummyJar -Destination (Join-Path $dir 'svcmon-hub-0.0.1-test.jar')
    RunScript $copy @('-DryRun', '-Port', '19096')
    Check 'two jars beside the script are an error' { ($script:Code -eq 1) -and (Has 'More than one jar in') }

    Write-Host '== the Windows package'
    $outDir = Join-Path $work 'out'
    $zip = Join-Path $outDir 'svcmon-hub-0.0.0-test-windows-x64.zip'
    RunScript (Join-Path $dir 'build-package.ps1') @('-JarPath', $dummyJar, '-WinSWPath', $good, '-OutDir', $outDir)
    Check 'build-package.ps1 builds the zip' { ($script:Code -eq 0) -and (Test-Path -LiteralPath $zip) }
    Check 'and writes its checksum' { (Get-Content -LiteralPath "$zip.sha256" -Raw).StartsWith((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()) }
    $unpacked = Join-Path $work 'unpacked'
    Expand-Archive -LiteralPath $zip -DestinationPath $unpacked
    $pkg = Join-Path $unpacked 'svcmon-hub-0.0.0-test-windows-x64'
    foreach ($f in 'install.ps1', 'uninstall.ps1', 'svcmon-hub.xml', 'svcmon-hub-0.0.0-test.jar', 'WinSW-x64.exe', 'README.md', 'LICENSE', 'NOTICE', 'WinSW-LICENSE.txt') {
        Check "the package contains $f" { Test-Path -LiteralPath (Join-Path $pkg $f) -PathType Leaf }
    }
    Check 'it contains no test or build scripts' {
        -not ((Test-Path -LiteralPath (Join-Path $pkg 'dry-run-test.ps1')) -or (Test-Path -LiteralPath (Join-Path $pkg 'smoke-test.ps1')) -or (Test-Path -LiteralPath (Join-Path $pkg 'build-package.ps1')))
    }
    Check 'its WinSW is the checked file' { (Get-FileHash -LiteralPath (Join-Path $pkg 'WinSW-x64.exe') -Algorithm SHA256).Hash -eq $goodHash }
    RunScript (Join-Path $pkg 'install.ps1') @('-DryRun', '-Port', '19096')
    Check 'the unpacked install.ps1 plans with the jar and WinSW beside it' {
        ($script:Code -eq 0) -and (Has 'svcmon-hub-0.0.0-test.jar -> ') -and (Has 'from the copy beside this script') -and (Has 'nothing is downloaded')
    }
    $badOut = Join-Path $work 'out-bad'
    RunScript (Join-Path $dir 'build-package.ps1') @('-JarPath', $dummyJar, '-WinSWPath', $bad, '-OutDir', $badOut)
    Check 'a WinSW that is not the pinned release builds no package' { ($script:Code -ne 0) -and -not (Test-Path -LiteralPath $badOut) }
} finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '== nothing was changed'
$after = State
Check "service and folders are as they were ($before)" { $before -eq $after }

Write-Host 'All checks passed.'
