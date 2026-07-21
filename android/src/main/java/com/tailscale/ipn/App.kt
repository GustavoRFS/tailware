// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.MDMSettingsChangedReceiver
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.localapi.Request
import com.tailscale.ipn.ui.localapi.WANT_RUNNING_LOCAL_API_TIMEOUT_MILLIS
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.notifier.HealthNotifier
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.viewModel.AppViewModel
import com.tailscale.ipn.ui.viewModel.AppViewModelFactory
import com.tailscale.ipn.util.FeatureFlags
import com.tailscale.ipn.util.HardwareKeyStore
import com.tailscale.ipn.util.NoSuchKeyException
import com.tailscale.ipn.util.ShareFileHelper
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.wear.DesiredVpnState
import com.tailscale.ipn.wear.TailwareActivationCapability
import com.tailscale.ipn.wear.TailwareVpnStateStore
import com.tailscale.ipn.wear.VpnStatePersistenceException
import com.tailscale.ipn.wear.WearMainActivity
import com.tailscale.ipn.wear.localization.TailwareStrings
import com.tailscale.ipn.wear.tile.TailwareTileStateStore
import com.tailscale.ipn.wear.tile.TailwareTileUpdates
import java.io.IOException
import java.lang.UnsupportedOperationException
import java.net.NetworkInterface
import java.security.GeneralSecurityException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import libtailscale.Libtailscale

data class VpnCommandDispatchResult(
    val command: DesiredVpnState,
    val dispatched: Boolean,
    val failedStartRollback: DesiredVpnState? = null,
    val fallbackStopSucceeded: Boolean? = null,
    val superseded: Boolean = false,
) {
  val stopSafelyHandled: Boolean
    get() = dispatched || fallbackStopSucceeded == true
}

/** Java-friendly completion hook used by BroadcastReceiver.goAsync(). */
fun interface VpnCommandDispatchCallback {
  fun onComplete(result: VpnCommandDispatchResult)
}

data class VpnServiceStopEvidence(
    val serviceMayExist: Boolean,
    val vpnWasDesired: Boolean,
    val tunnelWasActive: Boolean,
    val vpnCleanupGeneration: Long,
    val loginForegroundOwnerWasPresent: Boolean,
    val loginForegroundRequestFence: Long,
)

internal fun failedStopFallbackWithLoginOwner(
    vpnWasDesired: Boolean,
    tunnelWasActive: Boolean,
    vpnCleanupWasPending: Boolean,
    hasLoginForegroundOwner: Boolean,
): Boolean? =
    if (hasLoginForegroundOwner) {
      !(vpnWasDesired || tunnelWasActive || vpnCleanupWasPending)
    } else {
      null
    }

internal fun vpnCleanupGenerationAfterOutcome(
    currentGeneration: Long,
    outcomeGeneration: Long,
    handled: Boolean,
): Long {
  if (outcomeGeneration <= 0L) return currentGeneration
  return if (handled) {
    if (currentGeneration in 1L..outcomeGeneration) 0L else currentGeneration
  } else {
    maxOf(currentGeneration, outcomeGeneration)
  }
}

internal data class LoginForegroundPromotionRequest(
    val id: Long,
    val completion: CompletableDeferred<Result<Unit>>,
)

internal data class LoginForegroundLease(val requestId: Long)

internal data class LoginForegroundOwnerSnapshot(
    val hasOwner: Boolean,
    val requestFence: Long,
)

// Android's foreground-service deadline is roughly five seconds. Keep acknowledgement waiting
// longer so scheduler jitter on a slow watch cannot abandon a promotion Android still accepts.
internal const val LOGIN_FOREGROUND_PROMOTION_TIMEOUT_MILLIS = 10_000L

internal class LoginForegroundPromotionCoordinator {
  private val nextId = AtomicLong()
  private val requests = ConcurrentHashMap<Long, CompletableDeferred<Result<Unit>>>()
  private val liveOwners = ConcurrentHashMap.newKeySet<Long>()

  @Synchronized
  fun begin(): LoginForegroundPromotionRequest {
    val request =
        LoginForegroundPromotionRequest(
            id = nextId.incrementAndGet(),
            completion = CompletableDeferred(),
        )
    requests[request.id] = request.completion
    return request
  }

  @Synchronized
  fun beginWithDispatch(dispatch: (Long) -> Unit): LoginForegroundPromotionRequest {
    val request = begin()
    try {
      dispatch(request.id)
    } catch (error: Exception) {
      requests.remove(request.id)
      throw error
    }
    return request
  }

  @Synchronized fun <T> runLifecycle(action: () -> T): T = action()

  @Synchronized
  fun complete(id: Long, result: Result<Unit>): Boolean {
    // Remove ownership before publishing the result. If timeout/cancellation
    // wins the race via abandon(), the service must see false and close. If
    // completion wins, abandon() can no longer make the service close after
    // the caller has already observed success.
    val completion = requests.remove(id) ?: return false
    if (result.isSuccess) liveOwners.add(id)
    val completed = completion.complete(result)
    if (!completed) liveOwners.remove(id)
    return completed
  }

  @Synchronized fun abandon(id: Long): Boolean = requests.remove(id) != null

  @Synchronized
  fun release(id: Long): Boolean {
    val existed = requests.containsKey(id) || liveOwners.contains(id)
    requests.remove(id)
    liveOwners.remove(id)
    return existed
  }

  @Synchronized fun transferToVpn(id: Long) = liveOwners.remove(id)

  @Synchronized fun currentRequestFence(): Long = nextId.get()

  @Synchronized fun hasOwner(): Boolean = requests.isNotEmpty() || liveOwners.isNotEmpty()

  @Synchronized
  fun ownerSnapshot(): LoginForegroundOwnerSnapshot =
      LoginForegroundOwnerSnapshot(
          hasOwner = requests.isNotEmpty() || liveOwners.isNotEmpty(),
          requestFence = nextId.get(),
      )

  /**
   * Serializes a fallback stop against foreground-login creation. A begin that wins first is
   * visible in the owner sets; a begin that wins second dispatches a fresh service start.
   */
  @Synchronized
  fun runUnlessNewerOwner(
      requestFence: Long,
      preserveNewerOwner: Boolean,
      action: () -> Unit,
  ): Boolean {
    val hasNewerOwner =
        requests.keys.any { it > requestFence } || liveOwners.any { it > requestFence }
    if (preserveNewerOwner && hasNewerOwner) return false
    action()
    return true
  }
}

internal fun shouldPublishOptionalDisconnectedNotification(
    state: Ipn.State,
    vpnActive: Boolean,
    desiredVpnEnabled: Boolean,
): Boolean = state == Ipn.State.Stopped && !vpnActive && !desiredVpnEnabled

internal fun runBestEffortNotificationUpdate(
    update: () -> Unit,
    onFailure: (RuntimeException) -> Unit = {},
): Boolean =
    try {
      update()
      true
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (error: RuntimeException) {
      onFailure(error)
      false
    }

internal fun shouldForceStopVpnServiceAfterFailedDispatch(
    wasVpnActive: Boolean,
    failedCommand: DesiredVpnState,
    currentState: DesiredVpnState,
): Boolean = wasVpnActive && !failedCommand.enabled && currentState == failedCommand

internal enum class ExitNodeFailureReason(val messageResourceId: Int) {
  VpnSetupRequired(R.string.wear_vpn_setup_required),
  AccountSetupRequired(R.string.wear_finish_account_setup),
  NoDevicesAvailable(R.string.wear_no_devices_available_yet),
  ExitNodeUnavailable(R.string.wear_exit_node_unavailable);

  companion object {
    fun fromStored(value: String?): ExitNodeFailureReason? =
        entries.firstOrNull { it.name == value }
  }
}

internal data class TailwareNotificationRefreshPlan(
    val vpnStatus: Boolean,
    val vpnSetupRequired: Boolean,
    val vpnStopFailure: Boolean,
    val exitNodeFailure: Boolean,
)

internal fun tailwareNotificationRefreshPlan(
    activeNotificationIds: Set<Int>
): TailwareNotificationRefreshPlan =
    TailwareNotificationRefreshPlan(
        vpnStatus = UninitializedApp.STATUS_NOTIFICATION_ID in activeNotificationIds,
        vpnSetupRequired = StartVPNWorker.NOTIFICATION_ID in activeNotificationIds,
        vpnStopFailure = StartVPNWorker.STOP_FAILURE_NOTIFICATION_ID in activeNotificationIds,
        exitNodeFailure =
            UninitializedApp.STATUS_EXIT_NODE_FAILURE_NOTIFICATION_ID in activeNotificationIds,
    )

internal data class NativeBackendPublishPlan(
    val startupFailed: Boolean?,
    val attemptId: Long?,
    val restartNotifier: Boolean,
)

internal class NativeBackendStateCoordinator {
  private var bridgeReady = false
  private var viewModelReady = false
  private var hasBackendResult = false
  private var startupFailed = false
  private var latestAttemptId = 0L
  private var publishedState: Pair<Long, Boolean>? = null
  private var notifierRestartPending = false

  @Synchronized
  fun onStartupError(attemptId: Long): NativeBackendPublishPlan {
    if (!accept(attemptId, failed = true)) return emptyPlan()
    return publishPlan()
  }

  @Synchronized
  fun onRecovered(attemptId: Long): NativeBackendPublishPlan {
    if (!accept(attemptId, failed = false)) return emptyPlan()
    notifierRestartPending = true
    return publishPlan()
  }

  @Synchronized
  fun onBridgeReady(): NativeBackendPublishPlan {
    bridgeReady = true
    return publishPlan()
  }

  @Synchronized
  fun onViewModelReady(): NativeBackendPublishPlan {
    viewModelReady = true
    return publishPlan()
  }

  private fun accept(attemptId: Long, failed: Boolean): Boolean {
    if (attemptId <= 0L || attemptId < latestAttemptId) return false
    if (attemptId == latestAttemptId && hasBackendResult && startupFailed == failed) return false
    latestAttemptId = attemptId
    hasBackendResult = true
    startupFailed = failed
    return true
  }

  private fun publishPlan(): NativeBackendPublishPlan {
    val restart = bridgeReady && notifierRestartPending
    if (restart) {
      notifierRestartPending = false
    }
    val currentState =
        if (viewModelReady && hasBackendResult) latestAttemptId to startupFailed else null
    val publishState = currentState != null && currentState != publishedState
    if (publishState) publishedState = currentState
    return NativeBackendPublishPlan(
        startupFailed = currentState?.second.takeIf { publishState },
        attemptId = currentState?.first.takeIf { publishState },
        restartNotifier = restart,
    )
  }

  private fun emptyPlan() =
      NativeBackendPublishPlan(startupFailed = null, attemptId = null, restartNotifier = false)
}

class App : UninitializedApp(), libtailscale.AppContext, ViewModelStoreOwner {
  val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val nativeBackendState = NativeBackendStateCoordinator()
  private val nativeBackendStateLock = Any()
  private var nativeBackendErrorPublication: Long? = null

  companion object {
    // Key to store the SAF URI in EncryptedSharedPreferences.
    private val PREF_KEY_SAF_URI = "saf_directory_uri"
    private const val TAG = "App"
    private lateinit var appInstance: App
    /** Returns the initialized engine context only after a foreground user gesture activated it. */
    @JvmStatic
    fun get(): App {
      return TailwareActivationCapability.withEngineAuthorization {
        appInstance.initOnce()
        appInstance
      } ?: error("Tailware engine access requires a current-process user activation capability.")
    }
  }

  val dns = DnsConfig()
  private lateinit var connectivityManager: ConnectivityManager
  private lateinit var mdmChangeReceiver: MDMSettingsChangedReceiver
  private lateinit var app: libtailscale.Application
  override val viewModelStore: ViewModelStore
    get() = appViewModelStore

  private val appViewModelStore: ViewModelStore by lazy { ViewModelStore() }
  var healthNotifier: HealthNotifier? = null

  private data class WearNotificationSnapshot(
      val state: Ipn.State,
      val hideDisconnectAction: Boolean,
      val exitNodeName: String?,
      val selfIp: String?,
      val magicDnsName: String?,
      val tailscaleDnsEnabled: Boolean,
      val allowLanAccess: Boolean,
      val vpnActive: Boolean = false,
      val vpnAuthorized: Boolean = false,
      val underlyingOnline: Boolean? = null,
  )

  override fun getPlatformDNSConfig(): String = dns.dnsConfigAsString

  override fun getInstallSource(): String = AppSourceChecker.getInstallSource(this)

  override fun shouldUseGoogleDNSFallback(): Boolean = BuildConfig.USE_GOOGLE_DNS_FALLBACK

  override fun log(s: String, s1: String) {
    Log.d(s, s1)
  }

  override fun onNativeBackendStartupError(attemptId: Long) {
    updateNativeBackendState { onStartupError(attemptId) }
  }

  override fun onNativeBackendRecovered(attemptId: Long) {
    updateNativeBackendState { onRecovered(attemptId) }
  }

  private fun updateNativeBackendState(
      update: NativeBackendStateCoordinator.() -> NativeBackendPublishPlan
  ): NativeBackendPublishPlan =
      synchronized(nativeBackendStateLock) {
        nativeBackendState.update().also(::publishNativeBackendState)
      }

  private fun publishNativeBackendState(plan: NativeBackendPublishPlan) {
    plan.startupFailed?.let { startupFailed ->
      if (startupFailed) {
        // This path also runs with desired VPN OFF, so use the translated
        // process-level recovery message rather than a VPN-command error.
        nativeBackendErrorPublication =
            appViewModel.publishVpnServiceError(
                TailwareStrings.selectedText(this, R.string.wear_unexpected_error_try_again))
      } else {
        nativeBackendErrorPublication?.let(appViewModel::clearVpnServiceError)
        nativeBackendErrorPublication = null
      }
    }
    if (plan.restartNotifier) {
      // start() atomically invalidates and stops the old manager before its
      // replacement watcher can publish, including a watcher still returning
      // from the failed native readiness wait.
      Notifier.start(applicationScope)
    }
  }

  fun getLibtailscaleApp(): libtailscale.Application {
    check(TailwareActivationCapability.hasEngineCapability()) {
      "Native engine access requires a current-process user activation capability."
    }
    if (!isInitialized) {
      initOnce() // Calls the synchronized initialization logic
    }
    return app
  }

  override fun onCreate() {
    super.onCreate()
    appInstance = this
    setUnprotectedInstance(this)
    // P0 migration/version key: p0_containment_migration_version=1. This is a synchronous,
    // preference-only fail-closed cleanup and must remain before every other process-start action.
    TailwareVpnStateStore.ensureP0Containment(this)
    // A new process cannot inherit an established TUN from an older process. This also prevents a
    // cold Tile request after reboot/force-stop from displaying a stale connected state.
    TailwareTileStateStore.beginProcessSession(this)
  }

  override fun onTerminate() {
    super.onTerminate()
    if (isInitialized) {
      Notifier.stop()
      notificationManager.cancelAll()
      applicationScope.cancel()
      viewModelStore.clear()
      unregisterReceiver(mdmChangeReceiver)
    }
  }

  @Volatile private var isInitialized = false

  @Synchronized
  private fun initOnce() {
    check(TailwareActivationCapability.hasEngineCapability()) {
      "Tailware engine initialization requires a current-process user activation capability."
    }
    check(TailwareVpnStateStore.ensureP0Containment(this)) {
      "Tailware containment state could not be persisted; engine initialization is blocked."
    }
    if (isInitialized) {
      return
    }
    initializeApp()
    isInitialized = true
  }

  private fun initializeApp() {
    mdmChangeReceiver = MDMSettingsChangedReceiver()
    val filter = IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED)
    registerReceiver(mdmChangeReceiver, filter)
    runBestEffortNotificationUpdate(
        update = ::refreshTailwareNotificationChannels,
        onFailure = { error ->
          TSLog.e(TAG, "Unable to initialize Tailware notification channels", error)
        },
    )
    // Check if a directory URI has already been stored.
    val storedUri = getStoredDirectoryUri()
    val rm = getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
    val hardwareAttestation =
        rm.applicationRestrictions.getBoolean(MDMSettings.KEY_HARDWARE_ATTESTATION, true)

    // Populate MDM settings before starting Tailscale so that the rsop
    // policy framework reads correct values during its initial synchronous load
    // in newPolicy(). If MDM settings are not populated, rsop caches "not configured"
    // for Hostname and only corrects it after policyReloadMinDelay,
    // at which point the device may have already registered with the wrong hostname.
    MDMSettings.loadFrom(lazy { getEncryptedPrefs() }, rm)

    if (storedUri != null && storedUri.toString().startsWith("content://")) {
      startLibtailscale(storedUri.toString(), hardwareAttestation)
    } else {
      startLibtailscale(this.filesDir.absolutePath, hardwareAttestation)
    }
    healthNotifier = HealthNotifier(Notifier.health, Notifier.state, applicationScope)
    connectivityManager = this.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    NetworkChangeCallback.monitorDnsChanges(connectivityManager, dns)
    initViewModels()
    applicationScope.launch {
      val restrictionsManager =
          getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
      MDMSettings.update(get(), restrictionsManager)
    }
    applicationScope.launch {
      combine(Notifier.state, MDMSettings.forceEnabled.flow, Notifier.prefs, Notifier.netmap) {
              state,
              forceEnabled,
              prefs,
              netmap ->
            WearNotificationSnapshot(
                state = state,
                hideDisconnectAction = forceEnabled.value,
                exitNodeName = getExitNodeName(prefs, netmap),
                selfIp = netmap?.SelfNode?.primaryIPv4Address,
                magicDnsName = netmap?.SelfNode?.nameWithoutTrailingDot,
                tailscaleDnsEnabled = prefs?.CorpDNS == true,
                allowLanAccess = prefs?.activeExitNodeID != null && prefs.ExitNodeAllowLANAccess,
            )
          }
          .combine(appViewModel.vpnActive) { snapshot, vpnActive ->
            snapshot.copy(vpnActive = vpnActive)
          }
          .combine(appViewModel.vpnPrepared) { snapshot, vpnPrepared ->
            snapshot.copy(vpnAuthorized = vpnPrepared)
          }
          .combine(NetworkChangeCallback.underlyingNetworkStatus) { snapshot, networkStatus ->
            snapshot.copy(
                underlyingOnline =
                    when (networkStatus) {
                      UnderlyingNetworkStatus.Online -> true
                      UnderlyingNetworkStatus.Offline -> false
                      UnderlyingNetworkStatus.Limited,
                      UnderlyingNetworkStatus.Unknown -> null
                    })
          }
          .distinctUntilChanged()
          .collect { snapshot ->
            val ableToStartVPN = snapshot.state > Ipn.State.NeedsMachineAuth
            // Readiness and Tile state are authoritative. Publish them before touching the
            // best-effort notification service so a permission/OEM failure cannot skip this
            // snapshot or terminate its process-lifetime projection.
            updateConnStatus(ableToStartVPN)
            if (TailwareTileStateStore.write(
                context = this@App,
                ipnState = snapshot.state,
                vpnActive = snapshot.vpnActive,
                vpnAuthorized = snapshot.vpnAuthorized || snapshot.vpnActive,
                ableToStartVpn = ableToStartVPN,
                selfIp = snapshot.selfIp,
                activeExitNodeName = snapshot.exitNodeName,
                underlyingOnline = snapshot.underlyingOnline,
                tailscaleDnsEnabled = snapshot.tailscaleDnsEnabled,
                magicDnsName = snapshot.magicDnsName,
                allowLanAccess = snapshot.allowLanAccess,
            )) {
              TailwareTileUpdates.request(this@App)
            }
            runBestEffortNotificationUpdate(
                update = {
                  if (shouldPublishOptionalDisconnectedNotification(
                      state = snapshot.state,
                      vpnActive = snapshot.vpnActive,
                      desiredVpnEnabled = TailwareVpnStateStore.desiredVpnEnabled(this@App),
                  )) {
                    if (optionalWearNotificationsEnabled()) {
                      notifyStatus(
                          vpnRunning = false,
                          hideDisconnectAction = snapshot.hideDisconnectAction,
                      )
                    } else {
                      notificationManager.cancel(STATUS_OPTIONAL_NOTIFICATION_ID)
                    }
                  } else {
                    notificationManager.cancel(STATUS_OPTIONAL_NOTIFICATION_ID)
                  }
                },
                onFailure = { error ->
                  TSLog.e(TAG, "Unable to update the optional Wear status notification", error)
                },
            )
          }
    }
    TSLog.init(this)
    FeatureFlags.initialize(mapOf("enable_new_search" to true))
  }
  /**
   * Called when a SAF directory URI is available (either already stored or chosen). We must restart
   * Tailscale because directFileRoot must be set before LocalBackend starts being used.
   */
  fun startLibtailscale(directFileRoot: String, hardwareAttestation: Boolean) {
    check(TailwareActivationCapability.hasEngineCapability()) {
      "Native startup requires a current-process user activation capability."
    }
    app = Libtailscale.start(this.filesDir.absolutePath, directFileRoot, hardwareAttestation, this)
    TailwareActivationCapability.markNativeEngineStarted()
    ShareFileHelper.init(this, app, directFileRoot, applicationScope)
    Request.setApp(app)
    Notifier.setApp(app)
    val bridgePlan = updateNativeBackendState { onBridgeReady() }
    if (!bridgePlan.restartNotifier) {
      Notifier.start(applicationScope)
    }
  }

  private fun initViewModels() {
    appViewModel =
        ViewModelProvider(this, AppViewModelFactory(this, ShareFileHelper.observeTaildropPrompt()))
            .get(AppViewModel::class.java)
    updateNativeBackendState { onViewModelReady() }
  }

  fun setWantRunning(
      wantRunning: Boolean,
      reconcileDesiredState: Boolean = true,
      commandOwner: DesiredVpnState? = null,
      authenticationEpoch: Long? = null,
      onSuccess: (() -> Unit)? = null,
      onSuperseded: (() -> Unit)? = null,
      onFailure: ((Throwable) -> Unit)? = null,
  ) {
    if (commandOwner == null ||
        !TailwareActivationCapability.hasEngineCapability() ||
        (commandOwner.enabled &&
            !TailwareActivationCapability.acceptsVpnCommand(commandOwner.generation)) ||
        TailwareVpnStateStore.desiredVpnState(this) != commandOwner) {
      onFailure?.invoke(
          SecurityException("WantRunning requires the exact live desired-state command owner."))
      return
    }
    if (!app.advanceWantRunningGeneration(commandOwner.generation)) {
      // A newer persisted command already owns native WantRunning. Treat this request as
      // superseded rather than attempting a stale backend mutation.
      onSuperseded?.invoke()
      return
    }
    val callback: (Result<Ipn.Prefs>) -> Unit = { result ->
      result.fold(
          onSuccess = {
            val desired = TailwareVpnStateStore.desiredVpnState(this)
            if (commandOwner != null && desired != commandOwner) {
              // The callback belongs to an older generation. Reassert the latest desired state,
              // but never run the stale command's completion side effects.
              setWantRunning(
                  wantRunning = desired.enabled,
                  reconcileDesiredState = false,
                  commandOwner = desired,
                  authenticationEpoch = authenticationEpoch,
                  onSuccess = onSuperseded,
                  onSuperseded = onSuperseded,
                  onFailure = onFailure,
              )
            } else if (reconcileDesiredState && desired.enabled != wantRunning) {
              // A newer user command won while this asynchronous LocalAPI PATCH was in flight.
              // Reassert the newest value so an old STOP can never undo a later START (or vice
              // versa).
              setWantRunning(
                  wantRunning = desired.enabled,
                  reconcileDesiredState = true,
                  commandOwner = desired,
                  authenticationEpoch = authenticationEpoch,
                  onSuccess = onSuccess,
                  onSuperseded = onSuperseded,
                  onFailure = onFailure,
              )
            } else {
              onSuccess?.invoke()
            }
          },
          onFailure = { error ->
            if (error is com.tailscale.ipn.ui.localapi.LocalApiRequestSupersededException) {
              onSuperseded?.invoke()
              return@fold
            }
            TSLog.e(TAG, "Failed to update WantRunning=$wantRunning: $error")
            onFailure?.invoke(error)
          })
    }
    val requestAuthorization = {
      TailwareActivationCapability.hasEngineCapability() &&
          TailwareVpnStateStore.desiredVpnState(this) == commandOwner &&
          (!commandOwner.enabled ||
              TailwareActivationCapability.acceptsVpnCommand(commandOwner.generation))
    }
    Client(
            applicationScope,
            authenticationEpoch,
            wantRunningGeneration = commandOwner.generation,
            requestAuthorization = requestAuthorization,
        )
        .editPrefs(
            Ipn.MaskedPrefs().apply { WantRunning = wantRunning },
            timeoutMillis = WANT_RUNNING_LOCAL_API_TIMEOUT_MILLIS,
            responseHandler = callback,
        )
  }
  // encryptToPref a byte array of data using the Jetpack Security
  // library and writes it to a global encrypted preference store.
  @Throws(IOException::class, GeneralSecurityException::class)
  override fun encryptToPref(prefKey: String?, plaintext: String?) {
    if (!getEncryptedPrefs().edit().putString(prefKey, plaintext).commit()) {
      throw IOException("Unable to persist encrypted Tailware state")
    }
  }
  // decryptFromPref decrypts a encrypted preference using the Jetpack Security
  // library and returns the plaintext.
  @Throws(IOException::class, GeneralSecurityException::class)
  override fun decryptFromPref(prefKey: String?): String? {
    return getEncryptedPrefs().getString(prefKey, null)
  }

  override fun getStateStoreKeysJSON(): String {
    val prefix = "statestore-"
    val keys =
        getEncryptedPrefs()
            .getAll()
            .keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
    return org.json.JSONArray(keys).toString()
  }

  @Throws(IOException::class, GeneralSecurityException::class)
  fun getEncryptedPrefs(): SharedPreferences {
    val key = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    return EncryptedSharedPreferences.create(
        this,
        "secret_shared_prefs",
        key,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
  }

  fun getStoredDirectoryUri(): Uri? {
    val uriString = getEncryptedPrefs().getString(PREF_KEY_SAF_URI, null)
    return uriString?.let { Uri.parse(it) }
  }
  /*
   * setAbleToStartVPN remembers whether or not we're able to start the VPN
   * by storing this in a shared preference. This allows us to check this
   * value without needing a fully initialized instance of the application.
   */
  private fun updateConnStatus(ableToStartVPN: Boolean) {
    setAbleToStartVPN(ableToStartVPN)
    TSLog.d("App", "Set Tile Ready: $ableToStartVPN")
  }

  private fun optionalWearNotificationsEnabled(): Boolean =
      getSharedPreferences("tailware_wear_preferences", MODE_PRIVATE)
          .getBoolean(
              "optional_notifications",
              Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU,
          ) && NotificationManagerCompat.from(this).areNotificationsEnabled()

  fun refreshOptionalWearStatusNotification() {
    runBestEffortNotificationUpdate(
        update = {
          // A starting or active tunnel owns the mandatory foreground notification through
          // IPNService.
          if (appViewModel.vpnActive.value || TailwareVpnStateStore.desiredVpnEnabled(this)) {
            notificationManager.cancel(STATUS_OPTIONAL_NOTIFICATION_ID)
          } else if (optionalWearNotificationsEnabled()) {
            notifyStatus(vpnRunning = false, hideDisconnectAction = false)
          } else {
            notificationManager.cancel(STATUS_OPTIONAL_NOTIFICATION_ID)
          }
        },
        onFailure = { error ->
          TSLog.e(TAG, "Unable to refresh the optional Wear status notification", error)
        },
    )
  }

  /** Rebuilds currently relevant user-facing notifications after an app-language change. */
  fun refreshLocalizedNotifications() {
    runBestEffortNotificationUpdate(
        update = ::refreshTailwareNotificationChannels,
        onFailure = { error ->
          TSLog.e(TAG, "Unable to refresh localized notification channels", error)
        },
    )
    val refreshPlan = tailwareNotificationRefreshPlan(activeTailwareNotificationIds())
    StartVPNWorker.refreshActiveNotifications(
        this,
        refreshPlan.vpnSetupRequired,
        refreshPlan.vpnStopFailure,
    )
    healthNotifier?.refreshLocalizedNotifications()
    if (refreshPlan.exitNodeFailure) refreshExitNodeFailureNotification()
    if (refreshPlan.vpnStatus ||
        appViewModel.vpnActive.value ||
        TailwareVpnStateStore.desiredVpnEnabled(this)) {
      runCatching {
            startService(
                Intent(this, IPNService::class.java).apply {
                  action = IPNService.ACTION_REFRESH_NOTIFICATION
                })
          }
          .onFailure { error ->
            TSLog.e(TAG, "Unable to refresh the localized VPN notification: $error")
          }
    }
    refreshOptionalWearStatusNotification()
  }

  override fun getDeviceName(): String {
    // Try user-defined device name first
    android.provider.Settings.Global.getString(
            contentResolver, android.provider.Settings.Global.DEVICE_NAME)
        ?.let {
          return it
        }

    // Otherwise fallback to manufacturer + model
    val manu = Build.MANUFACTURER
    var model = Build.MODEL
    // Strip manufacturer from model.
    val idx = model.lowercase(Locale.getDefault()).indexOf(manu.lowercase(Locale.getDefault()))
    if (idx != -1) {
      model = model.substring(idx + manu.length).trim()
    }
    return "$manu $model"
  }

  override fun getOSVersion(): String = Build.VERSION.RELEASE

  override fun getSDKInt(): Long = Build.VERSION.SDK_INT.toLong()

  override fun isChromeOS(): Boolean {
    return packageManager.hasSystemFeature("android.hardware.type.pc")
  }

  override fun isClientLoggingEnabled(): Boolean {
    return getIsClientLoggingEnabled()
  }

  @Serializable
  data class AddrJson(
      val ip: String,
      val prefixLen: Int,
  )

  @Serializable
  data class InterfaceJson(
      val name: String,
      val index: Int,
      val mtu: Int,
      val up: Boolean,
      val broadcast: Boolean,
      val loopback: Boolean,
      val pointToPoint: Boolean,
      val multicast: Boolean,
      val addrs: List<AddrJson>,
  )

  override fun getInterfacesAsJson(): String {
    val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
    val out = ArrayList<InterfaceJson>(interfaces.size)

    for (nif in interfaces) {
      try {
        val addrs = ArrayList<AddrJson>()
        for (ia in nif.interfaceAddresses) {
          val addr = ia.address ?: continue
          // hostAddress is stable and avoids InterfaceAddress.toString() formatting risks.
          val host = addr.hostAddress ?: continue
          addrs.add(AddrJson(ip = host, prefixLen = ia.networkPrefixLength.toInt()))
        }

        out.add(
            InterfaceJson(
                name = nif.name,
                index = nif.index,
                mtu = nif.mtu,
                up = nif.isUp,
                broadcast = nif.supportsMulticast(),
                loopback = nif.isLoopback,
                pointToPoint = nif.isPointToPoint,
                multicast = nif.supportsMulticast(),
                addrs = addrs,
            ))
      } catch (_: Exception) {
        continue
      }
    }

    // Avoid pretty printing to keep payload small.
    return Json { encodeDefaults = true }.encodeToString(out)
  }

  @Throws(
      IOException::class, GeneralSecurityException::class, MDMSettings.NoSuchKeyException::class)
  override fun getSyspolicyBooleanValue(key: String): Boolean {
    return getSyspolicyStringValue(key) == "true"
  }

  @Throws(
      IOException::class, GeneralSecurityException::class, MDMSettings.NoSuchKeyException::class)
  override fun getSyspolicyStringValue(key: String): String {
    val setting = MDMSettings.allSettingsByKey[key]?.flow?.value
    if (setting?.isSet != true) {
      throw MDMSettings.NoSuchKeyException()
    }
    return setting.value?.toString() ?: ""
  }

  @Throws(
      IOException::class, GeneralSecurityException::class, MDMSettings.NoSuchKeyException::class)
  override fun getSyspolicyStringArrayJSONValue(key: String): String {
    val setting = MDMSettings.allSettingsByKey[key]?.flow?.value
    if (setting?.isSet != true) {
      throw MDMSettings.NoSuchKeyException()
    }
    try {
      val list = setting.value as? List<*>
      return Json.encodeToString(list)
    } catch (e: Exception) {
      TSLog.d("MDM", "$key value cannot be serialized to JSON. Throwing NoSuchKeyException.")
      throw MDMSettings.NoSuchKeyException()
    }
  }

  fun notifyPolicyChanged() {
    app.notifyPolicyChanged()
  }

  override fun hardwareAttestationKeySupported(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    } else {
      false
    }
  }

  private lateinit var keyStore: HardwareKeyStore

  private fun getKeyStore(): HardwareKeyStore {
    if (hardwareAttestationKeySupported()) {
      return HardwareKeyStore()
    } else {
      throw UnsupportedOperationException()
    }
  }

  override fun hardwareAttestationKeyCreate(): String {
    return getKeyStore().createKey()
  }

  @Throws(NoSuchKeyException::class)
  override fun hardwareAttestationKeyRelease(id: String) {
    return getKeyStore().releaseKey(id)
  }

  @Throws(NoSuchKeyException::class)
  override fun hardwareAttestationKeySign(id: String, data: ByteArray): ByteArray {
    return getKeyStore().sign(id, data)
  }

  @Throws(NoSuchKeyException::class)
  override fun hardwareAttestationKeyPublic(id: String): ByteArray {
    return getKeyStore().public(id)
  }

  @Throws(NoSuchKeyException::class)
  override fun hardwareAttestationKeyLoad(id: String) {
    return getKeyStore().load(id)
  }

  override fun bindSocketToNetwork(fd: Int): Boolean {
    if (!TailwareActivationCapability.hasEngineCapability()) {
      TSLog.w(TAG, "Refusing socket binding without a current-process engine capability")
      return false
    }
    val net =
        NetworkChangeCallback.cachedDefaultNetwork
            ?: run {
              TSLog.d(TAG, "bindSocketToActiveNetwork: no cached default network; noop")
              return false
            }

    val iface = NetworkChangeCallback.cachedDefaultInterfaceName

    TSLog.d(
        TAG,
        "bindSocketToActiveNetwork: binding fd=$fd to net=$net iface=$iface",
    )

    return try {
      android.os.ParcelFileDescriptor.fromFd(fd).use { pfd -> net.bindSocket(pfd.fileDescriptor) }
      true
    } catch (e: Exception) {
      TSLog.w(
          TAG,
          "bindSocketToActiveNetwork: bind failed fd=$fd net=$net iface=$iface: $e",
      )
      false
    }
  }

  override fun getUserCACertsPEM(): ByteArray {
    val ks = java.security.KeyStore.getInstance("AndroidCAStore")
    ks.load(null)
    val sb = StringBuilder()
    val encoder = android.util.Base64.NO_WRAP
    for (alias in ks.aliases()) {
      if (!alias.startsWith("user:")) continue
      val cert = ks.getCertificate(alias) ?: continue
      val pem = android.util.Base64.encodeToString(cert.encoded, encoder)
      sb.append("-----BEGIN CERTIFICATE-----\n")
      pem.chunked(64).forEach { sb.append(it).append('\n') }
      sb.append("-----END CERTIFICATE-----\n")
    }
    return sb.toString().toByteArray(Charsets.UTF_8)
  }
}

/**
 * UninitializedApp contains all of the methods of App that can be used without having to initialize
 * the Go backend. This is useful when you want to access functions on the App without creating side
 * effects from starting the Go backend (such as launching the VPN).
 */
open class UninitializedApp : Application() {
  companion object {
    const val TAG = "UninitializedApp"
    private val loginForegroundPromotionCoordinator = LoginForegroundPromotionCoordinator()
    private val pendingLoginForegroundReleases = ConcurrentHashMap.newKeySet<Long>()
    private val vpnServiceCleanupGeneration = AtomicLong()
    private val vpnCommandRequestCoordinator = VpnCommandRequestCoordinator()
    private val vpnCommandDispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    const val STATUS_NOTIFICATION_ID = 1
    const val STATUS_EXIT_NODE_FAILURE_NOTIFICATION_ID = 2
    const val STATUS_OPTIONAL_NOTIFICATION_ID = 3
    const val STATUS_CHANNEL_ID = "tailscale-status"
    const val FILE_CHANNEL_ID = "tailscale-files"
    private const val TAILWARE_PREFERENCES_FILE = "tailware_wear_preferences"
    private const val KEY_EXIT_NODE_FAILURE_REASON = "exit_node_failure_reason"
    // Key for shared preference that tracks whether or not we're able to start
    // the VPN (i.e. we're logged in and machine is authorized).
    private const val ABLE_TO_START_VPN_KEY = "ableToStartVPN"

    // The value is 'disallowedApps' as it used to represent
    // only disallowed applications. This has been changed
    // and allowing/disallowing is based on ALLOW_SELECTED_APPS_KEY
    //
    // The value is kept the same to not reset everyone's configuration
    private const val SELECTED_APPS_KEY = "disallowedApps"
    private const val ALLOW_SELECTED_APPS_KEY = "allowSelectedApps"

    private const val IS_CLIENT_LOGGING_ENABLED_KEY = "isClientLoggingEnabled"
    // File for shared preferences that are not encrypted.
    private const val UNENCRYPTED_PREFERENCES = "unencrypted"
    private lateinit var appInstance: UninitializedApp
    lateinit var notificationManager: NotificationManagerCompat

    lateinit var appViewModel: AppViewModel

    @JvmStatic
    fun get(): UninitializedApp {
      return appInstance
    }
    /**
     * Return the name of the active (but not the selected/prior one) exit node based on the
     * provided [Ipn.Prefs] and [Netmap.NetworkMap].
     *
     * @return The name of the exit node or `null` if there isn't one.
     */
    fun getExitNodeName(prefs: Ipn.Prefs?, netmap: Netmap.NetworkMap?): String? {
      return prefs?.activeExitNodeID?.let { exitNodeID ->
        // The active preference is authoritative. Peer metadata can briefly disappear during a
        // netmap refresh or while an exit node is unreachable; falling back to its stable ID keeps
        // the active choice visible instead of incorrectly presenting "Exit: Off".
        netmap?.Peers?.find { it.StableID == exitNodeID }?.exitNodeName ?: exitNodeID
      }
    }
  }

  protected fun setUnprotectedInstance(instance: UninitializedApp) {
    appInstance = instance
  }

  protected fun setAbleToStartVPN(rdy: Boolean) {
    getUnencryptedPrefs().edit().putBoolean(ABLE_TO_START_VPN_KEY, rdy).apply()
  }
  /** This function can be called without initializing the App. */
  fun isAbleToStartVPN(): Boolean {
    return getUnencryptedPrefs().getBoolean(ABLE_TO_START_VPN_KEY, false)
  }

  private fun getUnencryptedPrefs(): SharedPreferences {
    return getSharedPreferences(UNENCRYPTED_PREFERENCES, MODE_PRIVATE)
  }

  /**
   * Starts IPNService as a foreground service without creating a VPN tunnel. This prevents Android
   * from freezing the process and restricting network access during interactive login while the
   * user completes auth in the browser.
   */
  internal suspend fun startForegroundForLogin(
      loginCapability: Long
  ): Result<LoginForegroundLease> {
    if (!TailwareActivationCapability.hasEngineCapability() ||
        !TailwareActivationCapability.acceptsLoginCapability(loginCapability)) {
      return Result.failure(
          SecurityException(
              "Foreground login requires a fresh interactive-login capability in this process."))
    }
    retryPendingLoginForegroundReleases()
    if (vpnServiceCleanupGeneration.get() != 0L) {
      val retry = stopVPNCommand()
      if (!retry.stopSafelyHandled || vpnServiceCleanupGeneration.get() != 0L) {
        return Result.failure(
            IllegalStateException(
                "The previous VPN shutdown is still completing. Try sign-in again."))
      }
    }
    val request =
        try {
          loginForegroundPromotionCoordinator.beginWithDispatch { requestId ->
            dispatchLoginForegroundStart(requestId, loginCapability)
          }
        } catch (e: Exception) {
          TSLog.e(TAG, "startForegroundForLogin hit exception: $e")
          return Result.failure(e)
        }
    return try {
      val promotion =
          withTimeoutOrNull(LOGIN_FOREGROUND_PROMOTION_TIMEOUT_MILLIS) {
            request.completion.await()
          }
      // Deferred.await() has prompt cancellation semantics. Recheck before transferring the lease
      // so cancellation after the service acknowledgement cannot strand an ownerless foreground
      // service.
      currentCoroutineContext().ensureActive()
      if (promotion != null) {
        promotion.map { LoginForegroundLease(request.id) }
      } else {
        loginForegroundPromotionCoordinator.abandon(request.id)
        // The caller cannot start a login mutation before promotion succeeds, so this provisional
        // owner can close directly when the persisted state is OFF.
        releaseForegroundForLogin(LoginForegroundLease(request.id), backendAlreadyStopped = true)
        Result.failure(
            IllegalStateException("Android did not allow Tailware to start the VPN service."))
      }
    } catch (error: CancellationException) {
      loginForegroundPromotionCoordinator.abandon(request.id)
      releaseForegroundForLogin(LoginForegroundLease(request.id), backendAlreadyStopped = true)
      throw error
    }
  }

  private fun dispatchLoginForegroundStart(
      requestId: Long,
      loginCapability: Long,
  ) {
    check(TailwareActivationCapability.bindLoginRequest(requestId, loginCapability)) {
      "The interactive-login capability expired before service dispatch."
    }
    try {
      startForegroundService(
          Intent(this, IPNService::class.java).apply {
            action = IPNService.ACTION_START_FOREGROUND_ONLY
            putExtra(IPNService.EXTRA_LOGIN_FOREGROUND_REQUEST_ID, requestId)
          })
    } catch (error: Exception) {
      TailwareActivationCapability.releaseLoginRequest(requestId)
      throw error
    }
  }

  internal fun completeLoginForegroundPromotion(requestId: Long, result: Result<Unit>): Boolean =
      loginForegroundPromotionCoordinator.complete(requestId, result)

  internal fun hasLoginForegroundOwner(): Boolean = loginForegroundPromotionCoordinator.hasOwner()

  internal fun markVpnServiceCleanupComplete(cleanupGeneration: Long) {
    clearVpnServiceCleanupThrough(cleanupGeneration)
  }

  internal fun pendingVpnServiceCleanupGeneration(): Long = vpnServiceCleanupGeneration.get()

  private fun clearVpnServiceCleanupThrough(cleanupGeneration: Long) {
    vpnServiceCleanupGeneration.updateAndGet { pendingGeneration ->
      vpnCleanupGenerationAfterOutcome(
          currentGeneration = pendingGeneration,
          outcomeGeneration = cleanupGeneration,
          handled = true,
      )
    }
  }

  private fun markVpnServiceCleanupPending(cleanupGeneration: Long) {
    vpnServiceCleanupGeneration.updateAndGet { pendingGeneration ->
      vpnCleanupGenerationAfterOutcome(
          currentGeneration = pendingGeneration,
          outcomeGeneration = cleanupGeneration,
          handled = false,
      )
    }
  }

  internal fun releaseForegroundForLogin(
      lease: LoginForegroundLease,
      backendAlreadyStopped: Boolean = false,
      authenticationEpoch: Long? = null,
      backendStopAwaitedThroughGeneration: Long = 0L,
  ): Boolean {
    return loginForegroundPromotionCoordinator.runLifecycle {
      // Publish logical release before dispatch. IPNService consults this coordinator as the
      // process-wide source of truth, so an earlier failed release intent cannot strand a stale
      // service-side request after the final real owner exits.
      loginForegroundPromotionCoordinator.release(lease.requestId)
      pendingLoginForegroundReleases.remove(lease.requestId)
      val vpnCleanupGeneration = vpnServiceCleanupGeneration.get()
      val intent =
          Intent(this, IPNService::class.java).apply {
            action = IPNService.ACTION_RELEASE_FOREGROUND_ONLY
            putExtra(IPNService.EXTRA_LOGIN_FOREGROUND_REQUEST_ID, lease.requestId)
            putExtra(IPNService.EXTRA_LOGIN_BACKEND_ALREADY_STOPPED, backendAlreadyStopped)
            putExtra(
                IPNService.EXTRA_LOGIN_BACKEND_STOP_AWAITED_THROUGH_GENERATION,
                backendStopAwaitedThroughGeneration,
            )
            putExtra(IPNService.EXTRA_VPN_CLEANUP_GENERATION, vpnCleanupGeneration)
            authenticationEpoch?.let { putExtra(IPNService.EXTRA_AUTHENTICATION_EPOCH, it) }
          }
      try {
        startService(intent)
        true
      } catch (error: Exception) {
        TSLog.e(TAG, "Unable to dispatch foreground-only login release: $error")
        // Another logical login owner or a desired VPN keeps the shared service alive. Its later
        // release/stop will discard any stale service-side ID using the coordinator truth above.
        if (loginForegroundPromotionCoordinator.hasOwner() ||
            TailwareVpnStateStore.desiredVpnState(this).enabled) {
          return@runLifecycle true
        }
        // Never stopService while native cleanup could still own a descriptor: that would remove
        // foreground protection before exact acknowledgement. A proven login-only service has no
        // VPN cleanup obligation and may use the legacy lifecycle fallback.
        if (isVpnActive() || vpnCleanupGeneration != 0L) {
          pendingLoginForegroundReleases.add(lease.requestId)
          return@runLifecycle false
        }
        runCatching { stopIpnService() }
            .fold(
                onSuccess = {
                  clearVpnServiceCleanupThrough(vpnCleanupGeneration)
                  true
                },
                onFailure = { stopError ->
                  TSLog.e(
                      TAG,
                      "Unable to stop orphaned foreground-only login service: $stopError",
                  )
                  pendingLoginForegroundReleases.add(lease.requestId)
                  false
                },
            )
      }
    }
  }

  private fun retryPendingLoginForegroundReleases() {
    pendingLoginForegroundReleases.toList().forEach { requestId ->
      releaseForegroundForLogin(
          LoginForegroundLease(requestId),
          backendAlreadyStopped = true,
      )
    }
  }

  internal fun transferForegroundLoginToVpn(lease: LoginForegroundLease) {
    // The VPN command now supplies foreground ownership, but IPNService still needs the exact
    // login ID removed from its multi-owner set. Desired ON makes this release non-terminal.
    releaseForegroundForLogin(lease)
  }

  fun startVPN(): Boolean =
      startVPNCommand(TailwareActivationCapability.currentVpnCapability()).dispatched

  fun startVPNCommand(): VpnCommandDispatchResult =
      startVPNCommand(TailwareActivationCapability.currentVpnCapability())

  internal fun startVPNCommand(vpnCapability: Long): VpnCommandDispatchResult =
      vpnCommandRequestCoordinator.reserve().let { requestSequence ->
        loginForegroundPromotionCoordinator.runLifecycle {
          vpnCommandRequestCoordinator.runIfLatest(
              sequence = requestSequence,
              superseded = {
                VpnCommandDispatchResult(
                    command = TailwareVpnStateStore.desiredVpnState(this),
                    dispatched = false,
                    superseded = true,
                )
              },
              operation = { startVPNCommandLocked(vpnCapability) },
          )
        }
      }

  private fun startVPNCommandLocked(vpnCapability: Long): VpnCommandDispatchResult {
    if (!TailwareActivationCapability.acceptsVpnCapability(vpnCapability)) {
      return VpnCommandDispatchResult(
          command = TailwareVpnStateStore.desiredVpnState(this),
          dispatched = false,
      )
    }
    // Persist the user's intent before dispatching the service command. This closes the small
    // window where an immediate reboot could otherwise restore the previous OFF state.
    val command =
        try {
          TailwareActivationCapability.withRegistryLock {
            if (!TailwareActivationCapability.acceptsVpnCapability(vpnCapability)) {
              return@withRegistryLock null
            }
            TailwareVpnStateStore.requestVpnEnabled(this, true).also { candidate ->
              check(
                  TailwareActivationCapability.bindVpnCommand(
                      candidate.generation,
                      vpnCapability,
                  ))
            }
          }
        } catch (error: VpnStatePersistenceException) {
          val localOff = containVpnStatePersistenceFailure(error)
          // Never dispatch the attempted ON generation after its commit failed. The store has
          // already superseded it with this local OFF owner.
          return VpnCommandDispatchResult(
              command = localOff,
              dispatched = false,
              failedStartRollback = localOff,
          )
        }
            ?: return VpnCommandDispatchResult(
                command = TailwareVpnStateStore.desiredVpnState(this),
                dispatched = false,
            )
    val dispatched = dispatchVpnStart(command, vpnCapability)
    val failedStartRollback =
        if (!dispatched) {
          // Tile and notification callers use startVPN() directly and do not have a ViewModel that
          // can
          // compensate for a rejected foreground-service dispatch. Claim OFF here while this exact
          // ON
          // generation still owns the state. Existing UI rollback calls remain safe no-ops.
          rollbackAndDispatchFailedVpnStart(command)
        } else {
          null
        }
    return VpnCommandDispatchResult(command, dispatched, failedStartRollback)
  }

  /**
   * Restores an already persisted ON state as a new technical command generation.
   *
   * The service validates [command]'s generation again when it receives the intent, so a newer OFF
   * always wins even if it arrives immediately after this check.
   */
  fun restoreVPN(command: DesiredVpnState): Boolean {
    val vpnCapability = TailwareActivationCapability.currentVpnCapability()
    if (!TailwareActivationCapability.acceptsVpnCapability(vpnCapability)) return false
    return loginForegroundPromotionCoordinator.runLifecycle {
      restoreVpnLocked(command, vpnCapability)
    }
  }

  private fun restoreVpnLocked(command: DesiredVpnState, vpnCapability: Long): Boolean {
    if (!TailwareActivationCapability.acceptsVpnCapability(vpnCapability)) return false
    val claimed =
        try {
          TailwareActivationCapability.withRegistryLock {
            if (!TailwareActivationCapability.acceptsVpnCapability(vpnCapability)) {
              return@withRegistryLock null
            }
            TailwareVpnStateStore.claimEnabledCommandIfCurrent(this, command)?.also { candidate ->
              check(
                  TailwareActivationCapability.bindVpnCommand(
                      candidate.generation,
                      vpnCapability,
                  ))
            }
          }
        } catch (error: VpnStatePersistenceException) {
          containVpnStatePersistenceFailure(error)
          return false
        } ?: return true
    if (dispatchVpnStart(claimed, vpnCapability)) return true
    rollbackAndDispatchFailedVpnStart(claimed)
    return false
  }

  /**
   * Rolls a rejected ON command forward to OFF and delivers that exact successor generation.
   *
   * An older disconnect barrier already accepted by Go remains authoritative until its exact native
   * acknowledgement. Dispatching the rollback OFF supplies the successor generation when a rejected
   * foreground-service start advances the store, without allowing either generation to inherit the
   * other's TUN ownership.
   */
  private fun rollbackAndDispatchFailedVpnStart(failedCommand: DesiredVpnState): DesiredVpnState? {
    return loginForegroundPromotionCoordinator.runLifecycle {
      // Capture causal evidence before rolling desired ON to OFF. After rollback, desired state and
      // an asynchronous VPN-status publication may both say OFF even though the old service still
      // exists and must be stopped if the OFF intent is rejected.
      val stopEvidence = captureVpnServiceStopEvidence()
      val rollback =
          try {
            TailwareVpnStateStore.rollbackFailedVpnStartIfCurrent(this, failedCommand)
          } catch (error: VpnStatePersistenceException) {
            containVpnStatePersistenceFailure(error)
          } ?: return@runLifecycle null
      TailwareActivationCapability.revokeVpnCommand(failedCommand.generation)
      val stopDispatch = dispatchPersistedVpnStop(rollback)
      if (!stopDispatch.dispatched) {
        forceStopVpnServiceAfterFailedStop(rollback, stopEvidence)
      }
      rollback
    }
  }

  private fun dispatchVpnStart(command: DesiredVpnState, vpnCapability: Long): Boolean {
    if (!TailwareActivationCapability.acceptsVpnCapability(vpnCapability)) return false
    if (!TailwareActivationCapability.bindVpnCommand(command.generation, vpnCapability)) {
      return false
    }
    val intent =
        Intent(this, IPNService::class.java).apply {
          action = IPNService.ACTION_START_VPN
          putExtra(IPNService.EXTRA_COMMAND_GENERATION, command.generation)
        }
    return try {
      startForegroundService(intent)
      true
    } catch (foregroundServiceStartException: IllegalStateException) {
      TSLog.e(
          TAG,
          "startVPN hit ForegroundServiceStartNotAllowedException: $foregroundServiceStartException")
      false
    } catch (securityException: SecurityException) {
      TSLog.e(TAG, "startVPN hit SecurityException: $securityException")
      false
    } catch (e: Exception) {
      TSLog.e(TAG, "startVPN hit exception: $e")
      false
    }
  }

  fun stopVPN(): Boolean = stopVPNCommand().stopSafelyHandled

  fun forceStopVpnServiceAfterFailedStop(
      command: DesiredVpnState,
      evidence: VpnServiceStopEvidence,
  ): Boolean {
    val handled = forceStopVpnServiceAfterFailedStopInternal(command, evidence)
    // Persist causal VPN evidence across retries after OFF has replaced the prior ON generation.
    // Clear it only after an accepted STOP/fallback or proof that a newer owner superseded cleanup.
    if (handled) clearVpnServiceCleanupThrough(command.generation)
    else markVpnServiceCleanupPending(command.generation)
    return handled
  }

  private fun forceStopVpnServiceAfterFailedStopInternal(
      command: DesiredVpnState,
      evidence: VpnServiceStopEvidence,
  ): Boolean {
    if (!evidence.serviceMayExist) return true
    failedStopFallbackWithLoginOwner(
            vpnWasDesired = evidence.vpnWasDesired,
            tunnelWasActive = evidence.tunnelWasActive,
            vpnCleanupWasPending = evidence.vpnCleanupGeneration != 0L,
            hasLoginForegroundOwner =
                evidence.loginForegroundOwnerWasPresent ||
                    loginForegroundPromotionCoordinator.hasOwner(),
        )
        ?.let { ownerSafeResult ->
          // The enclosing lifecycle gate makes this the same owner captured before OFF persistence.
          // A login-only service has no tunnel to force-stop. If a real TUN exists, surface
          // fallback
          // failure rather than kill the login's only foreground protection and leave a phantom
          // lease.
          return ownerSafeResult
        }
    return TailwareVpnStateStore.withLockedDesiredVpnState(this) { current ->
      // A newer command has already superseded this notification OFF. Treat that as safely handled:
      // stopping the shared service here would tear down the newer owner.
      if (!shouldForceStopVpnServiceAfterFailedDispatch(true, command, current)) {
        return@withLockedDesiredVpnState true
      }
      try {
        // Keep the desired-state/login ownership decision atomic with scheduling the fallback on
        // the already-running service. The service itself retains foreground Stopping and performs
        // exact-generation native teardown; stopService is never used for a possible TUN owner.
        var scheduled = false
        val ran =
            loginForegroundPromotionCoordinator.runUnlessNewerOwner(
                requestFence = evidence.loginForegroundRequestFence,
                preserveNewerOwner =
                    !(evidence.vpnWasDesired ||
                        evidence.tunnelWasActive ||
                        evidence.vpnCleanupGeneration != 0L),
            ) {
              scheduled = IPNService.requestFailClosedStop(command.generation)
            }
        !ran || scheduled
      } catch (error: Exception) {
        TSLog.e(TAG, "Unable to schedule fail-closed IPNService teardown: $error")
        false
      }
    }
  }

  fun isVpnActive(): Boolean = runCatching { appViewModel.vpnActive.value }.getOrDefault(false)

  // ImplicitSamInstance mistakes the explicit component class literal for a removable SAM
  // listener. stopService intentionally uses a fresh explicit Intent, as required by Android.
  @SuppressLint("ImplicitSamInstance")
  private fun stopIpnService(): Boolean = stopService(Intent(this, IPNService::class.java))

  fun mayHaveRunningVpnService(): Boolean =
      TailwareVpnStateStore.desiredVpnEnabled(this) ||
          isVpnActive() ||
          loginForegroundPromotionCoordinator.hasOwner() ||
          pendingLoginForegroundReleases.isNotEmpty() ||
          vpnServiceCleanupGeneration.get() != 0L

  fun captureVpnServiceStopEvidence(
      additionalServiceEvidence: Boolean = false
  ): VpnServiceStopEvidence {
    // Capture the login fence first. A request that begins afterward is strictly newer and is
    // serialized by runUnlessNewerOwner rather than being mistaken for pre-stop evidence.
    val loginSnapshot = loginForegroundPromotionCoordinator.ownerSnapshot()
    val vpnWasDesired = TailwareVpnStateStore.desiredVpnEnabled(this)
    val tunnelWasActive = isVpnActive()
    val vpnCleanupGeneration = vpnServiceCleanupGeneration.get()
    return VpnServiceStopEvidence(
        serviceMayExist =
            additionalServiceEvidence ||
                loginSnapshot.hasOwner ||
                pendingLoginForegroundReleases.isNotEmpty() ||
                vpnWasDesired ||
                tunnelWasActive ||
                vpnCleanupGeneration != 0L,
        vpnWasDesired = vpnWasDesired,
        tunnelWasActive = tunnelWasActive,
        vpnCleanupGeneration = vpnCleanupGeneration,
        loginForegroundOwnerWasPresent = loginSnapshot.hasOwner,
        loginForegroundRequestFence = loginSnapshot.requestFence,
    )
  }

  internal fun containVpnStatePersistenceFailure(
      error: VpnStatePersistenceException
  ): DesiredVpnState {
    TSLog.e(
        TAG,
        "Desired VPN state persistence failed; localOff=${error.localOff} " +
            "cleanupPersisted=${error.cleanupPersisted}: $error",
    )
    runCatching {
          appViewModel.publishVpnServiceError(
              TailwareStrings.selectedText(this, R.string.wear_unexpected_error_try_again))
        }
        .onFailure { publishError ->
          TSLog.e(TAG, "Unable to publish desired-state persistence failure: $publishError")
        }
    return error.localOff
  }

  /**
   * Persists an exact OFF generation without stopping the shared foreground service yet.
   *
   * Interactive-login cancellation uses this before waiting for callback quiescence and backend
   * cleanup. The foreground lease therefore remains alive until the sensitive cleanup has
   * completed, while an immediate reboot still observes OFF.
   */
  internal fun persistVpnStopCommand(): DesiredVpnState =
      try {
        TailwareVpnStateStore.requestVpnEnabled(this, false)
      } catch (error: VpnStatePersistenceException) {
        // OFF is already this process's local state even if both disk attempts failed. Continue
        // causal service/backend cleanup while surfacing that reboot durability is uncertain.
        containVpnStatePersistenceFailure(error)
      }

  /**
   * Dispatches a previously persisted OFF generation.
   *
   * A newer ON/OFF writer wins without being touched. IPNService validates the same generation
   * again on receipt, closing the remaining dispatch race.
   */
  internal fun dispatchPersistedVpnStop(
      command: DesiredVpnState,
      authenticationEpoch: Long? = null,
  ): VpnCommandDispatchResult {
    if (!com.tailscale.ipn.wear.shouldDispatchPersistedVpnCommand(
        command,
        TailwareVpnStateStore.desiredVpnState(this),
    )) {
      clearVpnServiceCleanupThrough(command.generation)
      return VpnCommandDispatchResult(command = command, dispatched = true)
    }
    val intent =
        Intent(this, IPNService::class.java).apply {
          action = IPNService.ACTION_STOP_VPN
          putExtra(IPNService.EXTRA_COMMAND_GENERATION, command.generation)
          authenticationEpoch?.let { putExtra(IPNService.EXTRA_AUTHENTICATION_EPOCH, it) }
        }
    // startService only acknowledges dispatch. IPNService clears this generation after the exact
    // backend mutation completes; until then, a fresh login promotion fails closed.
    markVpnServiceCleanupPending(command.generation)
    val dispatched =
        try {
          startService(intent)
          true
        } catch (illegalStateException: IllegalStateException) {
          TSLog.e(
              TAG, "stopVPN hit IllegalStateException in startService(): $illegalStateException")
          false
        } catch (e: Exception) {
          TSLog.e(TAG, "stopVPN hit exception in startService(): $e")
          false
        }
    return VpnCommandDispatchResult(command, dispatched)
  }

  fun stopVPNCommand(): VpnCommandDispatchResult {
    return stopVPNCommand(additionalServiceEvidence = false, authenticationEpoch = null)
  }

  /**
   * Reserves the ordering position for cleanup that will run after asynchronous login retirement.
   *
   * A later explicit VPN command receives a newer sequence immediately on its caller thread. The
   * eventual login cleanup can therefore prove it is stale atomically with the OFF mutation,
   * instead of relying on a check that a Connect can overtake before persistence.
   */
  internal fun reserveDelayedVpnStopRequest(): Long = vpnCommandRequestCoordinator.reserve()

  internal fun stopVPNCommandForReservedRequest(
      requestSequence: Long,
      additionalServiceEvidence: Boolean,
      authenticationEpoch: Long? = null,
  ): VpnCommandDispatchResult =
      stopVPNCommandForRequest(
          requestSequence = requestSequence,
          additionalServiceEvidence = additionalServiceEvidence,
          authenticationEpoch = authenticationEpoch,
      )

  /**
   * Reserves Disconnect ordering on the caller thread and performs storage/service work on IO.
   *
   * A later Connect reserves a newer sequence synchronously. If it wins before this worker enters
   * the lifecycle gate, this request completes as [VpnCommandDispatchResult.superseded] without
   * writing OFF. The callback also runs on the IO dispatcher.
   */
  @JvmOverloads
  fun stopVPNCommandAsync(callback: VpnCommandDispatchCallback? = null) {
    val requestSequence = vpnCommandRequestCoordinator.reserve()
    vpnCommandDispatchScope.launch {
      val result =
          runCatching {
                stopVPNCommandForRequest(
                    requestSequence = requestSequence,
                    additionalServiceEvidence = false,
                    authenticationEpoch = null,
                )
              }
              .getOrElse { error ->
                TSLog.e(TAG, "Asynchronous VPN stop failed before dispatch: $error")
                VpnCommandDispatchResult(
                    command = TailwareVpnStateStore.desiredVpnState(this@UninitializedApp),
                    dispatched = false,
                    fallbackStopSucceeded = false,
                )
              }
      callback?.onComplete(result)
    }
  }

  fun stopVPNCommand(
      additionalServiceEvidence: Boolean,
      authenticationEpoch: Long? = null,
  ): VpnCommandDispatchResult =
      stopVPNCommandForRequest(
          requestSequence = vpnCommandRequestCoordinator.reserve(),
          additionalServiceEvidence = additionalServiceEvidence,
          authenticationEpoch = authenticationEpoch,
      )

  private fun stopVPNCommandForRequest(
      requestSequence: Long,
      additionalServiceEvidence: Boolean,
      authenticationEpoch: Long?,
  ): VpnCommandDispatchResult {
    return loginForegroundPromotionCoordinator.runLifecycle {
      vpnCommandRequestCoordinator.runIfLatest(
          sequence = requestSequence,
          superseded = {
            VpnCommandDispatchResult(
                command = TailwareVpnStateStore.desiredVpnState(this),
                dispatched = false,
                superseded = true,
            )
          },
      ) {
        if (!TailwareActivationCapability.hasEngineCapability()) {
          // Cold notification/receiver/legacy callers may neutralize stored intent, but they must
          // not create a service merely to stop a tunnel that this process cannot own. Keep this
          // persistence inside the request-order gate so a later Connect can supersede a queued
          // asynchronous Disconnect before it writes OFF.
          val safeState =
              try {
                TailwareVpnStateStore.ensureP0Containment(this, forcePassiveCheck = true)
                val current = TailwareVpnStateStore.desiredVpnState(this)
                if (current.enabled) TailwareVpnStateStore.setDesiredVpnEnabled(this, false)
                else current
              } catch (error: VpnStatePersistenceException) {
                error.localOff
              }
          return@runIfLatest VpnCommandDispatchResult(
              command = safeState,
              dispatched = false,
              fallbackStopSucceeded = true,
          )
        }
        retryPendingLoginForegroundReleases()
        // Keep login service creation outside the entire capture -> OFF persist -> OFF dispatch ->
        // fallback interval. A login that loses this gate starts only after cleanup and therefore
        // dispatches a fresh foreground-service start.
        val evidence = captureVpnServiceStopEvidence(additionalServiceEvidence)
        val stoppedCapability = TailwareActivationCapability.currentVpnCapability()
        val command = persistVpnStopCommand()
        TailwareActivationCapability.revokeVpnCapability(stoppedCapability)
        val dispatch = dispatchPersistedVpnStop(command, authenticationEpoch)
        if (dispatch.dispatched) {
          // The accepted OFF command normalizes all stale service-side login IDs. No orphan release
          // remains to retry after that authoritative cleanup.
          pendingLoginForegroundReleases.clear()
          dispatch
        } else {
          val fallbackStopSucceeded = forceStopVpnServiceAfterFailedStop(command, evidence)
          if (fallbackStopSucceeded) {
            // A successful stopService fallback (or proof that no separate service cleanup is
            // needed) is equally authoritative for every release pending before this lifecycle
            // gate.
            pendingLoginForegroundReleases.clear()
          }
          dispatch.copy(fallbackStopSucceeded = fallbackStopSucceeded)
        }
      }
    }
  }

  fun restartVPN() {
    loginForegroundPromotionCoordinator.runLifecycle { restartVpnLocked() }
  }

  private fun restartVpnLocked() {
    val vpnCapability = TailwareActivationCapability.currentVpnCapability()
    if (!TailwareActivationCapability.acceptsVpnCapability(vpnCapability)) return
    // A technical restart must never turn an explicitly disabled tunnel back on.
    val expected = TailwareVpnStateStore.desiredVpnState(this)
    if (!com.tailscale.ipn.wear.shouldDispatchVpnRestart(expected.enabled)) {
      return
    }
    // Capture causal evidence while desired state is still ON. A failed commit below forces the
    // in-memory successor to OFF, which can otherwise hide a currently running service from the
    // fallback-stop decision.
    val stopEvidence = captureVpnServiceStopEvidence()
    val command =
        try {
          TailwareActivationCapability.withRegistryLock {
            if (!TailwareActivationCapability.acceptsVpnCapability(vpnCapability)) {
              return@withRegistryLock null
            }
            TailwareVpnStateStore.claimEnabledCommandIfCurrent(this, expected)?.also { candidate ->
              check(
                  TailwareActivationCapability.bindVpnCommand(
                      candidate.generation,
                      vpnCapability,
                  ))
            }
          }
        } catch (error: VpnStatePersistenceException) {
          val localOff = containVpnStatePersistenceFailure(error)
          val stopDispatch = dispatchPersistedVpnStop(localOff)
          if (!stopDispatch.dispatched) {
            forceStopVpnServiceAfterFailedStop(localOff, stopEvidence)
          }
          return
        } ?: return
    val intent =
        Intent(this, IPNService::class.java).apply {
          action = IPNService.ACTION_RESTART_VPN
          putExtra(IPNService.EXTRA_COMMAND_GENERATION, command.generation)
        }
    if (!TailwareActivationCapability.bindVpnCommand(command.generation, vpnCapability)) return
    try {
      startForegroundService(intent)
    } catch (illegalStateException: IllegalStateException) {
      TSLog.e(
          TAG,
          "restartVPN hit IllegalStateException in startForegroundService(): $illegalStateException",
      )
      rollbackAndDispatchFailedVpnStart(command)
    } catch (securityException: SecurityException) {
      TSLog.e(
          TAG,
          "restartVPN hit SecurityException in startForegroundService(): $securityException",
      )
      rollbackAndDispatchFailedVpnStart(command)
    } catch (e: Exception) {
      TSLog.e(TAG, "restartVPN hit exception in startForegroundService(): $e")
      rollbackAndDispatchFailedVpnStart(command)
    }
  }

  fun createNotificationChannel(id: String, name: String, description: String, importance: Int) {
    val channel = NotificationChannel(id, name, importance)
    channel.description = description
    notificationManager = NotificationManagerCompat.from(this)
    notificationManager.createNotificationChannel(channel)
  }

  fun refreshTailwareNotificationChannels() {
    createNotificationChannel(
        STATUS_CHANNEL_ID,
        TailwareStrings.selectedText(this, R.string.vpn_status),
        TailwareStrings.selectedText(
            this, R.string.optional_notifications_which_display_the_status_of_the_vpn_tunnel),
        NotificationManagerCompat.IMPORTANCE_MIN)
    createNotificationChannel(
        FILE_CHANNEL_ID,
        TailwareStrings.selectedText(this, R.string.taildrop_file_transfers),
        TailwareStrings.selectedText(
            this, R.string.notifications_delivered_when_a_file_is_received_using_taildrop),
        NotificationManagerCompat.IMPORTANCE_DEFAULT)
    createNotificationChannel(
        HealthNotifier.HEALTH_CHANNEL_ID,
        TailwareStrings.selectedText(this, R.string.health_channel_name),
        TailwareStrings.selectedText(this, R.string.health_channel_description),
        NotificationManagerCompat.IMPORTANCE_HIGH)
    createNotificationChannel(
        StartVPNWorker.NOTIFICATION_CHANNEL_ID,
        TailwareStrings.selectedText(this, R.string.vpn_start),
        TailwareStrings.selectedText(
            this,
            R.string
                .notifications_delivered_when_user_interaction_is_required_to_establish_the_vpn_tunnel),
        NotificationManagerCompat.IMPORTANCE_HIGH)
    createNotificationChannel(
        StartVPNWorker.STOP_FAILURE_NOTIFICATION_CHANNEL_ID,
        TailwareStrings.selectedText(this, R.string.wear_error),
        TailwareStrings.selectedText(
            this, R.string.wear_android_did_not_allow_tailware_to_stop_the_vpn_service),
        NotificationManagerCompat.IMPORTANCE_HIGH)
  }

  fun notifyStatus(
      vpnRunning: Boolean,
      hideDisconnectAction: Boolean,
      exitNodeName: String? = null,
      vpnStarting: Boolean = false,
      vpnStopping: Boolean = false,
  ) {
    notifyStatus(
        STATUS_OPTIONAL_NOTIFICATION_ID,
        buildStatusNotification(
            vpnRunning,
            hideDisconnectAction,
            exitNodeName,
            vpnStarting,
            vpnStopping,
        ))
  }

  internal fun notifyExitNodeFailure(reason: ExitNodeFailureReason) {
    getSharedPreferences(TAILWARE_PREFERENCES_FILE, MODE_PRIVATE)
        .edit()
        .putString(KEY_EXIT_NODE_FAILURE_REASON, reason.name)
        .apply()
    notifyStatus(
        STATUS_EXIT_NODE_FAILURE_NOTIFICATION_ID,
        buildExitNodeFailureNotification(reason),
    )
  }

  internal fun refreshExitNodeFailureNotification() {
    val storedReason =
        getSharedPreferences(TAILWARE_PREFERENCES_FILE, MODE_PRIVATE)
            .getString(KEY_EXIT_NODE_FAILURE_REASON, null)
    val reason =
        ExitNodeFailureReason.fromStored(storedReason) ?: ExitNodeFailureReason.ExitNodeUnavailable
    notifyStatus(
        STATUS_EXIT_NODE_FAILURE_NOTIFICATION_ID,
        buildExitNodeFailureNotification(reason),
    )
  }

  internal fun activeTailwareNotificationIds(): Set<Int> =
      runCatching {
            getSystemService(NotificationManager::class.java).activeNotifications.mapTo(
                mutableSetOf()) {
                  it.id
                }
          }
          .onFailure { error ->
            TSLog.e(TAG, "Unable to inspect active Tailware notifications: $error")
          }
          .getOrDefault(emptySet())

  private fun buildExitNodeFailureNotification(reason: ExitNodeFailureReason): Notification {
    val intent = Intent(this, WearMainActivity::class.java)
    val pendingIntent =
        PendingIntent.getActivity(
            this,
            STATUS_EXIT_NODE_FAILURE_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    return NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(TailwareStrings.selectedText(this, R.string.wear_exit_node_unavailable))
        .setContentText(TailwareStrings.selectedText(this, reason.messageResourceId))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .build()
  }

  private fun notifyStatus(notificationId: Int, notification: Notification) {
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED) {
      // TODO: Consider calling
      //    ActivityCompat#requestPermissions
      // here to request the missing permissions, and then overriding
      //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
      //                                          int[] grantResults)
      // to handle the case where the user grants the permission. See the documentation
      // for ActivityCompat#requestPermissions for more details.
      return
    }
    notificationManager.notify(notificationId, notification)
  }

  fun buildStatusNotification(
      vpnRunning: Boolean,
      hideDisconnectAction: Boolean,
      exitNodeName: String? = null,
      vpnStarting: Boolean = false,
      vpnStopping: Boolean = false,
  ): Notification {
    val tunnelActive = vpnRunning || vpnStarting || vpnStopping
    val language = TailwareStrings.selectedLanguage(this)
    val title =
        TailwareStrings.text(
            this,
            language,
            when {
              vpnRunning -> R.string.connected
              vpnStopping -> R.string.wear_disconnecting
              vpnStarting -> R.string.starting
              else -> R.string.not_connected
            })
    val message =
        if (vpnRunning && exitNodeName != null) {
          TailwareStrings.text(
              this,
              language,
              R.string.using_exit_node,
              TailwareStrings.isolate(language, exitNodeName),
          )
        } else null
    val icon = if (tunnelActive) R.drawable.ic_notification else R.drawable.ic_notification_disabled
    val action =
        if (tunnelActive) IPNReceiver.INTENT_DISCONNECT_VPN else IPNReceiver.INTENT_CONNECT_VPN
    val actionLabel =
        TailwareStrings.text(
            this,
            language,
            if (tunnelActive) R.string.disconnect else R.string.connect,
        )
    val buttonIntent = Intent(this, IPNReceiver::class.java).apply { this.action = action }
    val pendingButtonIntent: PendingIntent =
        PendingIntent.getBroadcast(
            this,
            0,
            buttonIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val intent = Intent(this, WearMainActivity::class.java)
    val pendingIntent: PendingIntent =
        PendingIntent.getActivity(
            this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val builder =
        NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(!tunnelActive)
            .setOnlyAlertOnce(!tunnelActive)
            .setOngoing(tunnelActive)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
    if (!tunnelActive || !hideDisconnectAction) {
      builder.addAction(
          NotificationCompat.Action.Builder(0, actionLabel, pendingButtonIntent).build())
    }
    return builder.build()
  }

  fun getIsClientLoggingEnabled(): Boolean {

    // Force client logging to be enabled, when the device is managed by MDM
    // Later this could become a dedicated MDMSetting / restriction.
    if (MDMSettings.isMDMConfigured) {
      return true
    }

    return getUnencryptedPrefs().getBoolean(IS_CLIENT_LOGGING_ENABLED_KEY, true)
  }

  fun updateIsClientLoggingEnabled(value: Boolean) {
    getUnencryptedPrefs().edit { putBoolean(IS_CLIENT_LOGGING_ENABLED_KEY, value) }
    App.get().getLibtailscaleApp().setClientLoggingEnabled(getIsClientLoggingEnabled())
  }

  fun updateUserSelectedPackages(packageNames: List<String>) {
    if (packageNames.any { it.isEmpty() }) {
      TSLog.e(TAG, "updateUserSelectedPackage called with empty packageName(s)")
      return
    }

    getUnencryptedPrefs().edit { putStringSet(SELECTED_APPS_KEY, packageNames.toSet()) }

    this.restartVPN()
  }

  fun switchUserSelectedPackages() {
    getUnencryptedPrefs().edit {
      putBoolean(ALLOW_SELECTED_APPS_KEY, !allowSelectedPackages())
      putStringSet(SELECTED_APPS_KEY, setOf())
    }

    this.restartVPN()
  }

  fun selectedPackageNames(): List<String> {
    return getUnencryptedPrefs().getStringSet(SELECTED_APPS_KEY, emptySet())?.toList()
        ?: emptyList()
  }

  fun allowSelectedPackages(): Boolean {
    return getUnencryptedPrefs().getBoolean(ALLOW_SELECTED_APPS_KEY, false)
  }

  fun getAppScopedViewModel(): AppViewModel {
    return appViewModel
  }

  val builtInDisallowedPackageNames: List<String> =
      listOf(
          // RCS/Jibe https://github.com/tailscale/tailscale/issues/2322
          "com.google.android.apps.messaging",
          // Android Auto https://github.com/tailscale/tailscale/issues/3828
          "com.google.android.projection.gearhead",
          // GoPro https://github.com/tailscale/tailscale/issues/2554
          "com.gopro.smarty",
          // Sonos https://github.com/tailscale/tailscale/issues/2548
          "com.sonos.acr",
          "com.sonos.acr2",
          // Google Chromecast https://github.com/tailscale/tailscale/issues/3636
          "com.google.android.apps.chromecast.app",
          // Voicemail https://github.com/tailscale/tailscale/issues/13199
          "com.samsung.attvvm",
          "com.att.mobile.android.vvm",
          "com.tmobile.vvm.application",
          "com.metropcs.service.vvm",
          "com.mizmowireless.vvm",
          "com.vna.service.vvm",
          "com.dish.vvm",
          "com.comcast.modesto.vvm.client",
          // Android Connectivity Service https://github.com/tailscale/tailscale/issues/14128
          "com.google.android.apps.scone",
      )
}
