# Wear OS VPN authorization

Tailware uses Android's standard `VpnService`. On first connection, the app
always tries the normal Android consent flow before suggesting ADB.

## Normal setup

1. Install and open Tailware on the watch.
2. Sign in.
3. Tap **Connect**.
4. If Android shows the VPN warning, approve it.
5. Return to Tailware and connect again if needed.

`VpnService.prepare()` returns a system activity when approval is still needed
and returns `null` once Tailware is authorized. Tailware checks it again after
the system activity completes instead of assuming that approval succeeded.

Wear OS builds differ by manufacturer. Some watches include a usable Android
VPN confirmation activity; others omit it or cannot display it correctly on the
watch. ADB is a fallback for those devices, not the default onboarding route.

## ADB fallback

Granting this AppOp lets Tailware create an Android VPN interface. Only do this
for the Tailware build you installed yourself. A VPN app can inspect traffic
routed through its interface.

Requirements:

- Wear OS 3 / Android API 30 or newer
- Android SDK Platform-Tools on the computer
- Developer options and wireless debugging enabled on the watch
- the watch and computer connected through ADB

Pair and connect using the address and pairing code shown by the watch:

```powershell
adb pair <WATCH-IP>:<PAIRING-PORT>
adb connect <WATCH-IP>:<DEBUG-PORT>
adb devices -l
```

From the Tailware repository root, run:

```powershell
.\tools\authorize-wear-vpn.ps1
```

The script defaults to package ID `io.github.vladbezm.tailware`. It:

- selects the only ready ADB device, or stops if selection is ambiguous;
- detects the current Android user;
- verifies API level and that Tailware is installed for that user;
- shows the device, package, existing VPN AppOp, and always-on provider;
- asks for confirmation;
- grants only `ACTIVATE_VPN`;
- reads the AppOp back and fails unless Android reports `allow`.

With multiple connected devices, select the watch explicitly:

```powershell
.\tools\authorize-wear-vpn.ps1 -Serial '<WATCH-SERIAL>'
```

If `adb.exe` is not on `PATH`:

```powershell
.\tools\authorize-wear-vpn.ps1 `
  -AdbPath 'C:\Android\platform-tools\adb.exe' `
  -Serial '<WATCH-SERIAL>'
```

Preview the operation without changing the watch:

```powershell
.\tools\authorize-wear-vpn.ps1 -WhatIf
```

After the script confirms authorization, reopen Tailware and tap **Connect**.
The app's own `VpnService.prepare()` result is the definitive authorization
check.

## Manual equivalent

First determine the current user:

```powershell
adb -s <WATCH-SERIAL> shell am get-current-user
```

Then grant and verify the VPN AppOp:

```powershell
adb -s <WATCH-SERIAL> shell appops set --user <USER-ID> `
  io.github.vladbezm.tailware ACTIVATE_VPN allow

adb -s <WATCH-SERIAL> shell appops get --user <USER-ID> `
  io.github.vladbezm.tailware ACTIVATE_VPN
```

The verification output must contain:

```text
ACTIVATE_VPN: allow
```

Do not use `pm grant`. VPN consent is an AppOp, not a runtime permission.
`BIND_VPN_SERVICE` protects the service so only Android can bind to it; it is
not a permission that should be granted to Tailware.

## Optional always-on mode

Always-on is deliberately separate from basic authorization. First sign in,
make a normal connection, and verify that Tailware reconnects correctly.

To store Tailware as the always-on VPN provider:

```powershell
.\tools\authorize-wear-vpn.ps1 -EnableAlwaysOn
```

The script keeps VPN lockdown disabled. It refuses to replace a different
always-on VPN provider. Restart the watch after the command so Android reloads
the secure always-on settings:

```powershell
adb -s <WATCH-SERIAL> reboot
```

Lockdown is not enabled because it can cut off all watch networking while
Tailware is signed out, stopped, or unable to connect.

## Revoke the ADB authorization

Restore the AppOp to Android's default mode:

```powershell
adb -s <WATCH-SERIAL> shell appops set --user <USER-ID> `
  io.github.vladbezm.tailware ACTIVATE_VPN default
```

To remove the optional always-on settings:

```powershell
adb -s <WATCH-SERIAL> shell settings --user <USER-ID> delete secure always_on_vpn_app
adb -s <WATCH-SERIAL> shell settings --user <USER-ID> put secure always_on_vpn_lockdown 0
adb -s <WATCH-SERIAL> shell settings --user <USER-ID> delete secure always_on_vpn_lockdown_whitelist
adb -s <WATCH-SERIAL> reboot
```

## Troubleshooting

- **No ready ADB device:** unlock the watch, accept its debugging prompt, and
  reconnect. `unauthorized` and `offline` devices are rejected.
- **Multiple ADB devices:** pass the exact value from `adb devices -l` through
  `-Serial`.
- **Package is not installed:** install the Tailware APK for the active watch
  user, then rerun the script.
- **Another always-on VPN is listed:** disable that provider explicitly. The
  script will not replace it.
- **Authorization verifies but connection fails:** collect Tailware logs and
  check tunnel/login state. Do not grant `ACTIVATE_PLATFORM_VPN` or
  `ESTABLISH_VPN_SERVICE`; neither is the missing consent for a
  `VpnService`-based app.
