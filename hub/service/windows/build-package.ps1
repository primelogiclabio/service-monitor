#Requires -Version 5.1
<#
.SYNOPSIS
Builds the Windows hub package: a zip with the hub jar, the installer scripts and WinSW, so installing the hub as a
service needs no download.

.DESCRIPTION
The zip holds one folder, svcmon-hub-<version>-windows-x64, with install.ps1, uninstall.ps1, svcmon-hub.xml, the hub
jar, WinSW-x64.exe, README.md, LICENSE, NOTICE and WinSW-LICENSE.txt. install.ps1 finds the jar and WinSW beside
itself. The WinSW version, file name and SHA-256 are read from install.ps1, so they are pinned in one place; the file
is downloaded (or taken from -WinSWPath) and must match that SHA-256 or nothing is built. WinSW is never committed to
the repository. The "Windows hub package" workflow runs this on a GitHub runner. Nothing is installed or published.

.PARAMETER JarPath
The hub jar. Default: the one svcmon-hub-*.jar in hub\target (cd hub; mvn -DskipTests package).

.PARAMETER WinSWPath
A copy of the pinned WinSW release to package instead of downloading it. It must match the pinned SHA-256.

.PARAMETER OutDir
Where the zip, its .sha256 file and the staging folder go. Default: dist in the repository (ignored by git).
#>
[CmdletBinding()]
param(
    [string]$JarPath,
    [string]$WinSWPath,
    [string]$OutDir = (Join-Path $PSScriptRoot '..\..\..\dist')
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$repo = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$OutDir = [System.IO.Path]::GetFullPath($OutDir)

# The pin lives in install.ps1 only; read it from there instead of repeating it.
$installText = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'install.ps1'))
function Get-Pin([string]$Name) {
    $m = [regex]::Match($installText, '(?m)^\$' + $Name + " = '([^']+)'")
    if (-not $m.Success) { throw "Could not read `$$Name from install.ps1." }
    $m.Groups[1].Value
}
$WinSWVersion = Get-Pin 'WinSWVersion'
$WinSWAsset = Get-Pin 'WinSWAsset'
$WinSWSha256 = Get-Pin 'WinSWSha256'

if ($JarPath) {
    if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw "Jar not found: $JarPath" }
    $jar = Get-Item -LiteralPath $JarPath
} else {
    $found = @(Get-ChildItem -Path (Join-Path $repo 'hub\target') -Filter 'svcmon-hub-*.jar' -ErrorAction SilentlyContinue)
    if ($found.Count -ne 1) { throw "Expected exactly one svcmon-hub-*.jar in hub\target (found $($found.Count)); build it with 'cd hub; mvn -DskipTests package' or pass -JarPath." }
    $jar = $found[0]
}
$version = [regex]::Match($jar.BaseName, '^svcmon-hub-(.+)$').Groups[1].Value
if (-not $version) { throw "Cannot read the version from the jar name $($jar.Name); it must look like svcmon-hub-<version>.jar." }

$temp = $null
if ($WinSWPath) {
    if (-not (Test-Path -LiteralPath $WinSWPath -PathType Leaf)) { throw "WinSW file not found: $WinSWPath" }
    $winsw = (Resolve-Path -LiteralPath $WinSWPath).Path
} else {
    $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("winsw-" + [guid]::NewGuid().ToString('N') + '.exe')
    Write-Host "Downloading $WinSWAsset $WinSWVersion from github.com/winsw/winsw ..."
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri "https://github.com/winsw/winsw/releases/download/$WinSWVersion/$WinSWAsset" -OutFile $temp -UseBasicParsing
    $winsw = $temp
}

try {
    $actual = (Get-FileHash -LiteralPath $winsw -Algorithm SHA256).Hash
    if ($actual -ne $WinSWSha256) {
        throw "WinSW ($winsw) does not match the pinned SHA-256 of $WinSWAsset $WinSWVersion (expected $WinSWSha256, got $actual); not building the package."
    }

    $name = "svcmon-hub-$version-windows-x64"
    $stage = Join-Path (Join-Path $OutDir 'stage') $name
    $zip = Join-Path $OutDir "$name.zip"
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
    if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
    New-Item -ItemType Directory -Force -Path $stage | Out-Null

    foreach ($file in 'install.ps1', 'uninstall.ps1', 'svcmon-hub.xml', 'WinSW-LICENSE.txt') {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file) -Destination $stage
    }
    Copy-Item -LiteralPath $jar.FullName -Destination $stage
    Copy-Item -LiteralPath $winsw -Destination (Join-Path $stage $WinSWAsset)
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\README.md') -Destination $stage
    Copy-Item -LiteralPath (Join-Path $repo 'LICENSE') -Destination $stage
    Copy-Item -LiteralPath (Join-Path $repo 'NOTICE') -Destination $stage

    Compress-Archive -LiteralPath $stage -DestinationPath $zip
    $zipHash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText("$zip.sha256", "$zipHash  $name.zip`n", $Utf8NoBom)
} finally {
    if ($temp -and (Test-Path -LiteralPath $temp)) { Remove-Item -LiteralPath $temp -Force }
}

Write-Host "Built $zip"
Write-Host "  SHA-256 $zipHash"
Write-Host "  contains $name\: $((Get-ChildItem -LiteralPath $stage | ForEach-Object Name) -join ', ')"
