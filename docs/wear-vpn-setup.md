# Wear OS VPN authorization

Tailware uses Android's standard `VpnService`. On first connection, the app
needs a one-time ADB authorization on Wear OS.

## Why ADB is required

Android's Compatibility Test Suite explicitly reports VPN configuration as
unsupported on watch devices and excludes watches from its `VpnService`
compatibility test:

- [`FeatureUtil.isConfigVpnSupported()`](https://android.googlesource.com/platform/cts/+/refs/heads/main/apps/CtsVerifier/src/com/android/cts/verifier/features/FeatureUtil.java)
- [`VpnTest.supportedHardware()`](https://android.googlesource.com/platform/cts/+/4cb525394d52ecce303c18590d9955e098a22696/hostsidetests/net/app/src/com/android/cts/net/hostside/VpnTest.java)

`VpnService.prepare()` returns `null` once Tailware is authorized. A non-null
result is not evidence that Wear OS can display a working confirmation screen:
it can resolve to a non-interactive system stub. Tailware therefore does not
launch that intent on a watch. It safely shows the ADB setup until `prepare()`
returns `null`.

## ADB authorization

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

### Windows

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

The helper can also connect to the watch first:

```powershell
.\tools\authorize-wear-vpn.ps1 -ConnectAddress '<WATCH-IP>:<DEBUG-PORT>'
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

Keep Tailware's setup screen open while running the helper. It checks
authorization every two seconds and continues to sign-in automatically once
the grant is visible. After a successful sign-in, Tailware starts the tunnel
automatically. The app's own `VpnService.prepare()` result remains the
definitive authorization check.

### macOS or Linux

Install Android SDK Platform-Tools, connect the watch with `adb pair` and
`adb connect`, then run:

```sh
chmod +x ./tools/authorize-wear-vpn.sh
./tools/authorize-wear-vpn.sh
```

With multiple connected Android devices, always select the watch explicitly:

```sh
./tools/authorize-wear-vpn.sh --serial '<WATCH-SERIAL>'
```

Or connect and authorize in one guarded command:

```sh
./tools/authorize-wear-vpn.sh --connect '<WATCH-IP>:<DEBUG-PORT>'
```

The script also supports `--adb /path/to/adb`, `--dry-run`, and `--yes`.
It performs the same device, package, Android-user, and `ACTIVATE_VPN`
verification as the Windows script. It never enables lockdown or replaces an
always-on VPN provider.

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

## Restart behavior

Tailware stores the last explicit connection choice:

- when Tailware was **on**, a normal watch reboot restores the tunnel after
  `BOOT_COMPLETED`, provided the account and VPN AppOp are still valid;
- when Tailware was **off**, a reboot leaves it off;
- signing out or losing VPN authorization clears the saved on-state.

The `ACTIVATE_VPN` AppOp normally survives a reboot, so it does not need to be
granted again. Android deliberately suppresses boot receivers after a manual
force-stop until the user opens the app again.

## Optional always-on mode

Always-on is deliberately separate from Tailware's own state-preserving reboot
recovery. Android's always-on mode can restart the VPN even after the in-app
switch was deliberately turned off, so do not enable it when exact on/off
preservation is desired. If that system-enforced behavior is preferred, first
sign in, make a normal connection, and verify that Tailware reconnects
correctly.

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
