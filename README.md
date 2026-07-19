# Tailware

<p align="center">
  <img src="branding/tailware-logo.png" alt="Tailware logo" width="180">
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

- Guided first-run sign-in with browser/QR, auth key, or a custom
  Headscale/control-server URL
- A watch-first dashboard with one large connect/disconnect control
- Own device name, Tailscale IP addresses, MagicDNS name, tailnet, and tunnel
  status
- Tailnet device list with peer details and ping/latency testing
- Exit-node selection, exit-node clearing, and local-LAN access
- Tailscale DNS on/off
- English, Dutch, French, and German interface choices
- Optional connected/disconnected notifications
- In-app Tailware version, source attribution, and full BSD 3-Clause notice
- Wear OS Tile for status plus either a quick connection toggle or an
  open-app action, backed by a battery-friendly cached status
- VPN setup guidance, Android's normal consent flow, and a documented safe ADB
  fallback for watches that cannot display the system VPN dialog
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

Tailware first calls Android's standard `VpnService.prepare()` flow. Some Wear
OS builds do not expose a usable VPN-consent screen. For those watches, use the
repository's guarded PowerShell helper after installing the APK and connecting
the watch through wireless debugging:

```powershell
.\tools\authorize-wear-vpn.ps1
```

The required authorization is the `ACTIVATE_VPN` AppOp. It is **not** a runtime
permission and cannot be granted with `pm grant`.

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
