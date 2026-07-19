// Copyright (c) Tailware contributors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.net.VpnService
import android.os.Build
import android.os.LocaleList
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.NetworkChangeCallback
import com.tailscale.ipn.UnderlyingNetworkStatus
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Health
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.viewModel.IpnViewModel
import com.tailscale.ipn.wear.tile.TailwareTileUpdates
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun shouldAcceptLoginUrl(
    activeAttempt: Boolean,
    candidateUrl: String?,
    staleUrlFingerprint: String?,
): Boolean =
    activeAttempt &&
        candidateUrl != null &&
        loginUrlFingerprint(candidateUrl) != staleUrlFingerprint

internal fun loginUrlFingerprint(url: String): String {
  val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
  val hex = CharArray(digest.size * 2)
  val alphabet = "0123456789abcdef"
  digest.forEachIndexed { index, byte ->
    val value = byte.toInt() and 0xff
    hex[index * 2] = alphabet[value ushr 4]
    hex[index * 2 + 1] = alphabet[value and 0x0f]
  }
  return String(hex)
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
  private var staleLoginUrlFingerprint: String? =
      localPreferences.getString(KEY_LAST_PRESENTED_LOGIN_URL_FINGERPRINT, null)
  private var loginCancellationInProgress = false
  private var loginCancellationGeneration = 0L
  private var logoutProfile: IpnLocal.LoginProfile? = null

  private val _uiState =
      MutableStateFlow(
          WearUiState(
              language =
                  TailwareLanguage.fromStoredValue(localPreferences.getString(KEY_LANGUAGE, null)),
              optionalNotificationsEnabled = initialOptionalNotificationsEnabled(),
              tileQuickAction =
                  TileQuickAction.fromStoredValue(
                      localPreferences.getString(KEY_TILE_QUICK_ACTION, null)),
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
    migrateLegacyLoginUrl()
    applyAppLocale(_uiState.value.language)
    // LoginFinished is modeled upstream as state even though it is an event. Never replay a
    // completion from an older Activity/ViewModel into a new login attempt.
    Notifier.loginFinished.set(null)
    observeBackend()
    observeLoginUrl()
    observeLoginCompletion()
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
        // Ignore a replay from an abandoned/rejected attempt. The backend may briefly publish the
        // previous AuthURL while its new control client is starting.
        if (!shouldAcceptLoginUrl(acceptLoginUrls, url, staleLoginUrlFingerprint)) return@collect
        rememberPresentedLoginUrl(url)
        _uiState.update { current ->
          current.copy(
              accountState = WearAccountState.Authenticating,
              loginUrl = url,
              loginCode = loginCodeFromUrl(url),
              phoneLoginSent = false,
              busyAction = null,
          )
        }
      }
    }
  }

  private fun observeLoginCompletion() {
    viewModelScope.launch {
      Notifier.loginFinished.filterNotNull().collect {
        if (!acceptLoginUrls) {
          Notifier.loginFinished.set(null)
          return@collect
        }
        acceptLoginUrls = false
        Notifier.browseToURL.set(null)
        Notifier.loginFinished.set(null)
        _uiState.update {
          it.copy(
              loginUrl = null,
              loginCode = null,
              phoneLoginSent = false,
              busyAction = null,
              error = null,
          )
        }
      }
    }
  }

  private fun applyBackendSnapshot(snapshot: BackendSnapshot) {
    val hasProfile = snapshot.profile != null && !snapshot.profile.isEmpty()
    if (hasProfile) {
      acceptLoginUrls = false
      if (Notifier.browseToURL.value != null) Notifier.browseToURL.set(null)
      if (Notifier.loginFinished.value != null) Notifier.loginFinished.set(null)
    }
    if (!hasProfile && snapshot.profiles != null && snapshot.ipnState == Ipn.State.NeedsLogin) {
      // A proven signed-out state must never retain an older reboot-on preference. Do not mirror
      // WantRunning here: service actions are the authority for the user's on/off choice.
      TailwareVpnStateStore.setDesiredVpnEnabled(app, false)
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
          tunnelStatus = tunnelStatus(snapshot.ipnState, snapshot.vpnActive, current.busyAction),
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
          tailscaleDnsEnabled = hasProfile && snapshot.prefs?.CorpDNS == true,
          underlyingNetworkStatus = snapshot.underlyingNetworkStatus,
          healthWarnings = healthWarnings,
          loginUrl = if (hasProfile) null else current.loginUrl,
          loginCode = if (hasProfile) null else current.loginCode,
          phoneLoginSent = if (hasProfile) false else current.phoneLoginSent,
          busyAction = settledBusyAction(current.busyAction, snapshot),
      )
    }
  }

  private fun accountState(
      snapshot: BackendSnapshot,
      current: WearUiState,
  ): WearAccountState =
      when {
        snapshot.ipnState == Ipn.State.NeedsMachineAuth ->
            WearAccountState.NeedsMachineAuthorization
        snapshot.profile != null && !snapshot.profile.isEmpty() -> WearAccountState.SignedIn
        current.loginUrl != null || current.busyAction == WearAction.Login ->
            WearAccountState.Authenticating
        snapshot.ipnState == Ipn.State.NeedsLogin -> WearAccountState.SignedOut
        else -> WearAccountState.Loading
      }

  private fun tunnelStatus(
      ipnState: Ipn.State,
      vpnActive: Boolean,
      busyAction: WearAction?,
  ): WearTunnelStatus =
      when {
        ipnState == Ipn.State.Stopping || busyAction == WearAction.Disconnect ->
            WearTunnelStatus.Disconnecting
        ipnState == Ipn.State.Running && vpnActive -> WearTunnelStatus.Connected
        ipnState == Ipn.State.Starting -> WearTunnelStatus.Connecting
        busyAction == WearAction.Connect -> WearTunnelStatus.Connecting
        ipnState == Ipn.State.NoState -> WearTunnelStatus.Unknown
        else -> WearTunnelStatus.Disconnected
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
              snapshot.profile != null || snapshot.ipnState == Ipn.State.NeedsMachineAuth -> null
              else -> busyAction
            }
        WearAction.Logout ->
            if (snapshot.ipnState == Ipn.State.NeedsLogin &&
                snapshot.profile == null &&
                (logoutProfile == null ||
                    snapshot.profiles?.none { it.ID == logoutProfile?.ID } == true)) {
              logoutProfile = null
              null
            } else {
              busyAction
            }
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
    startVPN()
    clearBusyAfterTimeout(WearAction.Connect, generation)
  }

  fun disconnect() {
    val generation = startBusyAction(WearAction.Disconnect)
    stopVPN()
    clearBusyAfterTimeout(WearAction.Disconnect, generation)
  }

  fun startInteractiveLogin() {
    if (_uiState.value.busyAction == WearAction.Login || loginCancellationInProgress) return
    val generation = beginLoginAttempt()
    login(useForegroundService = false) { result ->
      completeUnitAction(WearAction.Login, result, generation)
    }
  }

  fun startAuthKeyLogin(authKey: String) {
    if (_uiState.value.busyAction == WearAction.Login || loginCancellationInProgress) return
    if (authKey.isBlank()) {
      setError(WearAction.Login, "Enter an auth key.")
      return
    }
    val generation = beginLoginAttempt()
    loginWithAuthKey(authKey.trim(), useForegroundService = false) { result ->
      completeUnitAction(WearAction.Login, result, generation)
    }
  }

  fun startCustomControlLogin(controlUrl: String) {
    if (_uiState.value.busyAction == WearAction.Login || loginCancellationInProgress) return
    val normalized = controlUrl.trim()
    val parsedUrl = normalized.toUri()
    if (!parsedUrl.scheme.equals("https", ignoreCase = true) || parsedUrl.host.isNullOrBlank()) {
      setError(WearAction.Login, "Enter a valid HTTPS control server URL.")
      return
    }
    val generation = beginLoginAttempt()
    loginWithCustomControlURL(normalized, useForegroundService = false) { result ->
      completeUnitAction(WearAction.Login, result, generation)
    }
  }

  fun cancelInteractiveLogin() {
    if (loginCancellationInProgress) return
    if (_uiState.value.accountState == WearAccountState.SignedIn ||
        _uiState.value.accountState == WearAccountState.NeedsMachineAuthorization) {
      return
    }
    loginCancellationInProgress = true
    loginCancellationGeneration += 1
    val cancellationGeneration = loginCancellationGeneration
    acceptLoginUrls = false
    Notifier.browseToURL.set(null)
    Notifier.loginFinished.set(null)
    TailwareVpnStateStore.setDesiredVpnEnabled(app, false)
    stopVPN()
    startBusyAction(WearAction.Login, clearLogin = true)
    viewModelScope.launch {
      val result =
          withContext(Dispatchers.IO) {
            runCatching {
              // DisconnectControl atomically clears the pending AuthURL without changing the
              // selected profile, node keys, or persisted login preferences.
              app.getLibtailscaleApp().cancelInteractiveLogin()
            }
          }
      completeLoginCancellation(result, cancellationGeneration)
    }
  }

  fun onPhoneLoginOpened(url: String) {
    if (_uiState.value.loginUrl != url) return
    _uiState.update { it.copy(phoneLoginSent = true, error = null) }
  }

  fun onPhoneLoginLaunchFailed(url: String, error: Throwable) {
    if (_uiState.value.loginUrl != url) return
    setError(
        WearAction.Login,
        "Could not open the sign-in page on the paired phone: ${error.readableMessage()}",
    )
  }

  fun logoutAccount() {
    if (_uiState.value.underlyingNetworkStatus != UnderlyingNetworkStatus.Online) {
      setError(
          WearAction.Logout,
          "A verified internet connection is required to sign out safely. The account is still present on this watch.",
      )
      return
    }
    acceptLoginUrls = false
    Notifier.browseToURL.set(null)
    Notifier.loginFinished.set(null)
    logoutProfile = loggedInUser.value
    TailwareVpnStateStore.setDesiredVpnEnabled(app, false)
    stopVPN()
    val generation = startBusyAction(WearAction.Logout)
    clearBusyAfterTimeout(WearAction.Logout, generation, LOGOUT_TIMEOUT_MILLIS)
    logout { result ->
      result
          .onSuccess {
            updateForAction(WearAction.Logout, generation) {
              it.copy(
                  loginUrl = null,
                  loginCode = null,
                  phoneLoginSent = false,
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
    if (_uiState.value.accountState != WearAccountState.SignedIn) {
      setError(WearAction.ToggleDns, "Sign in before changing Tailscale DNS.")
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
    val netmap = Notifier.netmap.value
    val peer = netmap?.getPeer(stableId)
    if (peer == null) {
      setError(WearAction.Ping, "The selected device is no longer available.")
      return
    }
    if (peer.primaryIPv4Address.isNullOrBlank()) {
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
              updateForAction(WearAction.Ping, generation) {
                it.copy(
                    busyAction = null,
                    pingResult =
                        WearPingResult(
                            peerId = peer.StableID,
                            peerName = peer.displayName,
                            status = PingStatus.Failed,
                            error = ping.Err,
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
            updateForAction(WearAction.Ping, generation) {
              it.copy(
                  busyAction = null,
                  pingResult =
                      WearPingResult(
                          peerId = peer.StableID,
                          peerName = peer.displayName,
                          status = PingStatus.Failed,
                          error = error.readableMessage(),
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
    _uiState.update { it.copy(optionalNotificationsEnabled = enabled) }
  }

  fun syncOptionalNotificationPermission() {
    _uiState.update {
      it.copy(optionalNotificationsEnabled = initialOptionalNotificationsEnabled())
    }
  }

  fun onVpnSettingsLaunchFailed() {
    setError(
        null,
        "Android could not open VPN settings on this watch. Use the one-time ADB setup instead.",
    )
  }

  fun setTileQuickAction(action: TileQuickAction) {
    localPreferences.edit { putString(KEY_TILE_QUICK_ACTION, action.name) }
    _uiState.update { it.copy(tileQuickAction = action) }
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

  private fun beginLoginAttempt(): Long {
    (_uiState.value.loginUrl ?: Notifier.browseToURL.value)?.let {
      staleLoginUrlFingerprint = loginUrlFingerprint(it)
    }
    acceptLoginUrls = true
    Notifier.loginFinished.set(null)
    return startBusyAction(WearAction.Login, clearLogin = true)
  }

  private fun isCurrentLoginCancellation(generation: Long): Boolean =
      loginCancellationInProgress && loginCancellationGeneration == generation

  private fun completeLoginCancellation(result: Result<Unit>, generation: Long) {
    if (!isCurrentLoginCancellation(generation)) return
    loginCancellationInProgress = false
    result
        .onSuccess {
          _uiState.update {
            it.copy(
                accountState =
                    if (it.profileId == null) WearAccountState.SignedOut else it.accountState,
                busyAction = if (it.busyAction == WearAction.Login) null else it.busyAction,
                loginUrl = null,
                loginCode = null,
                phoneLoginSent = false,
                error = null,
            )
          }
        }
        .onFailure { error ->
          acceptLoginUrls = false
          Notifier.browseToURL.set(null)
          _uiState.update {
            it.copy(
                busyAction = if (it.busyAction == WearAction.Login) null else it.busyAction,
                loginUrl = null,
                loginCode = null,
                phoneLoginSent = false,
                error = WearError(operation = WearAction.Login, message = error.readableMessage()),
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

          if (!isCurrentAction(WearAction.Logout, generation)) return@currentProfileCallback
          loginProfiles.set(profiles)
          loggedInUser.set(normalizedCurrent)
          if (normalizedCurrent == null && remainingOldProfile == null) {
            logoutProfile = null
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
                  healthWarnings = emptyList(),
                  error = null,
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
          phoneLoginSent = if (clearLogin) false else it.phoneLoginSent,
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
    if (action == WearAction.Login) {
      acceptLoginUrls = false
      Notifier.browseToURL.set(null)
    }
    updateForAction(action, generation) {
      it.copy(
          busyAction = null,
          error = WearError(operation = action, message = error.readableMessage()),
      )
    }
  }

  private fun setError(action: WearAction?, message: String) {
    actionGeneration += 1
    busyTimeoutJob?.cancel()
    _uiState.update {
      it.copy(busyAction = null, error = WearError(operation = action, message = message))
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
          _uiState.update {
            if (it.busyAction == action && actionGeneration == generation) {
              it.copy(
                  busyAction = null,
                  error =
                      WearError(
                          operation = action,
                          message = "The operation is taking longer than expected.",
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

  @SuppressLint("ApplySharedPref")
  private fun rememberPresentedLoginUrl(url: String) {
    staleLoginUrlFingerprint = loginUrlFingerprint(url)
    // Persist only a one-way fingerprint: the AuthURL itself is a bearer credential.
    localPreferences.edit(commit = true) {
      putString(KEY_LAST_PRESENTED_LOGIN_URL_FINGERPRINT, staleLoginUrlFingerprint)
      remove(KEY_LAST_PRESENTED_LOGIN_URL)
    }
  }

  @SuppressLint("ApplySharedPref")
  private fun migrateLegacyLoginUrl() {
    val legacyUrl = localPreferences.getString(KEY_LAST_PRESENTED_LOGIN_URL, null) ?: return
    if (staleLoginUrlFingerprint == null) {
      staleLoginUrlFingerprint = loginUrlFingerprint(legacyUrl)
    }
    localPreferences.edit(commit = true) {
      putString(KEY_LAST_PRESENTED_LOGIN_URL_FINGERPRINT, staleLoginUrlFingerprint)
      remove(KEY_LAST_PRESENTED_LOGIN_URL)
    }
  }

  private fun Throwable.readableMessage(): String =
      message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "Unknown error"

  companion object {
    private const val PREFERENCES_FILE = "tailware_wear_preferences"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_OPTIONAL_NOTIFICATIONS = "optional_notifications"
    private const val KEY_TILE_QUICK_ACTION = "tile_quick_action"
    private const val KEY_LAST_PRESENTED_LOGIN_URL = "last_presented_login_url"
    private const val KEY_LAST_PRESENTED_LOGIN_URL_FINGERPRINT =
        "last_presented_login_url_fingerprint"
    private const val LOGIN_URL_PREFIX = "https://login.tailscale.com/a/"
    private const val ACTION_TIMEOUT_MILLIS = 15_000L
    private const val LOGIN_TIMEOUT_MILLIS = 30_000L
    private const val LOGOUT_TIMEOUT_MILLIS = 35_000L
    private const val LOGOUT_VERIFICATION_DELAY_MILLIS = 600L
    private const val MAX_LOGOUT_VERIFICATION_ATTEMPTS = 5
  }
}
