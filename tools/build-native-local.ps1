[CmdletBinding()]
param(
    [string]$SdkRoot,
    [string]$GoBin
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if (-not $SdkRoot) {
    $SdkRoot = Join-Path $repoRoot 'android-sdk'
}
if (-not $GoBin) {
    $GoBin = Join-Path $env:USERPROFILE 'go\bin'
}
$sdk = (Resolve-Path -LiteralPath $SdkRoot).Path
$gomobile = Join-Path $GoBin 'gomobile.exe'
if (-not (Test-Path -LiteralPath $gomobile -PathType Leaf)) {
    throw "gomobile.exe was not found at '$gomobile'. Run: go install golang.org/x/mobile/cmd/gomobile"
}

$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$outputDirectory = Join-Path $repoRoot 'android\libs'
$output = Join-Path $outputDirectory 'libtailscale.aar'
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

$version = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $repoRoot 'tailscale.version')) {
    if ($line -match '^([A-Z0-9_]+)="?(.*?)"?$') {
        $version[$Matches[1]] = $Matches[2].TrimEnd('"')
    }
}

$requiredVersionKeys = @('VERSION_LONG', 'VERSION_SHORT', 'VERSION_GIT_HASH', 'VERSION_EXTRA_HASH')
foreach ($key in $requiredVersionKeys) {
    if (-not $version.ContainsKey($key)) {
        throw "tailscale.version is missing $key."
    }
}

$ldflags = @(
    '-linkmode=external'
    '-extldflags=-Wl,-z,max-page-size=16384'
    "-X tailscale.com/version.longStamp=$($version.VERSION_LONG)"
    "-X tailscale.com/version.shortStamp=$($version.VERSION_SHORT)"
    "-X tailscale.com/version.gitCommitStamp=$($version.VERSION_GIT_HASH)"
    "-X tailscale.com/version.extraGitCommitStamp=$($version.VERSION_EXTRA_HASH)"
) -join ' '

Push-Location $repoRoot
try {
    & $gomobile bind `
        -target android `
        -androidapi 26 `
        -tags 'not_tailscale_go ts_omit_cachenetmap' `
        -ldflags $ldflags `
        -o $output `
        ./libtailscale
    if ($LASTEXITCODE -ne 0) {
        throw "gomobile bind failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $output -PathType Leaf)) {
    throw "gomobile completed without creating '$output'."
}

$artifact = Get-Item -LiteralPath $output
Write-Host "Created $($artifact.FullName) ($($artifact.Length) bytes)."
