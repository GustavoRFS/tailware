# OpenAI Build Week: Tailware submission facts

This page is the concise, English source of truth for judges and testers. The
main [README](../README.md) explains the product and complete setup path; the
separate [Dutch value document](TAILWARE-MEERWAARDE.md) provides broader product
analysis.

## Submission snapshot

| Item | Fact |
|---|---|
| Project | **Tailware — Your private network, on your wrist** |
| Entrant | Vlad |
| Recommended track | **Developer Tools (security)** |
| Submission status | Early private preview; debug-signed sideload test build |
| Version | `0.1.0-private.6` (`versionCode 10006`) |
| Android package | `io.github.vladbezm.tailware` |
| Platform | Standalone Wear OS 3+ app; Android API 30 minimum / API 36 target |
| Initial watch target | Samsung Galaxy Watch5 Pro (`SM-R925F`) |
| Development workflow | Codex powered by GPT-5.6 |
| Runtime AI | None; Tailware does not call OpenAI models or APIs on the watch |
| Codex session/thread ID | `019f7ac6-17a1-7a73-99a7-88f91d8f6500` |
| Upstream | `tailscale/tailscale-android`, BSD 3-Clause |

The repository remains private for this preview. A GitHub account must have
read access before any repository, guide, release, or APK link below will open.
The judging accounts associated with `testing@devpost.com` and
`build-week-event@openai.com` therefore need repository access; this document
does not claim that an invitation has already been accepted.

## Exact links

- Repository: <https://github.com/vladbezm/tailware>
- Submission branch: <https://github.com/vladbezm/tailware/tree/agent/wear-v1>
- Draft pull request: <https://github.com/vladbezm/tailware/pull/1>
- Pinned prerelease: <https://github.com/vladbezm/tailware/releases/tag/v0.1.0-private.6>
- APK: <https://github.com/vladbezm/tailware/releases/download/v0.1.0-private.6/Tailware-0.1.0-private.6.apk>
- Checksums: <https://github.com/vladbezm/tailware/releases/download/v0.1.0-private.6/SHA256SUMS.txt>
- Private-branch VPN setup guide: <https://github.com/vladbezm/tailware/blob/agent/wear-v1/docs/wear-vpn-setup.md>
- Upstream repository: <https://github.com/tailscale/tailscale-android>
- Upstream fork base: <https://github.com/tailscale/tailscale-android/commit/e181e9c471c0170bafce523936da433df993f9b6>

Pinned APK SHA-256:

```text
9FCA20A993B8560C7852E34879DABE9FAE89CFD109745DB2C8D5317B75DEB178
```

## Problem and Build Week result

Vlad builds Codex-assisted Wear OS apps for voice control of private car, home,
computer, and phone functions and for monitoring work devices and data. Those
tools need secure reachability without publishing each service to the internet.
Tailware addresses that network-layer gap by making the watch a standalone
tailnet node.

During the submission period, the upstream Android client was turned into a
watch-first product with:

- a standalone Wear OS manifest, branding, navigation, dashboard, and Tile;
- paired-phone/QR login, auth-key provisioning, and custom HTTPS control-server
  onboarding;
- watch identity, peer browsing, peer detail and latency testing, exit-node and
  local-LAN control, DNS, notifications, and localized health guidance;
- one-time Wear OS VPN authorization guides and guarded PowerShell/POSIX tools;
- explicit desired-state persistence, reboot/update recovery, and extensive
  containment of stale login, VPN, TUN, foreground-service, and Tile work;
- 28 complete language packs with structural/completeness validation; and
- focused Kotlin/Go tests, repository validators, CI gates, release packaging,
  privacy/limitation disclosure, and tester documentation.

## Upstream versus Tailware work

Tailware is a derivative, not a clean-room VPN implementation. Attribution is
part of the submission.

| Pre-existing/upstream Tailscale work | Tailware Build Week work |
|---|---|
| Tailscale Go `LocalBackend`, `wgengine`, WireGuard datapath, routing, DNS, control-plane protocol, and much of the JNI/Android networking foundation | Standalone Wear OS product shape, package identity/versioning, branding, Compose UI/navigation, watch dashboard, devices/ping, settings, health presentation, and Tile |
| Existing Android `VpnService`, TUN integration, encrypted state mechanism, foreground-service foundation, build system, and upstream tests | Wear-specific login/onboarding, paired-phone handoff guards, one-time VPN authorization helpers/docs, desired-state recovery, attempt/epoch/generation ownership, teardown/race hardening, and focused regression tests |
| BSD 3-Clause source and retained Tailscale copyright notices | Tailware modifications, 28 Wear language packs and validators, CI/release gates, documentation, and private test release |

The fork starts from upstream commit
[`e181e9c`](https://github.com/tailscale/tailscale-android/commit/e181e9c471c0170bafce523936da433df993f9b6).
Tailware retains [LICENSE](../LICENSE), [PATENTS](../PATENTS), and existing
source notices. It does not claim the Tailscale engine, WireGuard implementation,
or inherited Android foundations as new Build Week work.

**Tailware is independent and unofficial. It is not affiliated with, endorsed
by, or supported by Tailscale Inc.** Tailscale and WireGuard are trademarks of
their respective owners.

## Judging alignment

| Evaluation lens | Concrete evidence |
|---|---|
| Developer Tools (security) | Gives private watch tools an authenticated tailnet route instead of requiring each service to be publicly exposed; honors existing ACLs/grants rather than replacing them |
| Useful product | A real on-watch node with a short daily interaction path: connect, inspect identity, find a peer, test latency, and use a Tile |
| Technical execution | Compose for Wear OS over Android `VpnService` and a JNI-bound Go Tailscale engine; current-TUN connection truth and ownership-fenced async lifecycle work |
| Watch-native UX | Round/small-screen layouts, phone handoff with QR fallback, concise health messages, Tile controls, and 28 complete language packs |
| Responsible delivery | Pinned APK and hash, explicit private/debug status, narrowly scoped and verified AppOp helpers, no VPN lockdown, upstream attribution, and candid privacy/test limits |
| Codex collaboration | GPT-5.6 supported repository archaeology, parallel specialist review, implementation, adversarial race analysis, tests, localization, validation, and documentation while the human retained product and release authority |

## Fast judging path: no rebuild required

### Prerequisites

- GitHub read access to the private repository/release
- Wear OS 3+ watch or compatible Wear emulator
- Android Platform-Tools 30 or newer
- A non-sensitive Tailscale test tailnet, or a compatible custom HTTPS control
  server
- A computer that can reach the watch/emulator with ADB

### Install and authorize

1. Download the exact APK above and verify the SHA-256.
2. Enable Wireless debugging on the watch, then use its distinct pairing and
   debugging ports:

   ```text
   adb pair <WATCH-IP>:<PAIRING-PORT>
   adb connect <WATCH-IP>:<DEBUG-PORT>
   adb devices -l
   adb -s <WATCH-IP>:<DEBUG-PORT> install -r Tailware-0.1.0-private.6.apk
   ```

3. From the repository root, authorize only Tailware's `ACTIVATE_VPN` AppOp:

   Windows:

   ```powershell
   .\tools\authorize-wear-vpn.ps1 -Serial '<WATCH-IP>:<DEBUG-PORT>'
   ```

   macOS/Linux:

   ```sh
   chmod +x ./tools/authorize-wear-vpn.sh
   ./tools/authorize-wear-vpn.sh --serial '<WATCH-IP>:<DEBUG-PORT>'
   ```

4. Read the helper summary before approving. The operation is not root, does
   not unlock the bootloader, and does not enable lockdown. The full
   [setup guide](wear-vpn-setup.md) covers dry-run, device selection,
   troubleshooting, always-on, and revocation.

### Exercise the product

1. Open Tailware and sign in using paired-phone handoff, QR/manual login, an
   auth key, or a compatible custom server.
2. Connect and confirm the watch's current Tailscale IP, MagicDNS name, and
   tailnet.
3. Open **Devices**, select an online peer, and run the latency test.
4. Add the Tailware Tile and compare its state with the dashboard.
5. If the test tailnet provides an exit node, select it, test local-LAN access,
   and clear it again. Restore the preferred DNS setting after testing.
6. Disconnect and verify that the app and Tile settle to OFF.
7. Disable Wireless debugging and remove the ADB pairing.

Do not expose auth keys, login URLs, pairing codes, device serials, or private
addresses in a recording or bug report.

## Validation evidence

The verified `v0.1.0-private.6` release evidence is:

- 218 focused and 726 full JVM test executions passed;
- Go tests and vet passed;
- ktfmt and Android lint passed;
- locale and VPN-bootstrap validators passed;
- native Go-to-Android binding passed;
- APK signature, alignment, provenance, version, package, and SHA checks passed;
- locale validation covered 198 translatable strings and 44 managed-config
  strings across all 28 languages; and
- a roughly five-minute passive physical smoke test on `SM-R925F` was clean.

The final physical smoke test deliberately did **not** connect the VPN. It
validated packaging/install stability, not live tunnel behavior, roaming,
battery life, or universal device compatibility.

Reproduction commands for a source checkout are listed in the
[README](../README.md#validation-commands). The attached APK is the recommended
judge path; rebuilding is not required.

## Codex + GPT-5.6 workflow

Codex was the engineering environment for this project, not a runtime feature.
The workflow followed a human-directed, evidence-driven loop:

1. **Human goal and constraints:** Vlad supplied the secure-connectivity use
   case, target hardware, watch-first scope, safety boundaries, and definition
   of done.
2. **Repository mapping:** Codex traced the inherited Android/Go architecture
   before proposing Wear-specific changes.
3. **Parallel specialist passes:** agents inspected networking/lifecycle,
   onboarding, UI/Tile behavior, localization, tests, safety, and documentation
   independently, then returned bounded findings to a coordinating thread.
4. **Implementation and adversarial review:** GPT-5.6 generated and revised
   code/tests, especially around rapid toggles, stale callbacks, login epochs,
   foreground ownership, TUN teardown, reboot restoration, and fail-safe OFF
   behavior.
5. **Executable evidence:** Codex ran targeted and full tests, validators,
   formatting, lint, native binding, APK inspection, and provenance checks;
   failures and unresolved physical-test boundaries were recorded rather than
   hidden.
6. **Human-controlled delivery:** Vlad retained final product judgment and
   control of credentials, account access, watch actions, release visibility,
   publication, and submission.

This use of parallel agents is consistent with Codex's documented
[subagent workflow](https://learn.chatgpt.com/docs/agent-configuration/subagents):
independent exploration and validation were parallelized, while requirements,
trade-offs, and synthesis remained in the main thread. The exact submission
session/thread ID is:

```text
019f7ac6-17a1-7a73-99a7-88f91d8f6500
```

### Human decisions versus Codex acceleration

| Vlad decided and controlled | Codex/GPT-5.6 accelerated |
|---|---|
| Personal problem, Developer Tools/security positioning, target watch, users, and feature priorities | Codebase exploration, dependency tracing, architecture explanations, and scoped implementation plans |
| Standalone watch node, upstream Tailscale base, narrow v1 UI, and acceptable one-time ADB setup | Compose/Wear implementation, Go/Android integration changes, validators, tests, and documentation |
| No root, no lockdown, explicit device selection, preserve explicit OFF, private debug preview | Threat/race modeling, ownership fences, fail-safe paths, regression tests, and helper hardening |
| Credentials, live account and hardware operations, what evidence was sufficient, and whether/when to publish | Repeatable checks, APK metadata/hash inspection, limitation discovery, localization coverage, and release preparation |

No claim is made that the model independently originated the upstream network
engine or made the final product/release decisions.

## Known limits a judge should retain

- The release and repository are private and the APK is debug-signed.
- One-time wireless ADB authorization is required; this is not a consumer-store
  onboarding flow.
- Broad hardware, firmware, roaming, battery, thermal, accessibility, privacy,
  and security validation remains outstanding.
- The inherited backend can upload remote diagnostic logs; this preview defaults
  that preference to enabled and does not yet expose a Wear opt-out.
- Auth keys are visible during entry; use a non-sensitive tailnet and careful key
  hygiene.
- Headscale/custom-server compatibility depends on server version and setup.
- Tailware is not a full tailnet administrator, official Tailscale client,
  medical device, emergency channel, or production security guarantee.
