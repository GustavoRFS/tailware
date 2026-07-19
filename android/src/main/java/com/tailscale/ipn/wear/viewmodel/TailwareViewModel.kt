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
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.viewModel.IpnViewModel
import com.tailscale.ipn.wear.tile.TailwareTileUpdates
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Wear-specific application state and actions layered on the existing Android Tailscale backend.
 *
 * This class contains no Compose or Activity code. The future Wear activity is responsible for
 * launching [vpnConsentIntent] and passing the result to [onVpnConsentResult].
 */
class TailwareViewModel : IpnViewModel() {
  private val app = App.get()
  private val appViewModel = app.getAppScopedViewModel()
  private val localPreferences = app.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
  private var actionGeneration = 0L
  private var busyTimeoutJob: Job? = null

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
      val vpnActive: Boolean = false,
      val vpnPrepared: Boolean = false,
  )

  init {
    applyAppLocale(_uiState.value.language)
    observeBackend()
    observeLoginUrl()
    observeLoginCompletion()
    refreshVpnAuthorization(app)
  }

  private fun observeBackend() {
    viewModelScope.launch {
      combine(Notifier.state, Notifier.netmap, Notifier.prefs, loggedInUser) {
              ipnState,
              netmap,
              prefs,
              profile ->
            BackendSnapshot(ipnState, netmap, prefs, profile)
          }
          .combine(appViewModel.vpnActive) { snapshot, active -> snapshot.copy(vpnActive = active) }
          .combine(appViewModel.vpnPrepared) { snapshot, prepared ->
            snapshot.copy(vpnPrepared = prepared)
          }
          .collect(::applyBackendSnapshot)
    }
  }

  private fun observeLoginUrl() {
    viewModelScope.launch {
      Notifier.browseToURL.collect { url ->
        _uiState.update { current ->
          current.copy(
              accountState =
                  if (url != null) WearAccountState.Authenticating else current.accountState,
              loginUrl = url,
              loginCode = loginCodeFromUrl(url),
              busyAction = if (url != null) null else current.busyAction,
          )
        }
      }
    }
  }

  private fun observeLoginCompletion() {
    viewModelScope.launch {
      Notifier.loginFinished.collect {
        Notifier.browseToURL.set(null)
        _uiState.update {
          it.copy(loginUrl = null, loginCode = null, busyAction = null, error = null)
        }
      }
    }
  }

  private fun applyBackendSnapshot(snapshot: BackendSnapshot) {
    val hasProfile = snapshot.profile != null && !snapshot.profile.isEmpty()
    if (hasProfile && Notifier.browseToURL.value != null) {
      Notifier.browseToURL.set(null)
    }
    _uiState.update { current ->
      val self = snapshot.netmap?.SelfNode
      val profileTailnetName =
          snapshot.profile?.NetworkProfile?.tailnetNameForDisplay()?.takeIf { it.isNotBlank() }
      val tailnetName =
          profileTailnetName
              ?: snapshot.netmap?.Domain?.takeIf { it.isNotBlank() }
              ?: current.tailnetName
      // InternalExitNodePrior is a remembered prior choice, not the current selection.
      val selectedExitNodeId = snapshot.prefs?.activeExitNodeID
      val activeExitNodeId = snapshot.prefs?.activeExitNodeID
      val allNodes = buildList {
        self?.let { add(it) }
        addAll(snapshot.netmap?.Peers.orEmpty())
      }
      val exitNodeModels =
          snapshot.netmap
              ?.Peers
              .orEmpty()
              .filter { it.isExitNode }
              .sortedWith(
                  compareByDescending<Tailcfg.Node> { it.StableID == selectedExitNodeId }
                      .thenByDescending { it.Online == true }
                      .thenBy { it.exitNodeName.lowercase() })
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
      val selectedExitNode = exitNodeModels.firstOrNull { it.stableId == selectedExitNodeId }
      val activeExitNode = exitNodeModels.firstOrNull { it.stableId == activeExitNodeId }

      current.copy(
          accountState = accountState(snapshot, current),
          ipnState = snapshot.ipnState,
          tunnelStatus = tunnelStatus(snapshot.ipnState, snapshot.vpnActive, current.busyAction),
          vpnAuthorization =
              if (snapshot.vpnPrepared) VpnAuthorization.Granted
              else
                  current.vpnAuthorization.takeUnless { it == VpnAuthorization.Granted }
                      ?: VpnAuthorization.SystemConsentAvailable,
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
          allowLanAccess = snapshot.prefs?.ExitNodeAllowLANAccess == true,
          tailscaleDnsEnabled = snapshot.prefs?.CorpDNS == true,
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
        busyAction == WearAction.Connect -> WearTunnelStatus.Connecting
        ipnState == Ipn.State.NoState -> WearTunnelStatus.Unknown
        else -> WearTunnelStatus.Disconnected
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
            if (snapshot.profile != null || snapshot.ipnState == Ipn.State.NeedsMachineAuth) null
            else busyAction
        WearAction.Logout ->
            if (snapshot.ipnState == Ipn.State.NeedsLogin && snapshot.profile == null) null
            else busyAction
        else -> busyAction
      }

  /**
   * Rechecks Android's VPN authorization.
   *
   * `VpnService.prepare() == null` is the only authoritative "granted" signal. When an intent is
   * returned, its resolvability distinguishes a normal system-consent flow from watches that need
   * the one-time ADB bootstrap.
   */
  fun refreshVpnAuthorization(context: Context): VpnAuthorization {
    val authorization =
        try {
          val intent = VpnService.prepare(context)
          when {
            intent == null -> VpnAuthorization.Granted
            canResolve(context, intent) -> VpnAuthorization.SystemConsentAvailable
            else -> VpnAuthorization.AdbRequired
          }
        } catch (error: RuntimeException) {
          setError(null, error)
          VpnAuthorization.AdbRequired
        }

    appViewModel.setVpnPrepared(authorization == VpnAuthorization.Granted)
    _uiState.update { it.copy(vpnAuthorization = authorization) }
    return authorization
  }

  /** Returns the system-consent intent only when Wear OS exposes a handler for it. */
  fun vpnConsentIntent(context: Context): Intent? {
    return try {
      val intent = VpnService.prepare(context) ?: return null
      if (canResolve(context, intent)) intent else null
    } catch (error: RuntimeException) {
      onVpnConsentLaunchFailed(error.message)
      null
    }
  }

  fun onVpnConsentResult(granted: Boolean, context: Context) {
    // The activity result itself is not authoritative on all OEM builds. Re-run prepare() and only
    // connect when Android now returns null.
    val authorization = refreshVpnAuthorization(context)
    if (authorization == VpnAuthorization.Granted) {
      _uiState.update { it.copy(error = null) }
      connect()
    } else if (granted) {
      onVpnConsentLaunchFailed(
          "Android returned from the VPN confirmation, but Tailware is still not authorized.")
    }
  }

  /** Call this when launching the returned system-consent intent itself fails on a watch. */
  fun onVpnConsentLaunchFailed(message: String? = null) {
    appViewModel.setVpnPrepared(false)
    _uiState.update {
      it.copy(
          vpnAuthorization = VpnAuthorization.AdbRequired,
          busyAction = null,
          error =
              WearError(
                  operation = WearAction.Connect,
                  message = message ?: "This watch requires one-time VPN setup through ADB.",
              ),
      )
    }
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
    val generation = startBusyAction(WearAction.Login, clearLogin = true)
    login(useForegroundService = false) { result ->
      completeUnitAction(WearAction.Login, result, generation)
    }
  }

  fun startAuthKeyLogin(authKey: String) {
    if (authKey.isBlank()) {
      setError(WearAction.Login, "Enter an auth key.")
      return
    }
    val generation = startBusyAction(WearAction.Login, clearLogin = true)
    loginWithAuthKey(authKey.trim(), useForegroundService = false) { result ->
      completeUnitAction(WearAction.Login, result, generation)
    }
  }

  fun startCustomControlLogin(controlUrl: String) {
    val normalized = controlUrl.trim()
    val parsedUrl = normalized.toUri()
    if (!parsedUrl.scheme.equals("https", ignoreCase = true) || parsedUrl.host.isNullOrBlank()) {
      setError(WearAction.Login, "Enter a valid HTTPS control server URL.")
      return
    }
    val generation = startBusyAction(WearAction.Login, clearLogin = true)
    loginWithCustomControlURL(normalized, useForegroundService = false) { result ->
      completeUnitAction(WearAction.Login, result, generation)
    }
  }

  fun logoutAccount() {
    Notifier.browseToURL.set(null)
    val generation = startBusyAction(WearAction.Logout)
    logout { result ->
      result
          .onSuccess {
            updateForAction(WearAction.Logout, generation) {
              it.copy(
                  busyAction = null,
                  loginUrl = null,
                  loginCode = null,
                  pingResult = WearPingResult(),
                  error = null,
              )
            }
          }
          .onFailure { completeFailure(WearAction.Logout, it, generation) }
    }
  }

  fun toggleTailscaleDns() {
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
      )
    }
    return actionGeneration
  }

  private fun completeUnitAction(action: WearAction, result: Result<Unit>, generation: Long) {
    result
        .onSuccess {
          updateForAction(action, generation) { it.copy(busyAction = null, error = null) }
        }
        .onFailure { completeFailure(action, it, generation) }
  }

  private fun completeFailure(action: WearAction, error: Throwable, generation: Long) {
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

  private fun clearBusyAfterTimeout(action: WearAction, generation: Long) {
    busyTimeoutJob?.cancel()
    busyTimeoutJob =
        viewModelScope.launch {
          delay(ACTION_TIMEOUT_MILLIS)
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

  @Suppress("DEPRECATION")
  private fun canResolve(context: Context, intent: Intent): Boolean =
      context.packageManager.resolveActivity(intent, 0) != null

  private fun Throwable.readableMessage(): String =
      message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "Unknown error"

  companion object {
    private const val PREFERENCES_FILE = "tailware_wear_preferences"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_OPTIONAL_NOTIFICATIONS = "optional_notifications"
    private const val KEY_TILE_QUICK_ACTION = "tile_quick_action"
    private const val LOGIN_URL_PREFIX = "https://login.tailscale.com/a/"
    private const val ACTION_TIMEOUT_MILLIS = 15_000L
  }
}
