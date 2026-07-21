[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [Parameter()]
    [string]$Serial,

    [Parameter()]
    [string]$ConnectAddress,

    [Parameter()]
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$')]
    [string]$PackageId = 'io.github.vladbezm.tailware',

    [Parameter()]
    [switch]$EnableAlwaysOn,

    [Parameter()]
    [switch]$Revoke,

    [Parameter()]
    [switch]$AllowNonWearDevice,

    [Parameter()]
    [string]$AdbPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:PlatformToolsUrl = 'https://developer.android.com/tools/releases/platform-tools'

if ($EnableAlwaysOn -and $Revoke) {
    throw '-EnableAlwaysOn and -Revoke cannot be used together.'
}

function Assert-ConnectAddress {
    param([string]$Address)

    if ([string]::IsNullOrWhiteSpace($Address)) {
        throw 'The ADB connection address is empty. Use the IP address and debugging port shown on the main Wireless debugging screen.'
    }

    $separator = $Address.LastIndexOf(':')
    if ($separator -lt 1 -or $separator -eq ($Address.Length - 1)) {
        throw "Invalid ADB connection address '$Address'. Expected WATCH-IP:DEBUG-PORT, for example 192.168.1.25:37123."
    }

    $hostPart = $Address.Substring(0, $separator)
    $portText = $Address.Substring($separator + 1)
    $port = 0
    $validHost = $hostPart -match '\A(?:[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?|\[[0-9A-Fa-f:.]+(?:%[A-Za-z0-9._-]+)?\])\z'
    if (-not $validHost -or
        $portText -notmatch '\A[0-9]{1,5}\z' -or
        -not [int]::TryParse($portText, [ref]$port) -or
        $port -lt 1 -or
        $port -gt 65535) {
        throw "Invalid ADB connection address '$Address'. Use HOST:PORT, IPv4:PORT, or [IPv6]:PORT with the debugging port shown on the main Wireless debugging screen. The pairing port is different."
    }
}

function Assert-Serial {
    param([string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value) -or
        $Value.StartsWith('-') -or
        $Value -notmatch '\A[A-Za-z0-9._:%\[\]-]+\z') {
        throw "Invalid ADB serial '$Value'. Copy the exact serial shown by 'adb devices -l'. Tailware accepts the normal USB, emulator, mDNS, IPv4, and bracketed-IPv6 serial characters only."
    }
}

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

    throw "ADB was not found. Download Android SDK Platform-Tools from $script:PlatformToolsUrl, extract it, then add adb to PATH or pass -AdbPath 'C:\path\to\platform-tools\adb.exe'."
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

function Get-AdbPlatformToolsVersion {
    $output = Invoke-AdbGlobal -Arguments @('version')
    $match = [regex]::Match(
        $output,
        '(?im)^\s*Version\s+(\d+)(?:\.\d+){1,2}(?:-[^\s]+)?\s*$'
    )
    if (-not $match.Success) {
        throw "Could not determine the Android SDK Platform-Tools version from 'adb version'. Install the official current release from $script:PlatformToolsUrl.`nADB output:`n$output"
    }

    $major = [int]$match.Groups[1].Value
    $version = ($match.Value -replace '^\s*Version\s+', '').Trim()
    if ($major -lt 30) {
        throw "Android SDK Platform-Tools $version is too old. Tailware requires Platform-Tools 30 or newer. Download the current official release from $script:PlatformToolsUrl."
    }
    return $version
}

function Get-AdbDeviceTable {
    $raw = Invoke-AdbGlobal -Arguments @('devices', '-l')
    $devices = @()

    foreach ($line in ($raw -split "`r?`n")) {
        if ($line -match '^\s*(\S+)\s+(\S+)(?:\s|$)' -and
            $Matches[1] -ne 'List' -and
            -not $Matches[1].StartsWith('*')) {
            $deviceSerial = $Matches[1]
            $state = $Matches[2]
            if ($state -eq 'no' -and $line -match '\bno permissions\b') {
                $state = 'no permissions'
            }
            $devices += [pscustomobject]@{
                Serial = $deviceSerial
                State  = $state
                Line   = $line.Trim()
            }
        }
    }
    return @($devices)
}

function Get-CurrentAndroidUser {
    $attempts = @(
        @('shell', 'am', 'get-current-user'),
        @('shell', 'cmd', 'activity', 'get-current-user')
    )
    $diagnostics = @()

    foreach ($arguments in $attempts) {
        try {
            $value = Invoke-AdbDevice -Arguments $arguments
            $parsedUser = 0
            if ([int]::TryParse($value.Trim(), [ref]$parsedUser) -and $parsedUser -ge 0) {
                return $parsedUser
            }
            $diagnostics += "adb $($arguments -join ' ') returned '$value'"
        } catch {
            $diagnostics += "adb $($arguments -join ' ') failed: $($_.Exception.Message)"
        }
    }

    throw "Could not determine the current Android user. $($diagnostics -join ' | ')"
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

function Get-AlwaysOnSettingsSnapshot {
    param(
        [Parameter(Mandatory = $true)]
        [int]$UserId
    )

    # Read the owner last. The caller can therefore act immediately on a snapshot whose final ADB
    # operation was the ownership check, rather than reusing values captured before confirmation.
    $lockdown = Get-SecureSetting -UserId $UserId -Name 'always_on_vpn_lockdown'
    $allowlist = Get-SecureSetting -UserId $UserId -Name 'always_on_vpn_lockdown_whitelist'
    $app = Get-SecureSetting -UserId $UserId -Name 'always_on_vpn_app'
    return [pscustomobject]@{
        App = $app
        Lockdown = $lockdown
        Allowlist = $allowlist
    }
}

function Assert-AlwaysOnOwnerMutable {
    param(
        [Parameter(Mandatory = $true)]
        [int]$UserId
    )

    $owner = Get-SecureSetting -UserId $UserId -Name 'always_on_vpn_app'
    if ($owner -and $owner -cne $PackageId) {
        throw "Always-on VPN ownership changed to '$owner'; refusing to modify another provider."
    }
    return $owner
}

function Set-SecureSetting {
    param(
        [Parameter(Mandatory = $true)]
        [int]$UserId,

        [Parameter(Mandatory = $true)]
        [string]$Name,

        [AllowNull()]
        [AllowEmptyString()]
        [object]$Value
    )

    if ($null -eq $Value -or [string]::IsNullOrEmpty([string]$Value)) {
        $null = Invoke-AdbDevice -Arguments @(
            'shell', 'settings', '--user', [string]$UserId, 'delete', 'secure', $Name
        )
        return
    }

    $null = Invoke-AdbDevice -Arguments @(
        'shell', 'settings', '--user', [string]$UserId, 'put', 'secure', $Name, [string]$Value
    )
}

function Set-GuardedAlwaysOnSetting {
    param(
        [Parameter(Mandatory = $true)]
        [int]$UserId,

        [Parameter(Mandatory = $true)]
        [string]$Name,

        [AllowNull()]
        [AllowEmptyString()]
        [object]$Value
    )

    # A settings sequence is several ADB calls, not one transaction. Recheck immediately before
    # every write so a provider that became observable between calls is never overwritten by the
    # remainder of Tailware's sequence.
    $null = Assert-AlwaysOnOwnerMutable -UserId $UserId
    Set-SecureSetting -UserId $UserId -Name $Name -Value $Value
}

function Get-AppOpMode {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Output
    )

    $match = [regex]::Match(
        $Output,
        '(?im)(?:ACTIVATE_VPN|android:activate_vpn)\s*:\s*([a-z_]+)\b'
    )
    if ($match.Success) {
        return $match.Groups[1].Value.ToLowerInvariant()
    }
    if ($Output -match '(?im)^\s*(?:No operations|No ops found)\.?\s*$') {
        return 'default'
    }
    throw "Could not determine the current ACTIVATE_VPN mode. AppOps output:`n$Output"
}

function Get-ActivateVpnAppOp {
    param(
        [Parameter(Mandatory = $true)]
        [int]$UserId
    )

    return Invoke-AdbDevice -Arguments @(
        'shell', 'appops', 'get', '--user', [string]$UserId, $PackageId, 'ACTIVATE_VPN'
    )
}

function Set-ActivateVpnAppOp {
    param(
        [Parameter(Mandatory = $true)]
        [int]$UserId,

        [Parameter(Mandatory = $true)]
        [string]$Mode
    )

    $null = Invoke-AdbDevice -Arguments @(
        'shell', 'appops', 'set', '--user', [string]$UserId, $PackageId, 'ACTIVATE_VPN', $Mode
    )
}

function Assert-ActivateVpnAppOp {
    param(
        [Parameter(Mandatory = $true)]
        [int]$UserId,

        [Parameter(Mandatory = $true)]
        [string]$ExpectedMode
    )

    $output = Get-ActivateVpnAppOp -UserId $UserId
    $actualMode = Get-AppOpMode -Output $output
    if ($actualMode -ne $ExpectedMode) {
        throw "ACTIVATE_VPN verification failed: expected '$ExpectedMode', received '$actualMode'. AppOps output:`n$output"
    }
    return $output
}

function Assert-AlwaysOnSettings {
    param(
        [Parameter(Mandatory = $true)]
        [int]$UserId,

        [AllowNull()]
        [object]$ExpectedApp,

        [AllowNull()]
        [object]$ExpectedLockdown,

        [AllowNull()]
        [object]$ExpectedAllowlist
    )

    $actualLockdown = Get-SecureSetting -UserId $UserId -Name 'always_on_vpn_lockdown'
    $actualAllowlist = Get-SecureSetting -UserId $UserId -Name 'always_on_vpn_lockdown_whitelist'
    # Read the security-critical owner last so a provider takeover between verification reads is
    # reflected in the value that decides success.
    $actualApp = Get-SecureSetting -UserId $UserId -Name 'always_on_vpn_app'
    if ($actualApp -cne $ExpectedApp -or
        $actualLockdown -cne $ExpectedLockdown -or
        $actualAllowlist -cne $ExpectedAllowlist) {
        throw "Always-on verification failed (app='$actualApp', lockdown='$actualLockdown', allowlist='$actualAllowlist')."
    }
}

function Restore-AlwaysOnSettings {
    param(
        [Parameter(Mandatory = $true)]
        [int]$UserId,

        [AllowNull()]
        [object]$PreviousApp,

        [AllowNull()]
        [object]$PreviousLockdown,

        [AllowNull()]
        [object]$PreviousAllowlist
    )

    if ($PreviousApp -and $PreviousApp -cne $PackageId) {
        throw "Refusing to restore foreign always-on VPN provider '$PreviousApp'."
    }
    $null = Assert-AlwaysOnOwnerMutable -UserId $UserId

    # Release lockdown before detaching the temporary provider. Restore the
    # previous provider last, after its lockdown policy and allowlist are ready.
    Set-GuardedAlwaysOnSetting -UserId $UserId -Name 'always_on_vpn_lockdown' -Value '0'
    Set-GuardedAlwaysOnSetting -UserId $UserId -Name 'always_on_vpn_app' -Value $null
    Set-GuardedAlwaysOnSetting -UserId $UserId -Name 'always_on_vpn_lockdown_whitelist' -Value $PreviousAllowlist
    Set-GuardedAlwaysOnSetting -UserId $UserId -Name 'always_on_vpn_lockdown' -Value $PreviousLockdown
    Set-GuardedAlwaysOnSetting -UserId $UserId -Name 'always_on_vpn_app' -Value $PreviousApp
    Assert-AlwaysOnSettings `
        -UserId $UserId `
        -ExpectedApp $PreviousApp `
        -ExpectedLockdown $PreviousLockdown `
        -ExpectedAllowlist $PreviousAllowlist
}

if ($Serial) {
    Assert-Serial -Value $Serial
}
if ($ConnectAddress) {
    Assert-ConnectAddress -Address $ConnectAddress
}
if ($ConnectAddress -and $Serial -and $ConnectAddress -cne $Serial) {
    throw "Refusing ambiguous targets: -ConnectAddress '$ConnectAddress' and -Serial '$Serial' identify different devices. Use only one selector, or pass the same exact address to both."
}

$script:ResolvedAdbPath = Resolve-AdbPath -RequestedPath $AdbPath
$script:AdbVersion = Get-AdbPlatformToolsVersion

if ($ConnectAddress) {
    try {
        $connectOutput = Invoke-AdbGlobal -Arguments @('connect', $ConnectAddress)
    } catch {
        throw "ADB could not connect to '$ConnectAddress'. Make sure Wireless debugging is on and use the debugging port from its main screen, not the temporary pairing port.`n$($_.Exception.Message)"
    }
    if ($connectOutput -notmatch '(?im)^\s*(?:already )?connected to\s+\S+\s*$') {
        throw "ADB could not connect to '$ConnectAddress'. Make sure Wireless debugging is on and use the debugging port from its main screen, not the temporary pairing port.`nADB output:`n$connectOutput"
    }
    Write-Host "ADB connect: $connectOutput"
    if (-not $Serial) {
        $Serial = $ConnectAddress
    }
}

$deviceTable = @(Get-AdbDeviceTable)

if ($Serial) {
    $selected = @($deviceTable | Where-Object { $_.Serial -ceq $Serial })
    if ($selected.Count -eq 0) {
        $knownDevices = if ($deviceTable.Count -gt 0) {
            ($deviceTable | ForEach-Object { $_.Line }) -join "`n"
        } else {
            '(none)'
        }
        throw "ADB cannot find '$Serial'. Pair the watch first, then run 'adb connect WATCH-IP:DEBUG-PORT'. The pairing port and debugging port are different.`nKnown devices:`n$knownDevices"
    }
    if ($selected.Count -ne 1) {
        throw "ADB reported the requested serial '$Serial' more than once. Refusing an ambiguous target; restart the ADB server and reconnect only the intended watch."
    }
    if ($selected[0].State -ne 'device') {
        $state = $selected[0].State
        $help = switch ($state) {
            'unauthorized' {
                'Unlock the watch and accept its Allow debugging prompt. If no prompt appears, turn Wireless debugging off and on, then pair again.'
            }
            'offline' {
                'Turn Wireless debugging off and on, then run adb connect again with the current debugging port.'
            }
            'no permissions' {
                'This computer cannot access the device. Reconnect it, accept the watch prompt, and check the computer ADB permissions.'
            }
            default {
                'Unlock the watch, confirm Wireless debugging is on, and reconnect.'
            }
        }
        throw "ADB can see '$Serial', but it is '$state' instead of ready. $help"
    }
    $script:SelectedSerial = $Serial
} else {
    if ($deviceTable.Count -gt 1) {
        $visibleDevices = ($deviceTable | ForEach-Object { $_.Line }) -join "`n"
        throw "More than one ADB device is visible. To avoid changing the wrong device, rerun with -Serial '<WATCH-SERIAL>' or -ConnectAddress '<WATCH-IP>:<DEBUG-PORT>'. This guard includes offline and unauthorized entries, not only ready devices.`nDevices:`n$visibleDevices"
    }
    $readyDevices = @($deviceTable | Where-Object { $_.State -eq 'device' })
    if ($readyDevices.Count -eq 0) {
        $knownDevices = if ($deviceTable.Count -gt 0) {
            ($deviceTable | ForEach-Object { $_.Line }) -join "`n"
        } else {
            '(none)'
        }
        $unauthorized = @($deviceTable | Where-Object { $_.State -eq 'unauthorized' })
        $offline = @($deviceTable | Where-Object { $_.State -eq 'offline' })
        if ($unauthorized.Count -gt 0) {
            throw "ADB found the watch, but it is unauthorized. Unlock the watch and accept its Allow debugging prompt. If no prompt appears, turn Wireless debugging off and on, then pair again.`nDevices:`n$knownDevices"
        }
        if ($offline.Count -gt 0) {
            throw "ADB found the watch, but it is offline. Turn Wireless debugging off and on, then run 'adb connect WATCH-IP:DEBUG-PORT' with the current debugging port.`nDevices:`n$knownDevices"
        }
        throw "No ready ADB watch was found. On the watch, enable Wireless debugging, pair the computer, then connect with the separate debugging port. Run 'adb devices -l' to confirm the state says 'device'.`nDevices:`n$knownDevices"
    }
    if ($readyDevices.Count -gt 1) {
        $serials = ($readyDevices | ForEach-Object { $_.Serial }) -join ', '
        throw "More than one ready ADB device was found: $serials. To avoid authorizing the wrong device, run this script again with -Serial '<WATCH-SERIAL>' using the exact watch value from 'adb devices -l'."
    }
    $script:SelectedSerial = $readyDevices[0].Serial
}

Assert-Serial -Value $script:SelectedSerial

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
    if (-not $AllowNonWearDevice) {
        throw "The selected device does not report android.hardware.type.watch. Tailware's helper refuses non-Wear devices by default. If this is an intentional emulator or unusual Wear build, inspect the device summary and rerun with -AllowNonWearDevice."
    }
    Write-Warning 'The selected device does not report android.hardware.type.watch. Continuing only because -AllowNonWearDevice was explicitly supplied.'
}

$userId = Get-CurrentAndroidUser
$packagePath = Invoke-AdbDevice -Arguments @(
    'shell', 'pm', 'path', '--user', [string]$userId, $PackageId
)
if ($packagePath -notmatch '(?m)^package:\S+') {
    throw "Tailware package '$PackageId' is not installed for the active watch user ($userId). You may be connected to the wrong device or have installed a different APK. Install the Tailware APK on this watch and retry; only change -PackageId if you intentionally built another application ID."
}

$displaySnapshot = Get-AlwaysOnSettingsSnapshot -UserId $userId
$existingAlwaysOn = $displaySnapshot.App
$existingLockdown = $displaySnapshot.Lockdown
$existingAllowlist = $displaySnapshot.Allowlist
$currentAppOp = Get-ActivateVpnAppOp -UserId $userId
$currentAppOpMode = Get-AppOpMode -Output $currentAppOp

Write-Host ''
Write-Host 'Tailware Wear VPN authorization'
Write-Host "  Device       : $manufacturer $model"
Write-Host "  Serial       : $script:SelectedSerial"
Write-Host "  ADB tools    : $script:AdbVersion"
Write-Host "  Android API  : $sdkLevel"
Write-Host "  Android user : $userId"
Write-Host "  Package      : $PackageId"
Write-Host "  APK           : $($packagePath -replace '^\s*package:', '')"
Write-Host "  Current AppOp: $currentAppOp"
Write-Host "  Always-on VPN: $(if ($existingAlwaysOn) { $existingAlwaysOn } else { '(none)' })"
Write-Host ''

if ($EnableAlwaysOn -and $existingAlwaysOn -and $existingAlwaysOn -cne $PackageId) {
    throw "Refusing to replace existing always-on VPN '$existingAlwaysOn'. Disable it explicitly before retrying."
} elseif (-not $Revoke -and $existingAlwaysOn -and $existingAlwaysOn -cne $PackageId) {
    Write-Warning "Another app ('$existingAlwaysOn') is configured as always-on VPN. Android can refuse to prepare Tailware until that setting is removed."
}

$targetDescription = "$manufacturer $model ($script:SelectedSerial), Android user $userId"
$grantAction = if ($Revoke) {
    "Restore ACTIVATE_VPN to default and remove always-on settings only if $PackageId owns them"
} elseif ($EnableAlwaysOn) {
    "Grant ACTIVATE_VPN AppOp and configure $PackageId as always-on VPN with lockdown disabled"
} else {
    "Grant ACTIVATE_VPN AppOp to $PackageId"
}
if (-not $PSCmdlet.ShouldProcess($targetDescription, $grantAction)) {
    Write-Host 'No device changes were made.'
    return
}

# ShouldProcess may wait on confirmation indefinitely. Refresh the AppOp rollback baseline only
# after authorization, immediately before the first mutation.
$currentAppOp = Get-ActivateVpnAppOp -UserId $userId
$currentAppOpMode = Get-AppOpMode -Output $currentAppOp

$mutationSnapshot = $null
if ($Revoke -or $EnableAlwaysOn) {
    $mutationSnapshot = Get-AlwaysOnSettingsSnapshot -UserId $userId
    if ($EnableAlwaysOn -and
        $mutationSnapshot.App -and
        $mutationSnapshot.App -cne $PackageId) {
        throw "Always-on VPN ownership changed to '$($mutationSnapshot.App)'; refusing to modify another provider."
    }
}

if ($Revoke) {
    $existingAlwaysOn = $mutationSnapshot.App
    $existingLockdown = $mutationSnapshot.Lockdown
    $existingAllowlist = $mutationSnapshot.Allowlist
    $alwaysOnChanged = $false
    try {
        if ($existingAlwaysOn -ceq $PackageId) {
            $alwaysOnChanged = $true
            # Disable lockdown first, detach Tailware, then remove Tailware's
            # remaining policy values. Never touch another provider's settings.
            Set-GuardedAlwaysOnSetting -UserId $userId -Name 'always_on_vpn_lockdown' -Value '0'
            Set-GuardedAlwaysOnSetting -UserId $userId -Name 'always_on_vpn_app' -Value $null
            Set-GuardedAlwaysOnSetting -UserId $userId -Name 'always_on_vpn_lockdown_whitelist' -Value $null
            Set-GuardedAlwaysOnSetting -UserId $userId -Name 'always_on_vpn_lockdown' -Value $null
            Assert-AlwaysOnSettings `
                -UserId $userId `
                -ExpectedApp $null `
                -ExpectedLockdown $null `
                -ExpectedAllowlist $null
        } elseif ($existingAlwaysOn) {
            Write-Warning "Always-on belongs to '$existingAlwaysOn'; revoke mode will preserve that provider and all of its lockdown settings."
        }

        Set-ActivateVpnAppOp -UserId $userId -Mode 'default'
        $verifiedAppOp = Assert-ActivateVpnAppOp -UserId $userId -ExpectedMode 'default'
    } catch {
        $revokeError = $_
        Write-Warning 'Revoke did not verify. Attempting to restore the exact previous AppOp and always-on settings.'
        $rollbackFailures = @()
        try {
            Set-ActivateVpnAppOp -UserId $userId -Mode $currentAppOpMode
            $null = Assert-ActivateVpnAppOp -UserId $userId -ExpectedMode $currentAppOpMode
        } catch {
            $rollbackFailures += "ACTIVATE_VPN AppOp: $($_.Exception.Message)"
        }
        if ($alwaysOnChanged) {
            try {
                Restore-AlwaysOnSettings `
                    -UserId $userId `
                    -PreviousApp $existingAlwaysOn `
                    -PreviousLockdown $existingLockdown `
                    -PreviousAllowlist $existingAllowlist
            } catch {
                $rollbackFailures += "always-on settings: $($_.Exception.Message)"
            }
        }
        if ($rollbackFailures.Count -gt 0) {
            throw "Revoke failed and rollback could not be verified. Original error: $($revokeError.Exception.Message) Rollback error(s): $($rollbackFailures -join ' | ')"
        }
        throw "Revoke failed; the previous state was restored and verified. $($revokeError.Exception.Message)"
    }

    Write-Host "VPN authorization revoked: $verifiedAppOp" -ForegroundColor Green
    if ($alwaysOnChanged) {
        Write-Host 'Tailware-owned always-on settings were removed safely.' -ForegroundColor Green
    }
    Write-Host 'Reopen Tailware. Its setup screen should now request authorization.'
} else {
    try {
        Set-ActivateVpnAppOp -UserId $userId -Mode 'allow'
        $verifiedAppOp = Assert-ActivateVpnAppOp -UserId $userId -ExpectedMode 'allow'
    } catch {
        $grantError = $_
        Write-Warning "VPN authorization did not verify. Restoring previous AppOp mode '$currentAppOpMode'."
        try {
            Set-ActivateVpnAppOp -UserId $userId -Mode $currentAppOpMode
            $null = Assert-ActivateVpnAppOp -UserId $userId -ExpectedMode $currentAppOpMode
        } catch {
            throw "VPN authorization failed and the previous AppOp could not be restored. Original error: $($grantError.Exception.Message) Rollback error: $($_.Exception.Message)"
        }
        throw "VPN authorization failed; the previous AppOp '$currentAppOpMode' was restored and verified. $($grantError.Exception.Message)"
    }

    Write-Host "VPN authorization confirmed: $verifiedAppOp" -ForegroundColor Green

    if ($EnableAlwaysOn) {
        $alwaysOnMutationAttempted = $false
        try {
            # AppOp mutation can give another provider time to take ownership. Refresh the complete
            # rollback baseline after AppOp verification, with the owner read last, before the first
            # settings write.
            $enableSnapshot = Get-AlwaysOnSettingsSnapshot -UserId $userId
            if ($enableSnapshot.App -and $enableSnapshot.App -cne $PackageId) {
                throw "Always-on VPN ownership changed to '$($enableSnapshot.App)'; refusing to modify another provider."
            }
            $existingAlwaysOn = $enableSnapshot.App
            $existingLockdown = $enableSnapshot.Lockdown
            $existingAllowlist = $enableSnapshot.Allowlist

            # Keep lockdown disabled before attaching Tailware. This avoids a
            # transient network lockout if Android applies each setting at once.
            $alwaysOnMutationAttempted = $true
            Set-GuardedAlwaysOnSetting -UserId $userId -Name 'always_on_vpn_lockdown' -Value '0'
            Set-GuardedAlwaysOnSetting -UserId $userId -Name 'always_on_vpn_lockdown_whitelist' -Value $null
            Set-GuardedAlwaysOnSetting -UserId $userId -Name 'always_on_vpn_app' -Value $PackageId
            Assert-AlwaysOnSettings `
                -UserId $userId `
                -ExpectedApp $PackageId `
                -ExpectedLockdown '0' `
                -ExpectedAllowlist $null
        } catch {
            $alwaysOnError = $_
            Write-Warning 'Always-on configuration failed. Restoring the exact previous settings and VPN AppOp.'
            $rollbackFailures = @()
            if ($alwaysOnMutationAttempted) {
                try {
                    Restore-AlwaysOnSettings `
                        -UserId $userId `
                        -PreviousApp $existingAlwaysOn `
                        -PreviousLockdown $existingLockdown `
                        -PreviousAllowlist $existingAllowlist
                } catch {
                    $rollbackFailures += "always-on settings: $($_.Exception.Message)"
                }
            }
            try {
                Set-ActivateVpnAppOp -UserId $userId -Mode $currentAppOpMode
                $null = Assert-ActivateVpnAppOp `
                    -UserId $userId `
                    -ExpectedMode $currentAppOpMode
            } catch {
                $rollbackFailures += "ACTIVATE_VPN AppOp: $($_.Exception.Message)"
            }
            if ($rollbackFailures.Count -gt 0) {
                throw "Always-on setup failed and rollback could not be verified. Original error: $($alwaysOnError.Exception.Message) Rollback error(s): $($rollbackFailures -join ' | ')"
            }
            throw "Always-on setup failed; its previous settings and AppOp '$currentAppOpMode' were restored and verified. $($alwaysOnError.Exception.Message)"
        }

        Write-Host 'Always-on VPN was stored with lockdown disabled.' -ForegroundColor Green
        Write-Warning 'Restart the watch before relying on always-on mode. First sign in and verify a normal Tailware connection.'
    }

    Write-Host ''
    Write-Host 'Open Tailware and connect. Its VpnService.prepare() check should now report authorized.'
}
