// Copyright (c) Tailware contributors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.VpnService
import android.os.Build
import android.os.LocaleList
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.IPNService
import com.tailscale.ipn.NetworkChangeCallback
import com.tailscale.ipn.UnderlyingNetworkStatus
import com.tailscale.ipn.ui.Links
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Health
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.viewModel.IpnViewModel
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.wear.tile.TailwareTileUpdates
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal fun shouldAcceptLoginUrl(
    activeAttemptGeneration: Long?,
    candidateUrl: String?,
): Boolean = activeAttemptGeneration != null && !candidateUrl.isNullOrBlank()

internal fun isCurrentLoginAttempt(activeGeneration: Long?, expectedGeneration: Long): Boolean =
    activeGeneration == expectedGeneration

internal fun shouldUseLocalOnlyLogout(networkStatus: UnderlyingNetworkStatus): Boolean =
    networkStatus != UnderlyingNetworkStatus.Online

internal fun resolveWearAccountState(
    ipnState: Ipn.State,
    hasProfile: Boolean,
    hasLoginUrl: Boolean,
    hasActiveLoginAttempt: Boolean,
): WearAccountState =
    when {
      ipnState == Ipn.State.NeedsMachineAuth -> WearAccountState.NeedsMachineAuthorization
      // NeedsLogin is authoritative even while LocalAPI still exposes the old profile. This is
      // common during key expiry and reauthentication.
      ipnState == Ipn.State.NeedsLogin ->
          if (hasLoginUrl || hasActiveLoginAttempt) {
            WearAccountState.Authenticating
          } else {
            WearAccountState.SignedOut
          }
      hasProfile -> WearAccountState.SignedIn
      hasLoginUrl || hasActiveLoginAttempt -> WearAccountState.Authenticating
      else -> WearAccountState.Loading
    }

internal fun pingFailureMessage(
    peerName: String,
    address: String,
    diagnostic: String?,
): String {
  val detail = diagnostic?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
  return when {
    detail.contains("timeout", ignoreCase = true) ||
        detail.contains("deadline exceeded", ignoreCase = true) ->
        "Ping to $peerName ($address) timed out. The device may be asleep, offline, or unreachable."
    detail.isBlank() ->
        "Ping to $peerName ($address) failed without a diagnostic from the Tailscale backend."
    else -> "Ping to $peerName ($address) failed: ${detail.trimEnd('.')}"
  }
}

/**
 * Wear-specific application state and actions layered on the existing Android Tailscale backend.
 *
 * This class contains no Compose or Activity code. Android's current VpnService state is the only
 * authoritative authorization signal.
 */
class TailwareViewModel : IpnViewModel() {
  private val app = App.get()
  private val appViewModel = app.getAppScopedViewModel()
  private val localPreferences = app.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
  private var actionGeneration = 0L
  private var busyTimeoutJob: Job? = null
  private var acceptLoginUrls = false
  private var loginAttemptGeneration = 0L
  private var activeLoginAttemptGeneration: Long? = null
  private var autoConnectedLoginGeneration: Long? = null
  private var loginCancellationInProgress = false
  private var loginCancellationGeneration = 0L
  private var loginCleanupRequired = false
  private var loginForegroundProtectionActive = false
  private var activityRecreationInProgress = false
  private var pendingLoginStart: (() -> Unit)? = null
  private var postCleanupError: WearError? = null
  private var automaticVpnRestoreAttempted = false
  private var logoutProfile: IpnLocal.LoginProfile? = null
  private var logoutCompletionNotice: WearError? = null

  private val _uiState =
      MutableStateFlow(
          WearUiState(
              language =
                  TailwareLanguage.fromStoredValue(localPreferences.getString(KEY_LANGUAGE, null)),
              desiredVpnEnabled = TailwareVpnStateStore.desiredVpnEnabled(app),
              optionalNotificationsEnabled = initialOptionalNotificationsEnabled(),
              optionalNotificationsBlockedBySystem = initialOptionalNotificationsBlocked(),
              tileQuickAction =
                  TileQuickAction.fromStoredValue(
                      localPreferences.getString(KEY_TILE_QUICK_ACTION, null)),
              tileShowIp = localPreferences.getBoolean(KEY_TILE_SHOW_IP, true),
              tileShowExitNode = localPreferences.getBoolean(KEY_TILE_SHOW_EXIT_NODE, true),
              tileShowMagicDns = localPreferences.getBoolean(KEY_TILE_SHOW_MAGIC_DNS, true),
          ))
  val uiState: StateFlow<WearUiState> = _uiState.asStateFlow()

  private data class BackendSnapshot(
      val ipnState: Ipn.State,
      val netmap: Netmap.NetworkMap?,
      val prefs: Ipn.Prefs?,
      val profile: IpnLocal.LoginProfile?,
      val profiles: List<IpnLocal.LoginProfile>?,
      val vpnActive: Boolean = false,
      val vpnPrepared: Boolean = false,
      val underlyingNetworkStatus: UnderlyingNetworkStatus = UnderlyingNetworkStatus.Unknown,
      val healthWarnings: Set<Health.UnhealthyState> = emptySet(),
  )

  init {
    clearLegacyLoginUrlPersistence()
    applyAppLocale(_uiState.value.language)
    // LoginFinished is modeled upstream as state even though it is an event. Never replay a
    // completion from an older Activity/ViewModel into a new login attempt.
    Notifier.loginFinished.set(null)
    observeBackend()
    observeLoginUrl()
    observeLoginCompletion()
    observeLoginErrors()
    refreshVpnAuthorization(app)
  }

  private fun observeBackend() {
    val healthWarnings =
        app.healthNotifier?.currentWarnings ?: flowOf<Set<Health.UnhealthyState>>(emptySet())
    viewModelScope.launch {
      combine(Notifier.state, Notifier.netmap, Notifier.prefs, loggedInUser, loginProfiles) {
              ipnState,
              netmap,
              prefs,
              profile,
              profiles ->
            BackendSnapshot(ipnState, netmap, prefs, profile, profiles)
          }
          .combine(appViewModel.vpnActive) { snapshot, active -> snapshot.copy(vpnActive = active) }
          .combine(appViewModel.vpnPrepared) { snapshot, prepared ->
            snapshot.copy(vpnPrepared = prepared)
          }
          .combine(NetworkChangeCallback.underlyingNetworkStatus) { snapshot, networkStatus ->
            snapshot.copy(underlyingNetworkStatus = networkStatus)
          }
          .combine(healthWarnings) { snapshot, warnings ->
            snapshot.copy(healthWarnings = warnings)
          }
          .collect(::applyBackendSnapshot)
    }
  }

  private fun observeLoginUrl() {
    viewModelScope.launch {
      Notifier.browseToURL.collect { url ->
        if (url == null) {
          if (acceptLoginUrls) {
            _uiState.update { it.copy(loginUrl = null, loginCode = null) }
          }
          return@collect
        }
        if (activeLoginAttemptGeneration == null &&
            (Notifier.state.value == Ipn.State.NeedsLogin ||
                Notifier.state.value == Ipn.State.NeedsMachineAuth)) {
          beginBackendReauthentication()
        }
        // Cleanup completes before a new generation starts. Therefore every non-empty URL emitted
        // while this generation is active belongs to this attempt, even when Headscale correctly
        // reuses the exact same URL text as an earlier attempt.
        if (!shouldAcceptLoginUrl(activeLoginAttemptGeneration, url)) {
          return@collect
        }
        busyTimeoutJob?.cancel()
        _uiState.update { current ->
          current.copy(
              accountState = WearAccountState.Authenticating,
              loginUrl = url,
              loginCode = loginCodeFromUrl(url),
              phoneLoginStatus = PhoneLoginStatus.Idle,
              phoneLoginError = null,
              busyAction = null,
          )
        }
      }
    }
  }

  private fun observeLoginCompletion() {
    viewModelScope.launch {
      Notifier.loginFinished.filterNotNull().collect {
        val generation = activeLoginAttemptGeneration
        if (generation == null) {
          Notifier.loginFinished.set(null)
          return@collect
        }
        acceptLoginUrls = false
        busyTimeoutJob?.cancel()
        Notifier.browseToURL.set(null)
        Notifier.loginFinished.set(null)
        tryAutoConnectAfterLogin(generation)
        _uiState.update {
          it.copy(
              loginUrl = null,
              loginCode = null,
              phoneLoginStatus = PhoneLoginStatus.Idle,
              phoneLoginError = null,
          )
        }
      }
    }
  }

  private fun observeLoginErrors() {
    viewModelScope.launch {
      Notifier.errMessage.filterNotNull().collect { backendMessage ->
        Notifier.errMessage.set(null)

        val currentMethod = _uiState.value.activeLoginMethod
        val origin = currentMethod.errorOrigin
        val readableBackendMessage =
            backendMessage.replace(Regex("\\s+"), " ").trim().take(MAX_BACKEND_ERROR_LENGTH)
        val message =
            when (currentMethod) {
              LoginMethod.AuthenticationKey ->
                  if (_uiState.value.underlyingNetworkStatus == UnderlyingNetworkStatus.Offline) {
                    "The authentication key could not be checked because the watch is offline."
                  } else {
                    "Authentication failed: $readableBackendMessage Check that the key is valid and not expired."
                  }
              LoginMethod.CustomServer ->
                  "The custom server refused the sign-in request: $readableBackendMessage"
              LoginMethod.Tailscale,
              null -> "Tailscale could not start sign-in: $readableBackendMessage"
            }

        val attemptGeneration = activeLoginAttemptGeneration
        if (attemptGeneration == null) {
          if (Notifier.state.value == Ipn.State.NeedsLogin ||
              Notifier.state.value == Ipn.State.NeedsMachineAuth) {
            setError(WearAction.Login, message, origin)
          }
          return@collect
        }
        startLoginCancellation(
            WearError(
                operation = WearAction.Login,
                message = message,
                origin = origin,
            ),
            expectedAttemptGeneration = attemptGeneration,
        )
      }
    }
  }

  private fun applyBackendSnapshot(snapshot: BackendSnapshot) {
    val hasProfile = snapshot.profile != null && !snapshot.profile.isEmpty()
    val signedInSnapshot =
        hasProfile &&
            snapshot.ipnState != Ipn.State.NeedsLogin &&
            snapshot.ipnState != Ipn.State.NeedsMachineAuth
    val completedLoginGeneration = activeLoginAttemptGeneration?.takeIf { signedInSnapshot }
    if (signedInSnapshot) {
      acceptLoginUrls = false
      if (Notifier.browseToURL.value != null) Notifier.browseToURL.set(null)
      if (Notifier.loginFinished.value != null) Notifier.loginFinished.set(null)
    }
    _uiState.update { current ->
      val resolvedAccountState = accountState(snapshot, current)
      val self = snapshot.netmap?.SelfNode.takeIf { hasProfile }
      val profileTailnetName =
          snapshot.profile?.NetworkProfile?.tailnetNameForDisplay()?.takeIf { it.isNotBlank() }
      val tailnetName =
          if (hasProfile) {
            profileTailnetName
                ?: snapshot.netmap?.Domain?.takeIf { it.isNotBlank() }
                ?: current.tailnetName
          } else {
            null
          }
      // InternalExitNodePrior is a remembered prior choice, not the current selection.
      val selectedExitNodeId = snapshot.prefs?.selectedExitNodeID.takeIf { hasProfile }
      val activeExitNodeId = snapshot.prefs?.activeExitNodeID.takeIf { hasProfile }
      val allNodes = buildList {
        self?.let { add(it) }
        if (hasProfile) addAll(snapshot.netmap?.Peers.orEmpty())
      }
      val mappedExitNodes =
          snapshot.netmap
              ?.Peers
              .orEmpty()
              .takeIf { hasProfile }
              .orEmpty()
              .filter { it.isExitNode }
              .map { node ->
                WearExitNode(
                    stableId = node.StableID,
                    displayName = node.exitNodeName,
                    online = node.Online == true,
                    selected = node.StableID == selectedExitNodeId,
                    active = node.StableID == activeExitNodeId,
                    isMullvad = node.isMullvadNode,
                    countryCode = node.Hostinfo.Location?.CountryCode,
                    country = node.Hostinfo.Location?.Country,
                    city = node.Hostinfo.Location?.City,
                )
              }
      val exitNodeModels =
          buildList<WearExitNode> {
                addAll(mappedExitNodes)
                if (activeExitNodeId != null && none { it.stableId == activeExitNodeId }) {
                  add(
                      WearExitNode(
                          stableId = activeExitNodeId,
                          displayName =
                              current.activeExitNodeName.takeIf {
                                current.activeExitNodeId == activeExitNodeId
                              } ?: activeExitNodeId,
                          online = false,
                          selected = activeExitNodeId == selectedExitNodeId,
                          active = true,
                          isMullvad = false,
                          countryCode = null,
                          country = null,
                          city = null,
                      ))
                }
              }
              .sortedWith(
                  compareByDescending<WearExitNode> { it.active }
                      .thenByDescending { it.selected }
                      .thenByDescending { it.online }
                      .thenBy { it.displayName.lowercase() })
      val selectedExitNode = exitNodeModels.firstOrNull { it.stableId == selectedExitNodeId }
      val activeExitNode = exitNodeModels.firstOrNull { it.stableId == activeExitNodeId }
      val healthWarnings = healthWarnings(snapshot, resolvedAccountState, activeExitNode)

      current.copy(
          accountState = resolvedAccountState,
          ipnState = snapshot.ipnState,
          desiredVpnEnabled = TailwareVpnStateStore.desiredVpnEnabled(app),
          tunnelStatus =
              tunnelStatus(
                  snapshot.ipnState,
                  snapshot.vpnActive,
                  current.busyAction,
                  TailwareVpnStateStore.desiredVpnEnabled(app),
              ),
          vpnAuthorization = vpnAuthorizationFromPrepared(snapshot.vpnPrepared),
          profileId = snapshot.profile?.ID,
          profileDisplayName = snapshot.profile?.UserProfile?.DisplayName,
          tailnetName = tailnetName,
          selfNode = self?.toWearSelfNode(tailnetName.orEmpty()),
          peers =
              allNodes
                  .filterNot { it.isMullvadNode }
                  .distinctBy { it.StableID }
                  .sortedWith(
                      compareByDescending<Tailcfg.Node> { node ->
                            snapshot.netmap?.let { node.isSelfNode(it) } == true
                          }
                          .thenByDescending { it.Online == true }
                          .thenBy { it.displayName.lowercase() })
                  .map { it.toWearPeer(snapshot.netmap) },
          exitNodes = exitNodeModels,
          selectedExitNodeId = selectedExitNodeId,
          selectedExitNodeName = selectedExitNode?.displayName,
          activeExitNodeId = activeExitNodeId,
          activeExitNodeName = activeExitNode?.displayName,
          allowLanAccess = hasProfile && snapshot.prefs?.ExitNodeAllowLANAccess == true,
          tailscaleDnsEnabled =
              resolvedAccountState == WearAccountState.SignedIn && snapshot.prefs?.CorpDNS == true,
          tailscaleSettingsReady =
              resolvedAccountState == WearAccountState.SignedIn && snapshot.prefs != null,
          underlyingNetworkStatus = snapshot.underlyingNetworkStatus,
          healthWarnings = healthWarnings,
          loginUrl = if (signedInSnapshot) null else current.loginUrl,
          loginCode = if (signedInSnapshot) null else current.loginCode,
          activeLoginMethod = if (signedInSnapshot) null else current.activeLoginMethod,
          phoneLoginStatus =
              if (signedInSnapshot) PhoneLoginStatus.Idle else current.phoneLoginStatus,
          phoneLoginError = if (signedInSnapshot) null else current.phoneLoginError,
          busyAction = settledBusyAction(current.busyAction, snapshot),
      )
    }
    completedLoginGeneration?.let(::finishSuccessfulLogin)
    maybeRestoreDesiredVpn(snapshot, hasProfile)
  }

  private fun accountState(
      snapshot: BackendSnapshot,
      current: WearUiState,
  ): WearAccountState =
      resolveWearAccountState(
          ipnState = snapshot.ipnState,
          hasProfile = snapshot.profile != null && !snapshot.profile.isEmpty(),
          hasLoginUrl = current.loginUrl != null,
          hasActiveLoginAttempt =
              activeLoginAttemptGeneration != null || current.busyAction == WearAction.Login,
      )

  private fun tunnelStatus(
      ipnState: Ipn.State,
      vpnActive: Boolean,
      busyAction: WearAction?,
      desiredVpnEnabled: Boolean,
  ): WearTunnelStatus =
      when {
        ipnState == Ipn.State.Stopping || busyAction == WearAction.Disconnect ->
            WearTunnelStatus.Disconnecting
        ipnState == Ipn.State.Running && vpnActive -> WearTunnelStatus.Connected
        ipnState == Ipn.State.Starting -> WearTunnelStatus.Connecting
        busyAction == WearAction.Connect -> WearTunnelStatus.Connecting
        desiredVpnEnabled -> WearTunnelStatus.Connecting
        ipnState == Ipn.State.NoState -> WearTunnelStatus.Unknown
        else -> WearTunnelStatus.Disconnected
      }

  private fun maybeRestoreDesiredVpn(snapshot: BackendSnapshot, hasProfile: Boolean) {
    val desired = TailwareVpnStateStore.desiredVpnState(app)
    if (automaticVpnRestoreAttempted ||
        !hasProfile ||
        !desired.enabled ||
        !snapshot.vpnPrepared ||
        snapshot.vpnActive ||
        snapshot.ipnState <= Ipn.State.NeedsMachineAuth ||
        snapshot.ipnState == Ipn.State.Starting ||
        snapshot.ipnState == Ipn.State.Stopping) {
      return
    }
    automaticVpnRestoreAttempted = true
    if (!app.restoreVPN(desired)) {
      val currentDesired = TailwareVpnStateStore.desiredVpnState(app)
      _uiState.update {
        it.copy(
            desiredVpnEnabled = currentDesired.enabled,
            tunnelStatus =
                if (currentDesired.enabled) WearTunnelStatus.Connecting
                else WearTunnelStatus.Disconnected,
        )
      }
      setError(
          WearAction.Connect,
          "Tailware could not restore the VPN automatically. Tap the connection control to retry.",
      )
    }
  }

  private fun healthWarnings(
      snapshot: BackendSnapshot,
      accountState: WearAccountState,
      activeExitNode: WearExitNode?,
  ): List<WearHealthWarning> {
    if (accountState != WearAccountState.SignedIn) return emptyList()

    val underlayWarning =
        when (snapshot.underlyingNetworkStatus) {
          UnderlyingNetworkStatus.Offline ->
              WearHealthWarning(
                  code = "tailware-underlay-offline",
                  severity = WearHealthSeverity.High,
                  title = "No internet connection",
                  message =
                      "The Tailware tunnel is still present, but this watch has no internet connection.",
                  impactsConnectivity = true,
              )
          UnderlyingNetworkStatus.Limited ->
              WearHealthWarning(
                  code = "tailware-underlay-limited",
                  severity = WearHealthSeverity.Medium,
                  title = "Internet not verified",
                  message =
                      "Android sees a network, but has not verified that it can reach the internet.",
                  impactsConnectivity = true,
              )
          UnderlyingNetworkStatus.Online,
          UnderlyingNetworkStatus.Unknown -> null
        }
    val exitNodeWarning =
        activeExitNode
            ?.takeIf { !it.online }
            ?.let {
              WearHealthWarning(
                  code = "tailware-exit-node-offline",
                  severity = WearHealthSeverity.High,
                  title = "Exit node unavailable",
                  message =
                      "${it.displayName} is still selected but is offline or missing. Disable it or choose another exit node.",
                  impactsConnectivity = true,
              )
            }
    val nativeWarnings =
        snapshot.healthWarnings
            .asSequence()
            .filterNot { underlayWarning != null && it.WarnableCode == "network-status" }
            .map { warning ->
              WearHealthWarning(
                  code = warning.WarnableCode,
                  severity =
                      when (warning.Severity) {
                        Health.Severity.low -> WearHealthSeverity.Low
                        Health.Severity.medium -> WearHealthSeverity.Medium
                        Health.Severity.high -> WearHealthSeverity.High
                      },
                  title = warning.Title.ifBlank { warning.WarnableCode },
                  message = warning.Text.ifBlank { "Tailscale reported a health warning." },
                  impactsConnectivity = warning.ImpactsConnectivity == true,
              )
            }
            .toList()

    return buildList {
          underlayWarning?.let(::add)
          exitNodeWarning?.let(::add)
          addAll(nativeWarnings)
        }
        .distinctBy { it.code }
        .sortedWith(
            compareByDescending<WearHealthWarning> { it.severity.ordinal }
                .thenByDescending { it.impactsConnectivity }
                .thenBy { it.title.lowercase() })
  }

  private fun settledBusyAction(
      busyAction: WearAction?,
      snapshot: BackendSnapshot,
  ): WearAction? =
      when (busyAction) {
        WearAction.Connect ->
            if (snapshot.ipnState == Ipn.State.Running && snapshot.vpnActive) null else busyAction
        WearAction.Disconnect ->
            if (snapshot.ipnState == Ipn.State.Stopped ||
                snapshot.ipnState == Ipn.State.Stopping ||
                !snapshot.vpnActive)
                null
            else busyAction
        WearAction.Login ->
            when {
              loginCancellationInProgress -> busyAction
              snapshot.profile != null &&
                  snapshot.ipnState != Ipn.State.NeedsLogin &&
                  snapshot.ipnState != Ipn.State.NeedsMachineAuth -> null
              else -> busyAction
            }
        // Profile deletion is verified explicitly by verifyLogoutProfile. Clearing this from a
        // transient backend snapshot would let a second tap race the verification callbacks.
        WearAction.Logout -> busyAction
        else -> busyAction
      }

  /**
   * Rechecks Android's VPN authorization.
   *
   * `VpnService.prepare() == null` is the only authoritative "granted" signal. Wear OS does not
   * provide a supported VPN confirmation flow, so every non-null result requires the one-time ADB
   * bootstrap.
   */
  fun refreshVpnAuthorization(context: Context): VpnAuthorization {
    val authorization =
        try {
          vpnAuthorizationFromPrepared(VpnService.prepare(context) == null)
        } catch (error: RuntimeException) {
          setError(null, error)
          VpnAuthorization.AdbRequired
        }

    appViewModel.setVpnPrepared(authorization == VpnAuthorization.Granted)
    _uiState.update { it.copy(vpnAuthorization = authorization) }
    return authorization
  }

  fun connect() {
    val current = _uiState.value
    if (current.accountState != WearAccountState.SignedIn) {
      setError(WearAction.Connect, "Connect a Tailware account before starting the VPN.")
      return
    }
    if (refreshVpnAuthorization(app) != VpnAuthorization.Granted) {
      setError(WearAction.Connect, "VPN authorization is required before Tailware can connect.")
      return
    }

    val generation = startBusyAction(WearAction.Connect)
    _uiState.update { it.copy(desiredVpnEnabled = true) }
    val dispatch = app.startVPNCommand()
    if (!dispatch.dispatched) {
      val reset = TailwareVpnStateStore.setDesiredVpnEnabledIfCurrent(app, dispatch.command, false)
      if (reset == null) {
        val newerDesired = TailwareVpnStateStore.desiredVpnState(app)
        updateForAction(WearAction.Connect, generation) {
          it.copy(desiredVpnEnabled = newerDesired.enabled, busyAction = null)
        }
        return
      }
      _uiState.update {
        it.copy(
            desiredVpnEnabled = false,
            tunnelStatus = WearTunnelStatus.Disconnected,
        )
      }
      completeFailure(
          WearAction.Connect,
          IllegalStateException("Android did not allow Tailware to start the VPN service."),
          generation,
      )
      return
    }
    clearBusyAfterTimeout(WearAction.Connect, generation)
  }

  fun disconnect() {
    val generation = startBusyAction(WearAction.Disconnect)
    _uiState.update { it.copy(desiredVpnEnabled = false) }
    val dispatch = app.stopVPNCommand()
    if (!dispatch.dispatched) {
      if (TailwareVpnStateStore.desiredVpnState(app) != dispatch.command) {
        val newerDesired = TailwareVpnStateStore.desiredVpnState(app)
        updateForAction(WearAction.Disconnect, generation) {
          it.copy(desiredVpnEnabled = newerDesired.enabled, busyAction = null)
        }
        return
      }
      completeFailure(
          WearAction.Disconnect,
          IllegalStateException("Android did not allow Tailware to stop the VPN service."),
          generation,
      )
      return
    }
    clearBusyAfterTimeout(WearAction.Disconnect, generation)
  }

  fun toggleConnection() {
    val current = _uiState.value
    if (current.error?.operation == WearAction.Disconnect && !current.desiredVpnEnabled) {
      disconnect()
    } else if (current.desiredVpnEnabled) {
      disconnect()
    } else {
      connect()
    }
  }

  /** Called by the current Activity instance after a configuration-change recreation. */
  fun onActivityAttached() {
    activityRecreationInProgress = false
  }

  /**
   * Prevents Compose route disposal during a configuration change from cancelling backend auth.
   *
   * Real route disposal still calls [cancelLoginIfActive] with this flag unset.
   */
  fun onActivityRecreationStarting() {
    activityRecreationInProgress = true
  }

  fun prepareLoginMethod(method: LoginMethod) {
    if (_uiState.value.activeLoginMethod == method) return
    busyTimeoutJob?.cancel()
    acceptLoginUrls = false
    Notifier.browseToURL.set(null)
    Notifier.loginFinished.set(null)
    Notifier.errMessage.set(null)
    _uiState.update {
      it.copy(
          activeLoginMethod = method,
          loginUrl = null,
          loginCode = null,
          phoneLoginStatus = PhoneLoginStatus.Idle,
          phoneLoginError = null,
          busyAction = if (it.busyAction == WearAction.Login) null else it.busyAction,
      )
    }
  }

  fun startInteractiveLogin(presentation: LoginPresentation = LoginPresentation.QrCode): Boolean {
    if (loginCancellationInProgress) return false
    if (loginCleanupRequired) {
      pendingLoginStart = { startInteractiveLogin(presentation) }
      startLoginCancellation()
      return true
    }
    if (_uiState.value.busyAction == WearAction.Login) return false
    val generation = beginLoginAttempt(LoginMethod.Tailscale, presentation)
    loginForegroundProtectionActive = true
    login(
        maskedPrefs = Ipn.MaskedPrefs().apply { ControlURL = Links.DEFAULT_CONTROL_URL },
        useForegroundService = true,
    ) { result ->
      completeUnitAction(WearAction.Login, result, generation)
    }
    return true
  }

  fun startAuthKeyLogin(authKey: String): Boolean {
    if (loginCancellationInProgress) return false
    if (loginCleanupRequired) {
      pendingLoginStart = { startAuthKeyLogin(authKey) }
      startLoginCancellation()
      return true
    }
    if (_uiState.value.busyAction == WearAction.Login) return false
    if (authKey.isBlank()) {
      setError(
          WearAction.Login,
          "Enter an authentication key.",
          WearErrorOrigin.AuthenticationKey,
      )
      return false
    }
    val generation = beginLoginAttempt(LoginMethod.AuthenticationKey, LoginPresentation.QrCode)
    loginForegroundProtectionActive = true
    login(
        maskedPrefs = Ipn.MaskedPrefs().apply { ControlURL = Links.DEFAULT_CONTROL_URL },
        authKey = authKey.trim(),
        useForegroundService = true,
    ) { result ->
      completeUnitAction(WearAction.Login, result, generation)
    }
    return true
  }

  fun startCustomControlLogin(controlUrl: String): Boolean {
    if (loginCancellationInProgress) return false
    if (loginCleanupRequired) {
      pendingLoginStart = { startCustomControlLogin(controlUrl) }
      startLoginCancellation()
      return true
    }
    if (_uiState.value.busyAction == WearAction.Login) return false
    val normalized = controlUrl.trim()
    val parsedUrl = normalized.toUri()
    if (!parsedUrl.scheme.equals("https", ignoreCase = true) || parsedUrl.host.isNullOrBlank()) {
      setError(
          WearAction.Login,
          "Enter a valid HTTPS control server URL.",
          WearErrorOrigin.CustomServer,
      )
      return false
    }
    val generation = beginLoginAttempt(LoginMethod.CustomServer, LoginPresentation.QrCode)
    loginForegroundProtectionActive = true
    loginWithCustomControlURL(normalized, useForegroundService = true) { result ->
      completeUnitAction(WearAction.Login, result, generation)
    }
    return true
  }

  fun cancelLoginIfActive(method: LoginMethod) {
    if (activityRecreationInProgress) return
    if (_uiState.value.activeLoginMethod != method) return
    val generation = activeLoginAttemptGeneration ?: return
    cancelInteractiveLogin(generation)
  }

  fun regenerateInteractiveLogin(presentation: LoginPresentation) {
    if (loginCancellationInProgress) return
    pendingLoginStart = { startInteractiveLogin(presentation) }
    val generation = activeLoginAttemptGeneration
    if (generation == null) {
      val pending = pendingLoginStart
      pendingLoginStart = null
      pending?.invoke()
    } else {
      startLoginCancellation(expectedAttemptGeneration = generation)
    }
  }

  fun cancelInteractiveLogin() {
    if (activityRecreationInProgress) return
    val generation = activeLoginAttemptGeneration ?: return
    cancelInteractiveLogin(generation)
  }

  private fun cancelInteractiveLogin(expectedAttemptGeneration: Long) {
    pendingLoginStart = null
    postCleanupError = null
    startLoginCancellation(expectedAttemptGeneration = expectedAttemptGeneration)
  }

  private fun startLoginCancellation(
      errorAfterCleanup: WearError? = null,
      expectedAttemptGeneration: Long? = activeLoginAttemptGeneration,
  ) {
    if (loginCancellationInProgress) return
    if (expectedAttemptGeneration != null &&
        !isCurrentLoginAttempt(activeLoginAttemptGeneration, expectedAttemptGeneration)) {
      return
    }
    if (activeLoginAttemptGeneration == null && !loginCleanupRequired) {
      val pending = pendingLoginStart
      pendingLoginStart = null
      pending?.invoke()
      return
    }
    if (_uiState.value.accountState == WearAccountState.SignedIn) {
      pendingLoginStart = null
      return
    }
    postCleanupError = errorAfterCleanup
    loginCancellationInProgress = true
    loginCleanupRequired = true
    loginCancellationGeneration += 1
    val cancellationGeneration = loginCancellationGeneration
    activeLoginAttemptGeneration = null
    acceptLoginUrls = false
    Notifier.browseToURL.set(null)
    Notifier.loginFinished.set(null)
    Notifier.errMessage.set(null)
    TailwareVpnStateStore.setDesiredVpnEnabled(app, false)
    startBusyAction(WearAction.Login, clearLogin = true)
    viewModelScope.launch {
      val result = cleanUpLoginAttempt()
      completeLoginCancellation(result, cancellationGeneration)
    }
  }

  private suspend fun cleanUpLoginAttempt(): Result<Unit> =
      try {
        withTimeout(LOGIN_CLEANUP_TIMEOUT_MILLIS) {
          setBackendWantRunningForCleanup(false).getOrThrow()
          withContext(Dispatchers.IO) {
            // DisconnectControl atomically clears the pending AuthURL without changing the
            // selected profile or node keys. Waiting for both operations prevents an old STOP
            // patch from racing a newly requested login.
            app.getLibtailscaleApp().cancelInteractiveLogin()
          }
        }
        Result.success(Unit)
      } catch (_: TimeoutCancellationException) {
        Result.failure(
            IllegalStateException(
                "The previous sign-in attempt could not be cleaned up in time. Try again."))
      } catch (error: CancellationException) {
        throw error
      } catch (error: Throwable) {
        Result.failure(error)
      }

  private suspend fun setBackendWantRunningForCleanup(wantRunning: Boolean): Result<Unit> =
      suspendCancellableCoroutine { continuation ->
        Client(viewModelScope).editPrefs(
            Ipn.MaskedPrefs().apply { WantRunning = wantRunning },
        ) { result ->
          if (continuation.isActive) continuation.resume(result.map { Unit })
        }
      }

  fun onPhoneLoginOpening(url: String) {
    if (_uiState.value.loginUrl != url) return
    _uiState.update {
      it.copy(
          phoneLoginStatus = PhoneLoginStatus.Opening,
          phoneLoginError = null,
      )
    }
  }

  fun onPhoneLoginOpened(url: String) {
    if (_uiState.value.loginUrl != url) return
    _uiState.update {
      it.copy(
          phoneLoginStatus = PhoneLoginStatus.Sent,
          phoneLoginError = null,
      )
    }
  }

  fun onPhoneLoginLaunchFailed(url: String, error: Throwable) {
    if (_uiState.value.loginUrl != url) return
    _uiState.update {
      it.copy(
          phoneLoginStatus = PhoneLoginStatus.Failed,
          phoneLoginError =
              "Could not open the sign-in page on the paired phone: ${error.readableMessage()}",
      )
    }
  }

  fun clearPhoneLoginError() {
    _uiState.update {
      it.copy(
          phoneLoginStatus = PhoneLoginStatus.Idle,
          phoneLoginError = null,
      )
    }
  }

  fun logoutAccount() {
    val current = _uiState.value
    if (current.busyAction == WearAction.Logout) return
    val profile =
        loggedInUser.value ?: loginProfiles.value?.firstOrNull { it.ID == current.profileId }
    if (profile == null) {
      setError(
          WearAction.Logout,
          "Account details are still loading. Wait a moment and try signing out again.",
      )
      return
    }
    val useLocalOnlyLogout = shouldUseLocalOnlyLogout(current.underlyingNetworkStatus)
    acceptLoginUrls = false
    activeLoginAttemptGeneration = null
    Notifier.browseToURL.set(null)
    Notifier.loginFinished.set(null)
    Notifier.errMessage.set(null)
    logoutProfile = profile
    logoutCompletionNotice =
        if (useLocalOnlyLogout) {
          WearError(
              operation = WearAction.Logout,
              message =
                  "Signed out locally while offline. Tailscale could not be notified; the old device entry may remain in the admin console until it is removed or expires.",
          )
        } else {
          null
        }
    TailwareVpnStateStore.setDesiredVpnEnabled(app, false)
    stopVPN()
    stopLoginForegroundProtection()
    val generation = startBusyAction(WearAction.Logout)
    clearBusyAfterTimeout(WearAction.Logout, generation, LOGOUT_TIMEOUT_MILLIS)
    if (useLocalOnlyLogout) {
      Client(viewModelScope).deleteProfile(profile) { result ->
        if (!isCurrentAction(WearAction.Logout, generation)) return@deleteProfile
        result
            .onSuccess {
              verifyLogoutProfile(
                  generation = generation,
                  allowLocalCleanup = false,
              )
            }
            .onFailure { completeFailure(WearAction.Logout, it, generation) }
      }
      return
    }
    logout { result ->
      result
          .onSuccess {
            updateForAction(WearAction.Logout, generation) {
              it.copy(
                  loginUrl = null,
                  loginCode = null,
                  activeLoginMethod = null,
                  phoneLoginStatus = PhoneLoginStatus.Idle,
                  phoneLoginError = null,
                  pingResult = WearPingResult(),
                  error = null,
              )
            }
            verifyLogoutProfile(generation)
          }
          .onFailure { completeFailure(WearAction.Logout, it, generation) }
    }
  }

  fun toggleTailscaleDns() {
    val current = _uiState.value
    if (current.accountState != WearAccountState.SignedIn) {
      setError(WearAction.ToggleDns, "Sign in before changing Tailscale DNS.")
      return
    }
    if (!current.tailscaleSettingsReady) {
      setError(
          WearAction.ToggleDns,
          "Tailscale settings are still loading. Wait a moment and try again.",
      )
      return
    }
    val prefs =
        Notifier.prefs.value
            ?: run {
              setError(WearAction.ToggleDns, "Tailscale preferences are not available yet.")
              return
            }
    editPrefs(
        WearAction.ToggleDns,
        Ipn.MaskedPrefs().apply { CorpDNS = !prefs.CorpDNS },
    )
  }

  fun toggleAllowLanAccess() {
    val prefs =
        Notifier.prefs.value
            ?: run {
              setError(WearAction.ToggleLanAccess, "Tailscale preferences are not available yet.")
              return
            }
    if (prefs.activeExitNodeID == null) {
      setError(WearAction.ToggleLanAccess, "Select an exit node before enabling LAN access.")
      return
    }
    editPrefs(
        WearAction.ToggleLanAccess,
        Ipn.MaskedPrefs().apply { ExitNodeAllowLANAccess = !prefs.ExitNodeAllowLANAccess },
    )
  }

  fun selectExitNode(stableId: String) {
    val node =
        Notifier.netmap.value?.Peers?.firstOrNull { it.StableID == stableId && it.isExitNode }
    if (node == null) {
      setError(WearAction.SelectExitNode, "The selected exit node is no longer available.")
      return
    }
    if (node.Online != true) {
      setError(WearAction.SelectExitNode, "The selected exit node is offline.")
      return
    }
    editPrefs(
        WearAction.SelectExitNode,
        Ipn.MaskedPrefs().apply { ExitNodeID = stableId },
    )
  }

  fun clearExitNode() {
    editPrefs(
        WearAction.SelectExitNode,
        Ipn.MaskedPrefs().apply { ExitNodeID = null },
    )
  }

  fun pingPeer(stableId: String) {
    val current = _uiState.value
    if (current.tunnelStatus != WearTunnelStatus.Connected ||
        current.ipnState != Ipn.State.Running) {
      setError(
          WearAction.Ping,
          "Connect Tailware first. Device ping is available only while the VPN tunnel is fully connected.",
      )
      return
    }
    val netmap = Notifier.netmap.value
    val peer = netmap?.getPeer(stableId)
    if (peer == null) {
      setError(WearAction.Ping, "The selected device is no longer available.")
      return
    }
    if (peer.isSelfNode(netmap)) {
      setError(WearAction.Ping, "This is this watch; a self-ping does not test a network path.")
      return
    }
    val address = peer.primaryIPv4Address
    if (address.isNullOrBlank()) {
      setError(WearAction.Ping, "This device has no IPv4 address to ping.")
      return
    }

    val generation = startBusyAction(WearAction.Ping)
    updateForAction(WearAction.Ping, generation) {
      it.copy(
          pingResult =
              WearPingResult(
                  peerId = peer.StableID,
                  peerName = peer.displayName,
                  status = PingStatus.Running,
              ),
      )
    }
    Client(viewModelScope).ping(peer) { result ->
      result
          .onSuccess { ping ->
            if (ping.Err.isNotBlank()) {
              val diagnostic = pingFailureMessage(peer.displayName, address, ping.Err)
              TSLog.d(
                  TAG,
                  "Tailscale disco ping failed peer=${peer.StableID} target=$address " +
                      "backendTarget=${ping.IP} error=${ping.Err}",
              )
              updateForAction(WearAction.Ping, generation) {
                it.copy(
                    busyAction = null,
                    pingResult =
                        WearPingResult(
                            peerId = peer.StableID,
                            peerName = peer.displayName,
                            status = PingStatus.Failed,
                            error = diagnostic,
                        ),
                )
              }
            } else {
              updateForAction(WearAction.Ping, generation) {
                it.copy(
                    busyAction = null,
                    error = null,
                    pingResult =
                        WearPingResult(
                            peerId = peer.StableID,
                            peerName = peer.displayName,
                            status = PingStatus.Success,
                            latencyMillis = ping.LatencySeconds * 1000.0,
                        ),
                )
              }
            }
          }
          .onFailure { error ->
            val diagnostic = pingFailureMessage(peer.displayName, address, error.readableMessage())
            TSLog.d(
                TAG,
                "Tailscale disco ping request failed peer=${peer.StableID} target=$address " +
                    "error=${error.readableMessage()}",
            )
            updateForAction(WearAction.Ping, generation) {
              it.copy(
                  busyAction = null,
                  pingResult =
                      WearPingResult(
                          peerId = peer.StableID,
                          peerName = peer.displayName,
                          status = PingStatus.Failed,
                          error = diagnostic,
                      ),
              )
            }
          }
    }
  }

  fun clearPingResult() {
    cancelBusyAction(WearAction.Ping)
    _uiState.update {
      it.copy(
          busyAction = if (it.busyAction == WearAction.Ping) null else it.busyAction,
          pingResult = WearPingResult(),
      )
    }
  }

  fun setLanguage(language: TailwareLanguage) {
    localPreferences.edit { putString(KEY_LANGUAGE, language.name) }
    applyAppLocale(language)
    _uiState.update { it.copy(language = language) }
  }

  fun setOptionalNotificationsEnabled(enabled: Boolean) {
    localPreferences.edit { putBoolean(KEY_OPTIONAL_NOTIFICATIONS, enabled) }
    app.refreshOptionalWearStatusNotification()
    syncOptionalNotificationPermission()
  }

  fun onOptionalNotificationPermissionFinished() {
    // Keep the user's ON intent after a denial so Settings can accurately explain that Android,
    // rather than Tailware's own switch, is blocking notifications.
    localPreferences.edit { putBoolean(KEY_OPTIONAL_NOTIFICATIONS, true) }
    app.refreshOptionalWearStatusNotification()
    syncOptionalNotificationPermission()
  }

  fun syncOptionalNotificationPermission() {
    _uiState.update {
      it.copy(
          optionalNotificationsEnabled = initialOptionalNotificationsEnabled(),
          optionalNotificationsBlockedBySystem = initialOptionalNotificationsBlocked(),
      )
    }
  }

  fun setTileQuickAction(action: TileQuickAction) {
    localPreferences.edit { putString(KEY_TILE_QUICK_ACTION, action.name) }
    _uiState.update { it.copy(tileQuickAction = action) }
    TailwareTileUpdates.request(app)
  }

  fun setTileShowIp(show: Boolean) {
    localPreferences.edit { putBoolean(KEY_TILE_SHOW_IP, show) }
    _uiState.update { it.copy(tileShowIp = show) }
    TailwareTileUpdates.request(app)
  }

  fun setTileShowExitNode(show: Boolean) {
    localPreferences.edit { putBoolean(KEY_TILE_SHOW_EXIT_NODE, show) }
    _uiState.update { it.copy(tileShowExitNode = show) }
    TailwareTileUpdates.request(app)
  }

  fun setTileShowMagicDns(show: Boolean) {
    localPreferences.edit { putBoolean(KEY_TILE_SHOW_MAGIC_DNS, show) }
    _uiState.update { it.copy(tileShowMagicDns = show) }
    TailwareTileUpdates.request(app)
  }

  fun clearError() {
    _uiState.update { it.copy(error = null) }
  }

  private fun initialOptionalNotificationsEnabled(): Boolean {
    val defaultEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
    val requested = localPreferences.getBoolean(KEY_OPTIONAL_NOTIFICATIONS, defaultEnabled)
    return requested && NotificationManagerCompat.from(app).areNotificationsEnabled()
  }

  private fun initialOptionalNotificationsBlocked(): Boolean {
    val defaultEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
    val requested = localPreferences.getBoolean(KEY_OPTIONAL_NOTIFICATIONS, defaultEnabled)
    return requested && !NotificationManagerCompat.from(app).areNotificationsEnabled()
  }

  @Suppress("DEPRECATION")
  private fun applyAppLocale(language: TailwareLanguage) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      app.getSystemService(android.app.LocaleManager::class.java).applicationLocales =
          LocaleList.forLanguageTags(language.languageTag)
    } else {
      val configuration = Configuration(app.resources.configuration)
      configuration.setLocale(Locale.forLanguageTag(language.languageTag))
      app.resources.updateConfiguration(configuration, app.resources.displayMetrics)
    }
  }

  private fun editPrefs(action: WearAction, prefs: Ipn.MaskedPrefs) {
    val generation = startBusyAction(action)
    Client(viewModelScope).editPrefs(prefs) { result ->
      result
          .onSuccess {
            updateForAction(action, generation) { it.copy(busyAction = null, error = null) }
          }
          .onFailure { completeFailure(action, it, generation) }
    }
  }

  private fun beginLoginAttempt(
      method: LoginMethod,
      presentation: LoginPresentation,
  ): Long {
    loginAttemptGeneration += 1L
    activeLoginAttemptGeneration = loginAttemptGeneration
    loginCleanupRequired = false
    acceptLoginUrls = true
    Notifier.loginFinished.set(null)
    Notifier.errMessage.set(null)
    _uiState.update {
      it.copy(
          activeLoginMethod = method,
          loginPresentation = presentation,
          phoneLoginStatus = PhoneLoginStatus.Idle,
          phoneLoginError = null,
          error = null,
      )
    }
    return startBusyAction(WearAction.Login, clearLogin = true)
  }

  private fun beginBackendReauthentication() {
    if (activeLoginAttemptGeneration != null) return
    loginAttemptGeneration += 1L
    activeLoginAttemptGeneration = loginAttemptGeneration
    loginCleanupRequired = false
    acceptLoginUrls = true
    if (!loginForegroundProtectionActive) {
      loginForegroundProtectionActive = true
      app.startForegroundForLogin()
    }
    val generation = startBusyAction(WearAction.Login)
    clearBusyAfterTimeout(WearAction.Login, generation, LOGIN_TIMEOUT_MILLIS)
    _uiState.update {
      it.copy(
          activeLoginMethod = LoginMethod.Tailscale,
          loginPresentation = LoginPresentation.QrCode,
          error = null,
      )
    }
  }

  /**
   * Converts a successful backend login into one exact desired-state command.
   *
   * `startVPNCommand` persists and returns the command generation used by IPNService. A later OFF
   * therefore wins even when Android delivers these intents out of order. Dispatch rollback is
   * likewise conditional on the same generation.
   */
  private fun tryAutoConnectAfterLogin(generation: Long) {
    if (!isCurrentLoginAttempt(activeLoginAttemptGeneration, generation) ||
        autoConnectedLoginGeneration == generation) {
      return
    }
    autoConnectedLoginGeneration = generation
    if (refreshVpnAuthorization(app) != VpnAuthorization.Granted) {
      stopLoginForegroundProtection()
      _uiState.update {
        it.copy(
            desiredVpnEnabled = false,
            error =
                WearError(
                    operation = WearAction.Connect,
                    message =
                        "Sign-in succeeded, but the one-time VPN authorization is still required before Tailware can connect.",
                ),
        )
      }
      return
    }

    val dispatch = app.startVPNCommand()
    if (!dispatch.dispatched) {
      val rollback =
          TailwareVpnStateStore.setDesiredVpnEnabledIfCurrent(app, dispatch.command, false)
      stopLoginForegroundProtection()
      _uiState.update {
        val desired = rollback ?: TailwareVpnStateStore.desiredVpnState(app)
        it.copy(
            desiredVpnEnabled = desired.enabled,
            tunnelStatus =
                if (desired.enabled) WearTunnelStatus.Connecting else WearTunnelStatus.Disconnected,
            error =
                WearError(
                    operation = WearAction.Connect,
                    message =
                        "Sign-in succeeded, but Android did not allow Tailware to start the VPN service.",
                ),
        )
      }
      return
    }

    // The foreground login service is now the real VPN service; never stop it as cleanup.
    loginForegroundProtectionActive = false
    val desired = TailwareVpnStateStore.desiredVpnState(app)
    _uiState.update {
      it.copy(
          desiredVpnEnabled = desired.enabled,
          tunnelStatus = if (desired.enabled) WearTunnelStatus.Connecting else it.tunnelStatus,
          error = null,
      )
    }
  }

  private fun finishSuccessfulLogin(generation: Long) {
    if (!isCurrentLoginAttempt(activeLoginAttemptGeneration, generation)) return
    tryAutoConnectAfterLogin(generation)
    acceptLoginUrls = false
    activeLoginAttemptGeneration = null
    loginCleanupRequired = false
    busyTimeoutJob?.cancel()
    Notifier.browseToURL.set(null)
    Notifier.loginFinished.set(null)
    _uiState.update {
      it.copy(
          activeLoginMethod = null,
          loginUrl = null,
          loginCode = null,
          phoneLoginStatus = PhoneLoginStatus.Idle,
          phoneLoginError = null,
          busyAction = if (it.busyAction == WearAction.Login) null else it.busyAction,
      )
    }
  }

  private fun stopLoginForegroundProtection() {
    if (!loginForegroundProtectionActive) return
    loginForegroundProtectionActive = false
    if (TailwareVpnStateStore.desiredVpnEnabled(app)) return
    val foregroundServiceIntent = Intent(app, IPNService::class.java)
    runCatching { app.stopService(foregroundServiceIntent) }
        .onFailure {
          TSLog.w("TailwareViewModel", "Unable to stop login foreground protection: $it")
        }
  }

  private fun isCurrentLoginCancellation(generation: Long): Boolean =
      loginCancellationInProgress && loginCancellationGeneration == generation

  private fun completeLoginCancellation(result: Result<Unit>, generation: Long) {
    if (!isCurrentLoginCancellation(generation)) return
    loginCancellationInProgress = false
    stopLoginForegroundProtection()
    result
        .onSuccess {
          loginCleanupRequired = false
          val pending = pendingLoginStart
          val finalError = postCleanupError
          pendingLoginStart = null
          postCleanupError = null
          _uiState.update {
            it.copy(
                accountState =
                    if (it.profileId == null) WearAccountState.SignedOut else it.accountState,
                busyAction = if (it.busyAction == WearAction.Login) null else it.busyAction,
                loginUrl = null,
                loginCode = null,
                activeLoginMethod = null,
                phoneLoginStatus = PhoneLoginStatus.Idle,
                phoneLoginError = null,
                error = finalError,
            )
          }
          pending?.invoke()
        }
        .onFailure { error ->
          loginCleanupRequired = true
          pendingLoginStart = null
          postCleanupError = null
          acceptLoginUrls = false
          Notifier.browseToURL.set(null)
          _uiState.update {
            it.copy(
                busyAction = if (it.busyAction == WearAction.Login) null else it.busyAction,
                loginUrl = null,
                loginCode = null,
                activeLoginMethod = null,
                phoneLoginStatus = PhoneLoginStatus.Idle,
                phoneLoginError = null,
                error =
                    WearError(
                        operation = WearAction.Login,
                        message =
                            "Tailware could not safely close the previous sign-in attempt: ${error.readableMessage()}",
                        origin = WearErrorOrigin.Global,
                    ),
            )
          }
        }
  }

  private fun verifyLogoutProfile(
      generation: Long,
      allowLocalCleanup: Boolean = true,
      verificationAttempt: Int = 0,
  ) {
    if (!isCurrentAction(WearAction.Logout, generation)) return
    val oldProfile = logoutProfile
    viewModelScope.launch {
      delay(LOGOUT_VERIFICATION_DELAY_MILLIS)
      if (!isCurrentAction(WearAction.Logout, generation)) return@launch
      Client(viewModelScope).profiles profilesCallback@{ profilesResult ->
        if (!isCurrentAction(WearAction.Logout, generation)) return@profilesCallback
        val profiles =
            profilesResult.getOrElse {
              completeFailure(WearAction.Logout, it, generation)
              return@profilesCallback
            }
        Client(viewModelScope).currentProfile currentProfileCallback@{ currentResult ->
          if (!isCurrentAction(WearAction.Logout, generation)) return@currentProfileCallback
          val currentProfile =
              currentResult.getOrElse {
                completeFailure(WearAction.Logout, it, generation)
                return@currentProfileCallback
              }
          val normalizedCurrent = currentProfile.takeUnless { it.isEmpty() }
          val remainingOldProfile =
              oldProfile?.let { old ->
                profiles.firstOrNull { it.ID == old.ID }
                    ?: normalizedCurrent?.takeIf { it.ID == old.ID }
              }
          if (remainingOldProfile != null && allowLocalCleanup) {
            if (_uiState.value.underlyingNetworkStatus != UnderlyingNetworkStatus.Online) {
              completeFailure(
                  WearAction.Logout,
                  IllegalStateException(
                      "Internet was lost before sign-out could be verified. The local account was kept."),
                  generation,
              )
              return@currentProfileCallback
            }
            if (Notifier.state.value != Ipn.State.NeedsLogin) {
              if (verificationAttempt < MAX_LOGOUT_VERIFICATION_ATTEMPTS) {
                verifyLogoutProfile(generation, true, verificationAttempt + 1)
              } else {
                completeFailure(
                    WearAction.Logout,
                    IllegalStateException(
                        "Remote sign-out could not be verified. The local account was kept."),
                    generation,
                )
              }
              return@currentProfileCallback
            }
            Client(viewModelScope).deleteProfile(remainingOldProfile) deleteProfileCallback@{
                deleteResult ->
              if (!isCurrentAction(WearAction.Logout, generation)) return@deleteProfileCallback
              deleteResult
                  .onSuccess { verifyLogoutProfile(generation, false) }
                  .onFailure { completeFailure(WearAction.Logout, it, generation) }
            }
            return@currentProfileCallback
          }

          if (remainingOldProfile != null &&
              !allowLocalCleanup &&
              verificationAttempt < MAX_LOGOUT_VERIFICATION_ATTEMPTS) {
            verifyLogoutProfile(
                generation = generation,
                allowLocalCleanup = false,
                verificationAttempt = verificationAttempt + 1,
            )
            return@currentProfileCallback
          }

          if (!isCurrentAction(WearAction.Logout, generation)) return@currentProfileCallback
          loginProfiles.set(profiles)
          loggedInUser.set(normalizedCurrent)
          if (normalizedCurrent == null && remainingOldProfile == null) {
            val completionNotice = logoutCompletionNotice
            logoutProfile = null
            logoutCompletionNotice = null
            updateForAction(WearAction.Logout, generation) {
              it.copy(
                  accountState = WearAccountState.SignedOut,
                  busyAction = null,
                  profileId = null,
                  profileDisplayName = null,
                  tailnetName = null,
                  selfNode = null,
                  peers = emptyList(),
                  exitNodes = emptyList(),
                  selectedExitNodeId = null,
                  selectedExitNodeName = null,
                  activeExitNodeId = null,
                  activeExitNodeName = null,
                  allowLanAccess = false,
                  tailscaleDnsEnabled = false,
                  tailscaleSettingsReady = false,
                  healthWarnings = emptyList(),
                  error = completionNotice,
              )
            }
          } else {
            completeFailure(
                WearAction.Logout,
                IllegalStateException(
                    "Tailware could not verify that the local account was removed."),
                generation,
            )
          }
        }
      }
    }
  }

  private fun startBusyAction(action: WearAction, clearLogin: Boolean = false): Long {
    actionGeneration += 1
    busyTimeoutJob?.cancel()
    if (clearLogin) {
      Notifier.browseToURL.set(null)
    }
    _uiState.update {
      it.copy(
          busyAction = action,
          error = null,
          loginUrl = if (clearLogin) null else it.loginUrl,
          loginCode = if (clearLogin) null else it.loginCode,
          phoneLoginStatus = if (clearLogin) PhoneLoginStatus.Idle else it.phoneLoginStatus,
          phoneLoginError = if (clearLogin) null else it.phoneLoginError,
      )
    }
    return actionGeneration
  }

  private fun completeUnitAction(action: WearAction, result: Result<Unit>, generation: Long) {
    result
        .onSuccess {
          if (!isCurrentAction(action, generation)) return@onSuccess
          if (action == WearAction.Login) {
            updateForAction(action, generation) { it.copy(error = null) }
            clearBusyAfterTimeout(action, generation, LOGIN_TIMEOUT_MILLIS)
          } else {
            updateForAction(action, generation) { it.copy(busyAction = null, error = null) }
          }
        }
        .onFailure { completeFailure(action, it, generation) }
  }

  private fun completeFailure(action: WearAction, error: Throwable, generation: Long) {
    if (!isCurrentAction(action, generation)) return
    if (action == WearAction.Logout) {
      logoutCompletionNotice = null
    }
    val origin =
        if (action == WearAction.Login) _uiState.value.activeLoginMethod.errorOrigin
        else WearErrorOrigin.Global
    if (action == WearAction.Login) {
      startLoginCancellation(
          WearError(
              operation = action,
              message = error.readableMessage(),
              origin = origin,
          ))
      return
    }
    updateForAction(action, generation) {
      it.copy(
          busyAction = null,
          error =
              WearError(
                  operation = action,
                  message = error.readableMessage(),
                  origin = origin,
              ),
      )
    }
  }

  private fun setError(
      action: WearAction?,
      message: String,
      origin: WearErrorOrigin = WearErrorOrigin.Global,
  ) {
    actionGeneration += 1
    busyTimeoutJob?.cancel()
    _uiState.update {
      it.copy(
          busyAction = null,
          error = WearError(operation = action, message = message, origin = origin),
      )
    }
  }

  private fun setError(action: WearAction?, error: Throwable) {
    setError(action, error.readableMessage())
  }

  private fun clearBusyAfterTimeout(
      action: WearAction,
      generation: Long,
      timeoutMillis: Long = ACTION_TIMEOUT_MILLIS,
  ) {
    busyTimeoutJob?.cancel()
    busyTimeoutJob =
        viewModelScope.launch {
          delay(timeoutMillis)
          if (action == WearAction.Login && isCurrentAction(action, generation)) {
            val current = _uiState.value
            val message =
                when (current.activeLoginMethod) {
                  LoginMethod.AuthenticationKey ->
                      "The authentication key could not be verified. Check that it is valid and try again."
                  LoginMethod.CustomServer ->
                      "The custom server is taking too long to answer. Check its address and connection."
                  LoginMethod.Tailscale,
                  null ->
                      "Tailscale sign-in is taking longer than expected. Check the watch connection."
                }
            startLoginCancellation(
                WearError(
                    operation = WearAction.Login,
                    message = message,
                    origin = current.activeLoginMethod.errorOrigin,
                ))
            return@launch
          }
          _uiState.update {
            if (it.busyAction == action && actionGeneration == generation) {
              it.copy(
                  busyAction = null,
                  error =
                      WearError(
                          operation = action,
                          message = "The operation is taking longer than expected.",
                          origin = WearErrorOrigin.Global,
                      ),
              )
            } else {
              it
            }
          }
        }
  }

  private inline fun updateForAction(
      action: WearAction,
      generation: Long,
      transform: (WearUiState) -> WearUiState,
  ) {
    _uiState.update {
      if (actionGeneration == generation && it.busyAction == action) transform(it) else it
    }
  }

  private fun isCurrentAction(action: WearAction, generation: Long): Boolean =
      actionGeneration == generation && _uiState.value.busyAction == action

  private fun cancelBusyAction(action: WearAction) {
    if (_uiState.value.busyAction == action) {
      actionGeneration += 1
      busyTimeoutJob?.cancel()
    }
  }

  private fun Tailcfg.Node.toWearSelfNode(tailnetName: String): WearSelfNode =
      WearSelfNode(
          stableId = StableID,
          displayName = displayName,
          dnsName = nameWithoutTrailingDot,
          ipv4 = primaryIPv4Address,
          ipv6 = primaryIPv6Address,
          addresses = Addresses.orEmpty().map(::withoutPrefixLength),
          tailnetName = tailnetName,
          keyExpiry = KeyExpiry,
      )

  private fun Tailcfg.Node.toWearPeer(netmap: Netmap.NetworkMap?): WearPeer =
      WearPeer(
          stableId = StableID,
          displayName = displayName,
          dnsName = nameWithoutTrailingDot,
          ipv4 = primaryIPv4Address,
          ipv6 = primaryIPv6Address,
          addresses = Addresses.orEmpty().map(::withoutPrefixLength),
          online = connectedOrSelfNode(netmap),
          isSelf = netmap?.let { isSelfNode(it) } == true,
          os = Hostinfo.OS,
          lastSeen = LastSeen,
          keyExpiry = KeyExpiry,
      )

  private fun withoutPrefixLength(address: String): String = address.substringBefore("/")

  private fun loginCodeFromUrl(url: String?): String? {
    if (url == null) return null
    return url.removePrefix(LOGIN_URL_PREFIX).takeIf { url.startsWith(LOGIN_URL_PREFIX) }
  }

  private fun clearLegacyLoginUrlPersistence() {
    // AuthURLs are bearer credentials and attempt freshness is now generation-scoped in memory.
    // Remove both historical formats once and never persist a URL or fingerprint again.
    localPreferences.edit {
      remove(KEY_LAST_PRESENTED_LOGIN_URL)
      remove(KEY_LAST_PRESENTED_LOGIN_URL_FINGERPRINT)
    }
  }

  private fun Throwable.readableMessage(): String =
      message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "Unknown error"

  private val LoginMethod?.errorOrigin: WearErrorOrigin
    get() =
        when (this) {
          LoginMethod.AuthenticationKey -> WearErrorOrigin.AuthenticationKey
          LoginMethod.CustomServer -> WearErrorOrigin.CustomServer
          LoginMethod.Tailscale,
          null -> WearErrorOrigin.InteractiveLogin
        }

  companion object {
    private const val PREFERENCES_FILE = "tailware_wear_preferences"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_OPTIONAL_NOTIFICATIONS = "optional_notifications"
    private const val KEY_TILE_QUICK_ACTION = "tile_quick_action"
    private const val KEY_TILE_SHOW_IP = "tile_show_ip"
    private const val KEY_TILE_SHOW_EXIT_NODE = "tile_show_exit_node"
    private const val KEY_TILE_SHOW_MAGIC_DNS = "tile_show_magic_dns"
    private const val KEY_LAST_PRESENTED_LOGIN_URL = "last_presented_login_url"
    private const val KEY_LAST_PRESENTED_LOGIN_URL_FINGERPRINT =
        "last_presented_login_url_fingerprint"
    private const val LOGIN_URL_PREFIX = "https://login.tailscale.com/a/"
    private const val ACTION_TIMEOUT_MILLIS = 15_000L
    private const val LOGIN_TIMEOUT_MILLIS = 30_000L
    private const val LOGIN_CLEANUP_TIMEOUT_MILLIS = 15_000L
    private const val LOGOUT_TIMEOUT_MILLIS = 35_000L
    private const val LOGOUT_VERIFICATION_DELAY_MILLIS = 750L
    private const val MAX_LOGOUT_VERIFICATION_ATTEMPTS = 12
    private const val MAX_BACKEND_ERROR_LENGTH = 180
  }
}
