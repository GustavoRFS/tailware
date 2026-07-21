# Wear OS VPN authorization

Tailware uses Android's standard `VpnService`. Wear OS does not provide a
reliable, supported VPN confirmation screen, so the watch needs a one-time ADB
authorization before Tailware can create its VPN interface.

During private branch testing, the app may encode this exact guide URL. The QR
code, its tappable action, the manual fallback, and the phone handoff must all
resolve to this one precise document:

```text
https://github.com/vladbezm/tailware/blob/agent/wear-v1/docs/wear-vpn-setup.md
```

The repository is private during testing, so the phone opening that link must
be signed in to a GitHub account with access. Before public release, the app
and its QR code must switch to:

```text
https://github.com/vladbezm/tailware/blob/main/docs/wear-vpn-setup.md
```

Do not publish a build containing the private branch URL. Tailware's CI scans
the production Android source before main/release builds and rejects a
non-`main` guide URL:

```text
python3 tools/validate-wear-vpn-bootstrap.py --mode release
```

During private testing, the corresponding development check permits either the
private URL above or the final `main` URL, but still rejects split or malformed
QR/manual/phone destinations:

```text
python3 tools/validate-wear-vpn-bootstrap.py --mode development
```

## What the authorization does

The helper grants only Android's `ACTIVATE_VPN` AppOp to the installed
Tailware package. It does not root the watch, unlock the bootloader, grant
unrelated permissions, or enable VPN lockdown.

Granting VPN access lets Tailware create an Android VPN interface and inspect
traffic routed through that interface. Only authorize a Tailware APK that you
obtained from this repository or an official Tailware release.

The package ID used by the official build is:

```text
io.github.vladbezm.tailware
```

## Beginner setup

### Before you start

You need:

- a Wear OS 3 watch running Android API 30 or newer;
- Tailware already installed on that watch;
- a Windows, macOS, or Linux computer;
- the watch and computer on a network that lets devices reach each other;
- the Tailware repository or the matching release's `tools` folder.

Keep authentication keys, Tailscale login links, and ADB pairing codes private.
The pairing code is temporary, but it should still never be posted in an issue,
chat, screenshot, or support log.

### 1. Install official Android Platform-Tools

Download Android SDK Platform-Tools only from the official Android developer
site:

<https://developer.android.com/tools/releases/platform-tools>

Extract the archive to a folder you can find again. The Tailware helpers require
Platform-Tools version 30 or newer and check this before changing the watch.

Confirm the installation:

```text
adb version
```

The output must contain a Platform-Tools line such as `Version 35.0.2-...`.
The separate line `Android Debug Bridge version 1.0.41` is the ADB protocol
version and is not the Platform-Tools version checked by the helper.

If `adb` is not on your command path, either run it from the extracted
`platform-tools` folder or pass its full path to the Tailware helper later.

### 2. Enable Developer options on the watch

Menu names vary slightly between watch brands and Wear OS versions.

1. Open **Settings** on the watch.
2. Open **About watch**, **About**, or **System > About**.
3. Open **Software information** or **Versions** if that submenu exists.
4. Tap **Build number** or **Software version** repeatedly until the watch says
   that Developer options are enabled.
5. Enter the watch PIN if Android requests it.

On Samsung Galaxy Watch models, the usual path is **Settings > About watch >
Software information > Software version**. On other Wear OS watches, look for
**Build number** instead.

Tailware may offer a shortcut to About or Developer options. That shortcut can
only open the Android settings page; an ordinary app cannot enable Developer
options for you.

### 3. Enable Wireless debugging

1. Return to the watch's main **Settings** screen.
2. Open **Developer options**.
3. Turn on **Wireless debugging**.
4. Leave that page available while pairing the computer.

Wireless debugging is an ADB setting, not the normal Wi-Fi settings page.
Bluetooth pairing with the phone is not enough. If the option is unavailable,
make sure the watch itself is connected to Wi-Fi.

Google's platform instructions are available in
[Debug a Wear OS app over Wi-Fi](https://developer.android.com/training/wearables/get-started/debug-wifi).

### 4. Pair, then connect

Wireless debugging displays two different ports:

- **Pairing port:** shown temporarily after selecting **Pair new device** or
  **Pair device with pairing code**.
- **Debugging port:** shown on the main Wireless debugging screen as
  **IP address & port**.

These ports are normally different. A successful `adb pair` does not replace
the separate `adb connect` step.

First select **Pair new device** on the watch. On the computer, run:

```text
adb pair <WATCH-IP>:<PAIRING-PORT>
```

Enter the temporary pairing code only when ADB asks for it. Next, return to the
main Wireless debugging screen, copy its current debugging address, and run:

```text
adb connect <WATCH-IP>:<DEBUG-PORT>
adb devices -l
```

The watch must appear with state `device`, for example:

```text
<WATCH-IP>:<DEBUG-PORT>    device product:... model:...
```

Do not continue if it says `unauthorized` or `offline`. Resolve that state
using the troubleshooting section below.

Android can change the debugging port after Wireless debugging is toggled,
Wi-Fi reconnects, or the watch restarts. Always use the address currently shown
by the watch.

### 5. Run the guarded Tailware helper

The helper shows the selected watch, Android user, package, current VPN AppOp,
and existing always-on provider before it asks for confirmation. Read that
summary and approve only when the watch and package are correct.

Without `-Serial`/`--serial` or `-ConnectAddress`/`--connect`, the helpers
continue only when exactly one ADB entry is visible. An offline or unauthorized
phone still counts as a visible entry: this prevents the script from silently
selecting the one ready device when the computer can also see another target.

#### Windows

Open PowerShell in the Tailware repository root and use the exact watch value
from `adb devices -l`:

```powershell
.\tools\authorize-wear-vpn.ps1 -Serial '<WATCH-IP>:<DEBUG-PORT>'
```

If `adb.exe` is not on `PATH`, pass its full location:

```powershell
.\tools\authorize-wear-vpn.ps1 `
  -AdbPath 'C:\path\to\platform-tools\adb.exe' `
  -Serial '<WATCH-IP>:<DEBUG-PORT>'
```

The helper can also perform the `adb connect` step:

```powershell
.\tools\authorize-wear-vpn.ps1 `
  -ConnectAddress '<WATCH-IP>:<DEBUG-PORT>'
```

Use one device selector. If both `-ConnectAddress` and `-Serial` are supplied,
they must contain the same exact address; the helper refuses two different
targets instead of guessing.

Preview all checks without changing the watch:

```powershell
.\tools\authorize-wear-vpn.ps1 -WhatIf
```

Do not disable PowerShell security globally. If Windows marks a verified script
download from this repository as blocked, unblock only that file:

```powershell
Unblock-File -LiteralPath .\tools\authorize-wear-vpn.ps1
```

#### macOS or Linux

Open a terminal in the Tailware repository root and use the exact watch value
from `adb devices -l`:

```sh
chmod +x ./tools/authorize-wear-vpn.sh
./tools/authorize-wear-vpn.sh --serial '<WATCH-IP>:<DEBUG-PORT>'
```

If `adb` is not on `PATH`:

```sh
./tools/authorize-wear-vpn.sh \
  --adb '/path/to/platform-tools/adb' \
  --serial '<WATCH-IP>:<DEBUG-PORT>'
```

The helper can also perform the connection step:

```sh
./tools/authorize-wear-vpn.sh \
  --connect '<WATCH-IP>:<DEBUG-PORT>'
```

Use one device selector. If both `--connect` and `--serial` are supplied, they
must contain the same exact address.

Preview without changing the watch:

```sh
./tools/authorize-wear-vpn.sh \
  --serial '<WATCH-IP>:<DEBUG-PORT>' \
  --dry-run
```

The `--yes` option is intended only for controlled automation. Beginners should
keep the confirmation prompt.

Preview mode never issues an AppOp or secure-settings write. When a
`-ConnectAddress`/`--connect` value is supplied, it can still establish the
host-side ADB transport so that the read-only watch checks can run.

### 6. Return to Tailware

After the helper prints `VPN authorization confirmed`, return to Tailware's VPN
setup screen. While that screen is visible, Tailware checks authorization every
two seconds and continues automatically once Android reports it ready. You can
also tap **Retry authorization** or reopen Tailware.

The app's own `VpnService.prepare()` result is the definitive check. The
`ACTIVATE_VPN` AppOp normally survives a watch restart, so this setup should
only be needed once unless the authorization is revoked, Tailware is reinstalled
under a different package identity, or Android resets the AppOp.

## What the helpers verify

Both helpers:

- require Android SDK Platform-Tools version 30 or newer;
- refuse malformed connection addresses;
- select the requested ADB serial, or the only visible and ready device;
- stop instead of guessing when multiple ADB entries are visible, including
  offline and unauthorized entries;
- explain `unauthorized` and `offline` states;
- verify Wear OS 3 / Android API 30 or newer;
- refuse a device that does not advertise
  `android.hardware.type.watch`, unless an expert explicitly supplies
  `-AllowNonWearDevice` or `--allow-non-wear` for an unusual Wear image or
  emulator;
- detect the current Android user;
- verify that the expected Tailware package is installed for that user;
- display the device, package, current AppOp, and always-on provider;
- ask for confirmation unless explicitly running a preview or automation;
- grant only `ACTIVATE_VPN`;
- read the AppOp back and fail unless Android reports `allow`;
- restore and verify the exact previous AppOp mode if granting does not verify;
- offer a guarded revoke mode that preserves another app's always-on settings.

Both helpers' optional always-on mode additionally refuses to replace a
different VPN provider. If verification fails, each helper first releases
lockdown, detaches its temporary provider, restores the previous policy, then
restores the previous provider last and verifies all three settings.

## Troubleshooting

### `adb` was not found

Install or update the official Platform-Tools package:

<https://developer.android.com/tools/releases/platform-tools>

Then add its folder to `PATH`, run commands from that folder, or pass
`-AdbPath`/`--adb` to the helper.

### Platform-Tools is older than version 30

Replace the entire old `platform-tools` folder with the current official
download. Do not copy only the `adb` executable over an older installation.

### Pairing succeeded, but connection fails

You probably used the temporary pairing port with `adb connect`. Return to the
main Wireless debugging screen and use its current **IP address & port**:

```text
adb connect <WATCH-IP>:<DEBUG-PORT>
```

Also check that the watch and computer are on the same reachable network.
Guest Wi-Fi and client isolation can block device-to-device connections.

### The watch is `unauthorized`

Unlock the watch and accept its **Allow debugging** prompt. If no prompt appears:

1. turn Wireless debugging off and on;
2. remove or forget the old paired computer on the watch if possible;
3. run `adb pair` again with a fresh pairing code;
4. run `adb connect` with the current debugging port.

### The watch is `offline`

Turn Wireless debugging off and on, then reconnect with the newly displayed
debugging port:

```text
adb disconnect <WATCH-IP>:<OLD-PORT>
adb connect <WATCH-IP>:<CURRENT-DEBUG-PORT>
```

### More than one ADB device is connected

The helpers deliberately stop to avoid authorizing the wrong device. This also
happens when only one entry is ready and a second phone/watch is offline or
unauthorized. Run `adb devices -l`, identify the watch, and pass its exact
serial:

```powershell
.\tools\authorize-wear-vpn.ps1 -Serial '<WATCH-SERIAL>'
```

```sh
./tools/authorize-wear-vpn.sh --serial '<WATCH-SERIAL>'
```

### Tailware is not installed for the active user

Confirm that:

- the selected ADB device is the watch;
- the Tailware APK is installed on that watch;
- its package ID is `io.github.vladbezm.tailware`.

Only use `-PackageId` or `--package` when you intentionally built Tailware with
a different application ID. Never change the package merely to make the check
pass.

### Another always-on VPN is listed

Do not replace it accidentally. Disable that provider explicitly in Android's
VPN settings before retrying. The normal helper warns about it, and optional
always-on mode refuses to overwrite it.

### Authorization verifies, but Tailware still cannot connect

Return to Tailware and tap **Retry authorization**. If authorization is ready
but the tunnel still fails, check account, internet, DNS, exit-node, and
Tailware health status. Do not grant `ACTIVATE_PLATFORM_VPN` or
`ESTABLISH_VPN_SERVICE`; neither is the missing authorization for a
`VpnService`-based app.

When sharing diagnostic output, remove local IP addresses, device serials,
authentication keys, login URLs, and pairing codes.

## Why ADB is required

Android's Compatibility Test Suite reports VPN configuration as unsupported on
watch devices and excludes watches from its `VpnService` compatibility test:

- [`FeatureUtil.isConfigVpnSupported()`](https://android.googlesource.com/platform/cts/+/refs/heads/main/apps/CtsVerifier/src/com/android/cts/verifier/features/FeatureUtil.java)
- [`VpnTest.supportedHardware()`](https://android.googlesource.com/platform/cts/+/4cb525394d52ecce303c18590d9955e098a22696/hostsidetests/net/app/src/com/android/cts/net/hostside/VpnTest.java)

`VpnService.prepare()` returns `null` once Tailware is authorized. A non-null
result does not prove that Wear OS can display a working confirmation screen;
it can resolve to a non-interactive system stub. Tailware therefore checks the
result but does not launch that intent on a watch.

The required authorization is an AppOp, not a runtime permission. Do not use
`pm grant`. `BIND_VPN_SERVICE` protects the service so only Android can bind to
it and must not be granted to Tailware.

## Advanced: manual authorization

The guarded helpers are recommended. For diagnosis or review, the equivalent
manual sequence is:

```text
adb -s <WATCH-SERIAL> shell am get-current-user
```

Use the returned user ID in both commands:

```text
adb -s <WATCH-SERIAL> shell appops set --user <USER-ID> io.github.vladbezm.tailware ACTIVATE_VPN allow
adb -s <WATCH-SERIAL> shell appops get --user <USER-ID> io.github.vladbezm.tailware ACTIVATE_VPN
```

The verification output must contain:

```text
ACTIVATE_VPN: allow
```

## Advanced: restart behavior

Tailware stores the user's last explicit connection choice:

- when Tailware was **on**, a normal watch restart restores the tunnel after
  `BOOT_COMPLETED`, provided the account and VPN AppOp are still valid;
- when Tailware was **off**, a restart leaves it off;
- signing out or losing VPN authorization clears the saved on-state.

Android deliberately suppresses boot receivers after a manual force-stop until
the user opens the app again.

## Advanced: optional always-on VPN

Always-on is separate from Tailware's own state-preserving restart recovery.
Android always-on can restart the VPN after the in-app switch was deliberately
turned off. Do not enable it when exact preservation of the Tailware switch is
required.

Only after normal authorization, sign-in, connection, and restart recovery have
been verified, enable optional always-on mode.

Windows:

```powershell
.\tools\authorize-wear-vpn.ps1 `
  -Serial '<WATCH-SERIAL>' `
  -EnableAlwaysOn
```

macOS or Linux:

```sh
./tools/authorize-wear-vpn.sh \
  --serial '<WATCH-SERIAL>' \
  --enable-always-on
```

Both helpers keep lockdown disabled, refuse to replace a different always-on
provider, verify the provider, lockdown, and allowlist values, and restore the
exact previous settings in a network-safe order if verification fails. Restart
the watch afterward:

```text
adb -s <WATCH-SERIAL> reboot
```

Lockdown remains disabled because it could cut off all watch networking while
Tailware is signed out, stopped, or unable to connect.

## Advanced: revoke authorization

Use the guarded revoke mode instead of deleting secure settings manually. It
restores `ACTIVATE_VPN` to Android's default mode. It removes the always-on
provider, lockdown, and allowlist values only when the current always-on
provider is exactly `io.github.vladbezm.tailware`; if another VPN owns
always-on, every one of its settings is preserved.

Windows:

```powershell
.\tools\authorize-wear-vpn.ps1 `
  -Serial '<WATCH-SERIAL>' `
  -Revoke
```

macOS or Linux:

```sh
./tools/authorize-wear-vpn.sh \
  --serial '<WATCH-SERIAL>' \
  --revoke
```

Revoke is transactional: if the new AppOp or Tailware-owned always-on state
does not verify, the helper attempts to restore and verify the previous state.
If rollback itself cannot be verified, the helper reports that explicitly
instead of claiming success.
