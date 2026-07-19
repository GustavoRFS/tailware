[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [Parameter()]
    [string]$Serial,

    [Parameter()]
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$')]
    [string]$PackageId = 'io.github.vladbezm.tailware',

    [Parameter()]
    [switch]$EnableAlwaysOn,

    [Parameter()]
    [string]$AdbPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-AdbPath {
    param([string]$RequestedPath)

    if ($RequestedPath) {
        if (-not (Test-Path -LiteralPath $RequestedPath -PathType Leaf)) {
            throw "ADB was not found at '$RequestedPath'."
        }
        return (Resolve-Path -LiteralPath $RequestedPath).Path
    }

    $command = Get-Command 'adb' -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $candidates = @()
    if ($env:ANDROID_SDK_ROOT) {
        $candidates += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe')
    }
    if ($env:ANDROID_HOME) {
        $candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe')
    }
    if ($env:LOCALAPPDATA) {
        $candidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
    }

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw 'ADB was not found. Install Android SDK Platform-Tools, add adb to PATH, or pass -AdbPath.'
}

function Invoke-AdbGlobal {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $output = (& $script:ResolvedAdbPath @Arguments 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "ADB failed: adb $($Arguments -join ' ')`n$output"
    }
    return $output
}

function Invoke-AdbDevice {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $adbArguments = @('-s', $script:SelectedSerial) + $Arguments
    $output = (& $script:ResolvedAdbPath @adbArguments 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "ADB failed for '$script:SelectedSerial': adb $($adbArguments -join ' ')`n$output"
    }
    return $output
}

function Get-AdbDeviceTable {
    $raw = Invoke-AdbGlobal -Arguments @('devices', '-l')
    $devices = @()

    foreach ($line in ($raw -split "`r?`n")) {
        if ($line -match '^\s*(\S+)\s+(device|offline|unauthorized|no permissions)(?:\s|$)') {
            $devices += [pscustomobject]@{
                Serial = $Matches[1]
                State  = $Matches[2]
                Line   = $line.Trim()
            }
        }
    }
    return @($devices)
}

function Get-CurrentAndroidUser {
    $value = Invoke-AdbDevice -Arguments @('shell', 'am', 'get-current-user')
    if ($value -notmatch '^\s*(\d+)\s*$') {
        $value = Invoke-AdbDevice -Arguments @('shell', 'cmd', 'activity', 'get-current-user')
    }
    if ($value -notmatch '^\s*(\d+)\s*$') {
        throw "Could not determine the current Android user. Output: '$value'"
    }
    return [int]$Matches[1]
}

function Get-SecureSetting {
    param(
        [Parameter(Mandatory = $true)]
        [int]$UserId,

        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $value = (Invoke-AdbDevice -Arguments @(
            'shell', 'settings', '--user', [string]$UserId, 'get', 'secure', $Name
        )).Trim()
    if (-not $value -or $value -eq 'null') {
        return $null
    }
    return $value
}

function Set-SecureSetting {
    param(
        [Parameter(Mandatory = $true)]
        [int]$UserId,

        [Parameter(Mandatory = $true)]
        [string]$Name,

        [AllowNull()]
        [string]$Value
    )

    if ($null -eq $Value) {
        $null = Invoke-AdbDevice -Arguments @(
            'shell', 'settings', '--user', [string]$UserId, 'delete', 'secure', $Name
        )
        return
    }

    $null = Invoke-AdbDevice -Arguments @(
        'shell', 'settings', '--user', [string]$UserId, 'put', 'secure', $Name, $Value
    )
}

$script:ResolvedAdbPath = Resolve-AdbPath -RequestedPath $AdbPath
$deviceTable = @(Get-AdbDeviceTable)

if ($Serial) {
    $selected = @($deviceTable | Where-Object { $_.Serial -eq $Serial })
    if ($selected.Count -eq 0) {
        $knownDevices = if ($deviceTable.Count -gt 0) {
            ($deviceTable | ForEach-Object { $_.Line }) -join "`n"
        } else {
            '(none)'
        }
        throw "Device '$Serial' was not found by ADB. Known devices:`n$knownDevices"
    }
    if ($selected[0].State -ne 'device') {
        throw "Device '$Serial' is '$($selected[0].State)', not ready. Accept the debugging prompt and reconnect."
    }
    $script:SelectedSerial = $Serial
} else {
    $readyDevices = @($deviceTable | Where-Object { $_.State -eq 'device' })
    if ($readyDevices.Count -eq 0) {
        $knownDevices = if ($deviceTable.Count -gt 0) {
            ($deviceTable | ForEach-Object { $_.Line }) -join "`n"
        } else {
            '(none)'
        }
        throw "No ready ADB device was found. Devices:`n$knownDevices"
    }
    if ($readyDevices.Count -gt 1) {
        $serials = ($readyDevices | ForEach-Object { $_.Serial }) -join ', '
        throw "Multiple ready ADB devices were found ($serials). Run again with -Serial."
    }
    $script:SelectedSerial = $readyDevices[0].Serial
}

$sdkText = (Invoke-AdbDevice -Arguments @('shell', 'getprop', 'ro.build.version.sdk')).Trim()
if ($sdkText -notmatch '^\d+$') {
    throw "Invalid Android API level returned by the device: '$sdkText'"
}
$sdkLevel = [int]$sdkText
if ($sdkLevel -lt 30) {
    throw "Android API $sdkLevel is unsupported by this bootstrap. Wear OS 3 / API 30 or newer is required."
}

$manufacturer = (Invoke-AdbDevice -Arguments @('shell', 'getprop', 'ro.product.manufacturer')).Trim()
$model = (Invoke-AdbDevice -Arguments @('shell', 'getprop', 'ro.product.model')).Trim()
$features = Invoke-AdbDevice -Arguments @('shell', 'pm', 'list', 'features')
if ($features -notmatch '(?m)^feature:android\.hardware\.type\.watch\s*$') {
    Write-Warning 'The connected device does not report android.hardware.type.watch. Continuing because the VPN authorization mechanism is standard Android.'
}

$userId = Get-CurrentAndroidUser
$packagePath = Invoke-AdbDevice -Arguments @(
    'shell', 'pm', 'path', '--user', [string]$userId, $PackageId
)
if ($packagePath -notmatch '(?m)^package:') {
    throw "Package '$PackageId' is not installed for Android user $userId."
}

$existingAlwaysOn = Get-SecureSetting -UserId $userId -Name 'always_on_vpn_app'
$currentAppOp = Invoke-AdbDevice -Arguments @(
    'shell', 'appops', 'get', '--user', [string]$userId, $PackageId, 'ACTIVATE_VPN'
)

Write-Host ''
Write-Host 'Tailware Wear VPN authorization'
Write-Host "  Device       : $manufacturer $model"
Write-Host "  Serial       : $script:SelectedSerial"
Write-Host "  Android API  : $sdkLevel"
Write-Host "  Android user : $userId"
Write-Host "  Package      : $PackageId"
Write-Host "  APK           : $($packagePath -replace '^\s*package:', '')"
Write-Host "  Current AppOp: $currentAppOp"
Write-Host "  Always-on VPN: $(if ($existingAlwaysOn) { $existingAlwaysOn } else { '(none)' })"
Write-Host ''

if ($EnableAlwaysOn -and $existingAlwaysOn -and $existingAlwaysOn -ne $PackageId) {
    throw "Refusing to replace existing always-on VPN '$existingAlwaysOn'. Disable it explicitly before retrying."
} elseif ($existingAlwaysOn -and $existingAlwaysOn -ne $PackageId) {
    Write-Warning "Another app ('$existingAlwaysOn') is configured as always-on VPN. Android can refuse to prepare Tailware until that setting is removed."
}

$targetDescription = "$manufacturer $model ($script:SelectedSerial), Android user $userId"
$grantAction = if ($EnableAlwaysOn) {
    "Grant ACTIVATE_VPN AppOp and configure $PackageId as always-on VPN with lockdown disabled"
} else {
    "Grant ACTIVATE_VPN AppOp to $PackageId"
}
if (-not $PSCmdlet.ShouldProcess($targetDescription, $grantAction)) {
    Write-Host 'No device changes were made.'
    return
}

$null = Invoke-AdbDevice -Arguments @(
    'shell', 'appops', 'set', '--user', [string]$userId, $PackageId, 'ACTIVATE_VPN', 'allow'
)

$verifiedAppOp = Invoke-AdbDevice -Arguments @(
    'shell', 'appops', 'get', '--user', [string]$userId, $PackageId, 'ACTIVATE_VPN'
)
if ($verifiedAppOp -notmatch '(?im)(?:ACTIVATE_VPN|android:activate_vpn)\s*:\s*allow\b') {
    throw "ADB did not confirm VPN authorization. AppOps output:`n$verifiedAppOp"
}

Write-Host "VPN authorization confirmed: $verifiedAppOp" -ForegroundColor Green

if ($EnableAlwaysOn) {
    $oldAlwaysOn = $existingAlwaysOn
    $oldLockdown = Get-SecureSetting -UserId $userId -Name 'always_on_vpn_lockdown'
    $oldAllowlist = Get-SecureSetting -UserId $userId -Name 'always_on_vpn_lockdown_whitelist'

    try {
        Set-SecureSetting -UserId $userId -Name 'always_on_vpn_lockdown' -Value '0'
        Set-SecureSetting -UserId $userId -Name 'always_on_vpn_lockdown_whitelist' -Value $null
        Set-SecureSetting -UserId $userId -Name 'always_on_vpn_app' -Value $PackageId

        $storedAlwaysOn = Get-SecureSetting -UserId $userId -Name 'always_on_vpn_app'
        $storedLockdown = Get-SecureSetting -UserId $userId -Name 'always_on_vpn_lockdown'
        if ($storedAlwaysOn -ne $PackageId -or $storedLockdown -ne '0') {
            throw "Always-on values did not verify (app='$storedAlwaysOn', lockdown='$storedLockdown')."
        }
    } catch {
        Write-Warning 'Always-on configuration failed. Attempting to restore the previous secure settings.'
        try {
            Set-SecureSetting -UserId $userId -Name 'always_on_vpn_app' -Value $oldAlwaysOn
            Set-SecureSetting -UserId $userId -Name 'always_on_vpn_lockdown' -Value $oldLockdown
            Set-SecureSetting -UserId $userId -Name 'always_on_vpn_lockdown_whitelist' -Value $oldAllowlist
        } catch {
            Write-Warning "Rollback also failed: $($_.Exception.Message)"
        }
        throw
    }

    Write-Host 'Always-on VPN was stored with lockdown disabled.' -ForegroundColor Green
    Write-Warning 'Restart the watch before relying on always-on mode. First sign in and verify a normal Tailware connection.'
    Write-Host "Restart command: `"$script:ResolvedAdbPath`" -s `"$script:SelectedSerial`" reboot"
}

Write-Host ''
Write-Host 'Open Tailware and connect. Its VpnService.prepare() check should now report authorized.'
