# Tailware

<p align="center">
  <img src="branding/tailware-mark.svg" alt="Tailware network T logo" width="160">
</p>

<p align="center">
  <strong>Your private network, on your wrist.</strong>
</p>

<p align="center">
  <a href="docs/OPENAI-BUILD-WEEK.md"><img alt="Built with Codex and GPT-5.6" src="https://img.shields.io/badge/OpenAI%20Build%20Week-Codex%20%2B%20GPT--5.6-000000?logo=openai&amp;logoColor=white"></a>
  <a href="https://github.com/vladbezm/tailware/releases/tag/v0.1.0-private.6"><img alt="Release v0.1.0-private.6" src="https://img.shields.io/badge/release-v0.1.0--private.6-6f42c1"></a>
  <a href="#supported-platforms"><img alt="Wear OS 3 or newer" src="https://img.shields.io/badge/Wear%20OS-3%2B-4285F4?logo=wearos&amp;logoColor=white"></a>
  <a href="#languages"><img alt="28 interface languages" src="https://img.shields.io/badge/languages-28-00897B"></a>
  <a href="LICENSE"><img alt="BSD 3-Clause license" src="https://img.shields.io/badge/license-BSD--3--Clause-blue"></a>
</p>

Tailware is an independent, open-source Wear OS client that makes a compatible
smartwatch its own tailnet node. The tunnel runs on the watch; a companion phone
is useful for sign-in but is not required for everyday networking.

> **Early private preview.** `v0.1.0-private.6` is a debug-signed, sideload-only
> test build, not a production release. It needs one-time VPN authorization over
> wireless ADB and access to this private repository. Read the
> [limitations](#limitations) before testing.

## Why Tailware

Wear OS apps can be useful front ends for private home, vehicle, workstation,
and monitoring tools, but exposing every service to the public internet is the
wrong trade-off. Tailware gives the watch a Tailscale identity, address, and
MagicDNS name so suitable watch apps can reach services allowed by the existing
tailnet policy.

This is a real network node, not a remote control for a phone-side VPN.

## Current feature set

- Watch-first connect/disconnect dashboard with the current tunnel state,
  device identity, Tailscale addresses, MagicDNS name, and tailnet
- Tailnet device list, peer details, and Tailscale disco ping with latency
- Existing exit-node selection, clearing, and local-LAN access control
- Tailscale DNS toggle and classified account, DNS, clock, relay, internet,
  and exit-node health guidance
- Sign-in through paired-phone handoff, QR/manual login, Tailscale auth key, or
  a compatible custom HTTPS control server such as Headscale
- Wear OS Tile with compact status, network details, and a configurable quick
  toggle/open-app action
- Optional connection alerts, separate from Android's required active-VPN
  foreground notification
- Explicit-ON reboot/update recovery while preserving an explicit OFF choice
- Guided one-time VPN authorization with guarded Windows and macOS/Linux tools
- 28 complete interface language packs, including RTL, Indic, CJK, and
  European-language coverage

## Architecture and technical depth

| Layer | Tailware implementation |
|---|---|
| Watch experience | Kotlin, Jetpack Compose for Wear OS, Compose navigation, and Wear Tiles |
| State and lifecycle | Attempt-, epoch-, and generation-owned login/VPN operations; current-TUN-based status; fail-safe OFF persistence; reboot and foreground-service coordination |
| Android tunnel | `VpnService`, foreground notification ownership, Android TUN file descriptor, and JNI-generated Android archive |
| Network core | Tailscale's Go `LocalBackend`, `wgengine`, `wireguard-go`, DNS/routing, and a replaceable multi-TUN bridge |

The UI does not call a connection “connected” merely because the Go backend is
running: it also requires current native TUN evidence. Stale callbacks and
superseded connect, disconnect, login, Tile, and teardown attempts are fenced
by ownership identifiers, with focused race tests covering those boundaries.

## Supported platforms

| Requirement | Current support |
|---|---|
| Watch OS | Wear OS 3 or newer; Android API 30 minimum, API 36 target |
| Form factor | Standalone watch app; round and small-screen layouts; no daily companion app |
| Connectivity | The watch still needs working Wi-Fi or LTE |
| Control plane | Tailscale, or a compatible custom HTTPS control server; Headscale versions/configurations are not yet broadly certified |
| Initial hardware target | Samsung Galaxy Watch5 Pro (`SM-R925F`) |
| APK ABIs | `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` |

Other Wear OS 3+ watches should meet the declared platform requirements, but
they have not yet been validated as a compatibility matrix.

## Install the pinned test build

You need a Wear OS 3+ watch, a computer with
[Android Platform-Tools 30+](https://developer.android.com/tools/releases/platform-tools),
and a GitHub account that can read this private repository.

1. Download
   **[Tailware-0.1.0-private.6.apk](https://github.com/vladbezm/tailware/releases/download/v0.1.0-private.6/Tailware-0.1.0-private.6.apk)**
   from the [pinned prerelease](https://github.com/vladbezm/tailware/releases/tag/v0.1.0-private.6).
2. Verify its SHA-256:

   ```text
   9FCA20A993B8560C7852E34879DABE9FAE89CFD109745DB2C8D5317B75DEB178
   ```

   The release also contains
   [SHA256SUMS.txt](https://github.com/vladbezm/tailware/releases/download/v0.1.0-private.6/SHA256SUMS.txt).
3. On the watch, enable Developer options and Wireless debugging. Pair and
   connect with the two different ports shown by Wear OS:

   ```text
   adb pair <WATCH-IP>:<PAIRING-PORT>
   adb connect <WATCH-IP>:<DEBUG-PORT>
   adb devices -l
   ```

4. Install to the exact ready watch serial:

   ```text
   adb -s <WATCH-IP>:<DEBUG-PORT> install -r Tailware-0.1.0-private.6.apk
   ```

### One-time Wear OS VPN authorization

Wear OS does not expose a reliable interactive VPN confirmation flow for this
use case. After installation, run the repository's guarded helper. It shows the
target watch, Android user, package, current AppOp, and always-on provider before
asking for confirmation.

Windows PowerShell:

```powershell
.\tools\authorize-wear-vpn.ps1 -Serial '<WATCH-IP>:<DEBUG-PORT>'
```

macOS or Linux:

```sh
chmod +x ./tools/authorize-wear-vpn.sh
./tools/authorize-wear-vpn.sh --serial '<WATCH-IP>:<DEBUG-PORT>'
```

The helper grants only the `ACTIVATE_VPN` AppOp, verifies the result, and fails
instead of guessing when device selection is ambiguous. It does not root the
watch, unlock its bootloader, or enable VPN lockdown. `ACTIVATE_VPN` is not a
runtime permission, so `pm grant` is the wrong mechanism.

Read the full **[Wear OS VPN authorization guide](docs/wear-vpn-setup.md)** for
pairing, dry-run/preview, custom ADB paths, optional always-on mode (lockdown
remains disabled), transactional revocation, and troubleshooting. Turn off
Wireless debugging and remove the paired computer when testing is complete.

## Sign in and test without rebuilding

Launch Tailware and choose one login path:

- **Continue on phone** for the simplest interactive Tailscale login, with QR
  and manual URL fallback
- **Tailscale authentication key** for controlled provisioning; keep the key
  private and short-lived where possible
- **Headscale / custom server** for a compatible HTTPS control URL, followed by
  interactive login or an auth key as appropriate

A concise evaluator path is:

1. Sign in, tap the main connection control, and wait for **Connected**.
2. Confirm the watch identity, Tailscale IP, MagicDNS name, and tailnet.
3. Open **Devices**, select an online peer, and run the latency test.
4. Add the Tailware Tile and compare its status with the app.
5. If the test tailnet provides one, select an exit node, toggle local-LAN
   access, then clear the exit node again.
6. Toggle Tailscale DNS and restore the evaluator's preferred setting.
7. Disconnect and confirm that both the dashboard and Tile settle to OFF.

Use a non-sensitive test tailnet. Never publish login URLs, auth keys, pairing
codes, local IP addresses, or device serials in screenshots or logs.

## Build from source

Prerequisites are JDK 17, the Go version declared in `go.mod`, Android SDK API
36 with build-tools 35, NDK `23.1.7779620`, `gomobile`, and `gobind`.

On Linux/macOS or a suitable Unix environment, the project build path is:

```sh
make tailware-debug
```

It produces `tailware-debug.apk`. On Windows, after installing `gomobile` and
`gobind`, build the native archive and app with:

```powershell
go install golang.org/x/mobile/cmd/gomobile
go install golang.org/x/mobile/cmd/gobind
.\tools\build-native-local.ps1 -SdkRoot 'C:\path\to\android-sdk'
Push-Location android
.\gradlew.bat ktfmtCheck test lintDebug assembleDebug
Pop-Location
```

The Gradle output is `android/build/outputs/apk/debug/android-debug.apk`. This
private branch is documented and validated as a debug/test build; do not treat
it as a reproducible production-signing recipe.

### Validation commands

The principal repository checks are:

```sh
make fmt-check
make lint
make go-test
make test
python tools/validate-tailware-locales.py --run-fixtures
python tools/validate-wear-vpn-bootstrap.py --mode development --run-fixtures
```

Release evidence for `v0.1.0-private.6` includes 218 focused and 726 full JVM
test executions; Go tests and vet; ktfmt; Android lint; locale and VPN-bootstrap
validators; native binding; and APK signature, alignment, and provenance checks.
The locale validator reports 198 translatable strings, 44 managed-configuration
strings, and complete key coverage across 28 languages. A roughly five-minute
passive smoke test on an `SM-R925F` was clean; **the VPN was not connected during
that smoke test**. These checks are evidence, not a security audit or universal
device guarantee.

## Languages

Tailware currently bundles English, Arabic, Bengali, Czech, German, Greek,
Spanish, Persian, French, Hindi, Hungarian, Indonesian, Italian, Hebrew,
Japanese, Korean, Dutch, Polish, Portuguese, Romanian, Russian, Swedish, Thai,
Turkish, Ukrainian, Urdu, Vietnamese, and Chinese.

Every pack passes structural and completeness validation. Native-speaker review
is still welcome, especially for short watch-specific wording.

## Safety and privacy

- Tailware creates an Android VPN interface and can handle traffic routed
  through it. Install only an APK whose source, signer, and checksum you trust.
- Native Tailscale state is stored through encrypted preferences and Android
  backups are disabled. Some operational preferences and Tile cache data remain
  ordinary app-private, unencrypted storage.
- The inherited Tailscale backend supports remote diagnostic-log upload. In
  this preview it defaults to enabled, and the Wear UI does not yet expose an
  opt-out. Do not describe this build as “zero telemetry.”
- Auth keys remain visible while entered and verified. Avoid screenshots, use
  narrowly scoped/short-lived keys, and revoke any key that may have leaked.
- The authorization helpers deliberately scope and verify their changes, but
  ADB access is powerful. Check the displayed serial/package and disable
  Wireless debugging afterward.
- Optional Android always-on VPN is separate from Tailware's saved switch state.
  The helpers keep lockdown disabled so a failed or signed-out preview cannot
  intentionally cut off all watch networking.
- Tailware has not had an independent security, privacy, accessibility, or
  battery audit and is not a medical, emergency, or life-safety service.

## Limitations

- Private, debug-signed prerelease; no Play Store distribution or production
  support commitment
- One-time computer and wireless-ADB setup before Android permits the VPN
- Broad OEM, firmware, roaming, battery, thermal, and accessibility validation
  is still outstanding
- Initial physical focus is Galaxy Watch5 Pro; the recorded release smoke test
  was passive and did not exercise a live VPN
- Existing tailnet ACLs/grants, subnet routers, exit nodes, and internet access
  must already be configured; Tailware does not create them
- Custom control-server/Headscale compatibility depends on server version and
  configuration
- The current Wear UI is intentionally narrower than the upstream Android app:
  it is not a full tailnet administration, Taildrop, SSH, or account-switching
  client

## OpenAI Build Week

Tailware was built during OpenAI Build Week with Codex powered by GPT-5.6 as an
engineering workflow—not as a runtime app feature. Codex accelerated repository
mapping, parallel review, Wear implementation, lifecycle/race hardening, test
generation, localization, validation, and release documentation.

Vlad chose the problem, target user and watch, standalone product shape,
Tailscale-derived architecture, v1 scope, safety boundaries, one-time ADB
trade-off, no-lockdown policy, private preview status, and final release
decisions. He retained control of accounts, credentials, hardware actions, and
publication. See **[the Build Week fact sheet](docs/OPENAI-BUILD-WEEK.md)** for
the exact submission, testing, upstream, and Codex-session details.

Codex and GPT-5.6 are development tools for this repository. Tailware does not
call an OpenAI model or API while running on the watch.

### What changed during the submission period

Starting from the upstream Android client, the Build Week work added the
standalone Wear OS product layer and branding; watch-first dashboard,
navigation, devices/ping, exit-node, DNS, notification, login, and Tile flows;
28 Wear language packs; guarded VPN-authorization helpers; lifecycle and race
hardening across Kotlin and Go; focused regression tests and validators; and
the private test-release and evaluator documentation.

## Upstream, license, and independence

Tailware is derived from the BSD 3-Clause
[Tailscale Android client](https://github.com/tailscale/tailscale-android), with
the fork based on upstream commit
[`e181e9c`](https://github.com/tailscale/tailscale-android/commit/e181e9c471c0170bafce523936da433df993f9b6).
The Go networking engine, WireGuard datapath, and substantial Android
foundations are upstream work; Tailware's Wear product layer and safety changes
are described in the [Build Week fact sheet](docs/OPENAI-BUILD-WEEK.md).

Source is distributed under the repository's [BSD 3-Clause license](LICENSE).
Existing copyright notices are retained; see also [PATENTS](PATENTS).

**Tailware is an independent, unofficial project. It is not affiliated with,
endorsed by, or supported by Tailscale Inc.** Tailscale and WireGuard are
trademarks of their respective owners.

## Short roadmap

- Validate live VPN behavior, roaming, battery, accessibility, and UI on a
  broader Wear OS hardware matrix
- Add explicit diagnostic/privacy controls and publication-grade privacy and
  security documentation
- Establish reproducible signed releases, provenance, rollback, disclosure,
  and compatibility processes before any public preview
- Refine onboarding and watch-native diagnostics from tester feedback
