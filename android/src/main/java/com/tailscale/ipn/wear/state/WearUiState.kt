// Copyright (c) Tailware contributors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import com.tailscale.ipn.ui.model.Ipn

/**
 * Account state is deliberately independent from VPN authorization and tunnel state. A user can be
 * signed in while the watch still needs one-time VPN authorization.
 */
enum class WearAccountState {
  Loading,
  SignedOut,
  Authenticating,
  NeedsMachineAuthorization,
  SignedIn,
}

/** The state of Android's one-VPN-at-a-time authorization for Tailware. */
enum class VpnAuthorization {
  /** VpnService.prepare returned null: Tailware is currently authorized. */
  Granted,

  /** Wear OS needs the one-time ADB bootstrap before VpnService.prepare returns null. */
  AdbRequired,
}

internal fun vpnAuthorizationFromPrepared(vpnPrepared: Boolean): VpnAuthorization =
    if (vpnPrepared) VpnAuthorization.Granted else VpnAuthorization.AdbRequired

/** User-facing tunnel state, derived from both the IPN state and the established TUN state. */
enum class WearTunnelStatus {
  Unknown,
  Disconnected,
  Connecting,
  Connected,
  Disconnecting,
}

enum class TailwareLanguage(val languageTag: String) {
  English("en"),
  Dutch("nl"),
  French("fr"),
  German("de");

  companion object {
    fun fromStoredValue(value: String?): TailwareLanguage =
        entries.firstOrNull { it.name == value } ?: English
  }
}

/** What a tap on the connection control in the Wear OS Tile should do. */
enum class TileQuickAction {
  ToggleConnection,
  OpenApp;

  companion object {
    fun fromStoredValue(value: String?): TileQuickAction =
        entries.firstOrNull { it.name == value } ?: ToggleConnection
  }
}

enum class LoginMethod {
  Tailscale,
  AuthenticationKey,
  CustomServer,
}

enum class LoginPresentation {
  Phone,
  QrCode,
}

enum class PhoneLoginStatus {
  Idle,
  Opening,
  Sent,
  Failed,
}

enum class WearErrorOrigin {
  Global,
  InteractiveLogin,
  AuthenticationKey,
  CustomServer,
}

enum class WearAction {
  Connect,
  Disconnect,
  Login,
  Logout,
  ToggleDns,
  ToggleLanAccess,
  SelectExitNode,
  Ping,
}

data class WearError(
    val operation: WearAction?,
    val message: String,
    val origin: WearErrorOrigin = WearErrorOrigin.Global,
)

data class WearSelfNode(
    val stableId: String,
    val displayName: String,
    val dnsName: String,
    val ipv4: String?,
    val ipv6: String?,
    val addresses: List<String>,
    val tailnetName: String,
    val keyExpiry: String?,
)

data class WearPeer(
    val stableId: String,
    val displayName: String,
    val dnsName: String,
    val ipv4: String?,
    val ipv6: String?,
    val addresses: List<String>,
    val online: Boolean,
    val isSelf: Boolean,
    val os: String?,
    val lastSeen: String?,
    val keyExpiry: String?,
)

data class WearExitNode(
    val stableId: String,
    val displayName: String,
    val online: Boolean,
    val selected: Boolean,
    val active: Boolean,
    val isMullvad: Boolean,
    val countryCode: String?,
    val country: String?,
    val city: String?,
)

enum class WearHealthSeverity {
  Low,
  Medium,
  High,
}

data class WearHealthWarning(
    val code: String,
    val severity: WearHealthSeverity,
    val title: String,
    val message: String,
    val impactsConnectivity: Boolean,
)

enum class PingStatus {
  Idle,
  Running,
  Success,
  Failed,
}

data class WearPingResult(
    val peerId: String? = null,
    val peerName: String? = null,
    val status: PingStatus = PingStatus.Idle,
    val latencyMillis: Double? = null,
    val error: String? = null,
)

/**
 * Single immutable state consumed by all future Wear OS screens and the Tile state cache.
 *
 * The raw [Ipn.State] is retained for diagnostics, while [tunnelStatus] is the safer UI state: an
 * IPN `Running` event does not count as connected until Android reports an active TUN interface.
 */
data class WearUiState(
    val accountState: WearAccountState = WearAccountState.Loading,
    val ipnState: Ipn.State = Ipn.State.NoState,
    val tunnelStatus: WearTunnelStatus = WearTunnelStatus.Unknown,
    val desiredVpnEnabled: Boolean = false,
    val vpnAuthorization: VpnAuthorization = VpnAuthorization.AdbRequired,
    val profileId: String? = null,
    val profileDisplayName: String? = null,
    val tailnetName: String? = null,
    val selfNode: WearSelfNode? = null,
    val peers: List<WearPeer> = emptyList(),
    val exitNodes: List<WearExitNode> = emptyList(),
    val selectedExitNodeId: String? = null,
    val selectedExitNodeName: String? = null,
    val activeExitNodeId: String? = null,
    val activeExitNodeName: String? = null,
    val allowLanAccess: Boolean = false,
    val tailscaleDnsEnabled: Boolean = false,
    val underlyingNetworkStatus: com.tailscale.ipn.UnderlyingNetworkStatus =
        com.tailscale.ipn.UnderlyingNetworkStatus.Unknown,
    val healthWarnings: List<WearHealthWarning> = emptyList(),
    val language: TailwareLanguage = TailwareLanguage.English,
    val optionalNotificationsEnabled: Boolean = true,
    val optionalNotificationsBlockedBySystem: Boolean = false,
    val tileQuickAction: TileQuickAction = TileQuickAction.ToggleConnection,
    val tileShowIp: Boolean = true,
    val tileShowExitNode: Boolean = true,
    val tileShowMagicDns: Boolean = true,
    val activeLoginMethod: LoginMethod? = null,
    val loginPresentation: LoginPresentation = LoginPresentation.QrCode,
    val loginUrl: String? = null,
    val loginCode: String? = null,
    val phoneLoginStatus: PhoneLoginStatus = PhoneLoginStatus.Idle,
    val phoneLoginError: String? = null,
    val busyAction: WearAction? = null,
    val error: WearError? = null,
    val pingResult: WearPingResult = WearPingResult(),
)
