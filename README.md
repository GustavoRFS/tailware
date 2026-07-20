# Tailware

<p align="center">
  <img src="branding/tailware-mark.svg" alt="Tailware network T logo" width="180">
</p>

Tailware is an independent, open-source Tailscale client designed for Wear OS.
It turns a smartwatch into a full tailnet device while keeping the interface
focused on the controls that make sense on a small round screen.

> **Development status:** early private preview. The app is not ready for
> production use or public distribution yet.

Tailware is based on the open-source
[Tailscale Android client](https://github.com/tailscale/tailscale-android).
It is not affiliated with or endorsed by Tailscale Inc.

## First-version scope

- Guided first-run sign-in with an attempt-scoped QR code, one-tap continuation
  on the paired phone, a clearly labeled Tailscale auth key, or a custom
  Headscale/control-server URL
- A watch-first dashboard with one large connect/disconnect control
- Own device name, Tailscale IP addresses, MagicDNS name, tailnet, and tunnel
  status
- Tailnet device list with peer details and ping/latency testing
- Exit-node selection, exit-node clearing, and local-LAN access
- Tailscale DNS on/off
- English, Dutch, French, German, and Russian interface choices
- Optional connection alerts, kept separate from Android's required active-VPN
  foreground notification
- In-app Tailware version, source attribution, and full BSD 3-Clause notice
- Wear OS Tile with a colored connection ring, compact network details, and
  either a quick connection toggle or an open-app action, backed by a
  battery-friendly attempt-aware status snapshot
- Connection-health details for missing internet, DNS, DERP, and exit-node
  problems without misreporting the local VPN interface as disconnected
- Reboot recovery that preserves the user's last explicit on/off choice
- Safe one-time ADB authorization guidance for Wear OS VPN setup, with guarded
  Windows and macOS/Linux helpers
- Optional always-on VPN setup with lockdown deliberately disabled
- Layouts made for round screens and smaller Wear OS displays

## Platform

- Wear OS 3 or newer
- Android API 30 minimum
- Android API 36 target
- Standalone watch app; a companion phone app is not required

The initial hardware target is a Samsung Galaxy Watch5 Pro. The design avoids
Samsung-only APIs so it can also run on compatible watches from other
manufacturers. Physical-device compatibility still needs to be verified before
the first public release.

## VPN authorization on Wear OS

Android's Compatibility Test Suite treats VPN configuration as unsupported on
watch devices and excludes watches from its `VpnService` compatibility test.
Tailware therefore uses `VpnService.prepare()` only to check whether it is
already authorized. When it is not, use one of the repository's guarded
helpers after installing the APK and connecting the watch through wireless
debugging.

Windows:

```powershell
.\tools\authorize-wear-vpn.ps1 -ConnectAddress '<WATCH-IP>:<DEBUG-PORT>'
```

macOS or Linux:

```sh
./tools/authorize-wear-vpn.sh --connect '<WATCH-IP>:<DEBUG-PORT>'
```

The required authorization is the `ACTIVATE_VPN` AppOp. It is **not** a runtime
permission and cannot be granted with `pm grant`.

See the official AOSP checks in
[`FeatureUtil.isConfigVpnSupported()`](https://android.googlesource.com/platform/cts/+/refs/heads/main/apps/CtsVerifier/src/com/android/cts/verifier/features/FeatureUtil.java)
and
[`VpnTest.supportedHardware()`](https://android.googlesource.com/platform/cts/+/4cb525394d52ecce303c18590d9955e098a22696/hostsidetests/net/app/src/com/android/cts/net/hostside/VpnTest.java).

Read [docs/wear-vpn-setup.md](docs/wear-vpn-setup.md) for pairing, device
selection, verification, optional always-on setup, revocation, and
troubleshooting.

## Build

The project retains Tailscale's Go/Android architecture. A build needs:

- JDK 17 or newer
- Go
- Android SDK with API 36 and NDK `23.1.7779620`
- `gomobile` and `gobind`

Generate `tailscale.version` with the existing project tooling, then build the
native Android archive:

```powershell
.\tools\build-native-local.ps1
```

Compile the app:

```powershell
cd android
.\gradlew.bat ktfmtCheck testDebugUnitTest lintDebug assembleDebug
```

The debug APK is written to:

```text
android/build/outputs/apk/debug/android-debug.apk
```

Local SDK files, generated version metadata, native archives, and APKs are
ignored by Git.

The checked-in lint baseline records inherited upstream Android cleanup work.
New lint findings still fail the build because `warningsAsErrors` remains
enabled.

## Security and privacy

Tailware creates an Android VPN interface and can therefore handle traffic
routed through it. Only install builds from a source you trust. The ADB helper
shows its target and proposed operation, refuses ambiguous device selection,
checks the installed package, and verifies the resulting VPN authorization.

## License and attribution

The source remains under the repository's BSD 3-Clause license. Existing
Tailscale copyright and license notices are retained. Tailscale and WireGuard
are trademarks of their respective owners.
