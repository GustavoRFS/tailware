// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.wear.DesiredVpnState
import com.tailscale.ipn.wear.TailwareActivationCapability
import com.tailscale.ipn.wear.TailwareVpnStateStore
import com.tailscale.ipn.wear.VpnStatePersistenceException
import com.tailscale.ipn.wear.WearMainActivity
import com.tailscale.ipn.wear.isCurrentVpnCommand
import com.tailscale.ipn.wear.localization.TailwareStrings
import com.tailscale.ipn.wear.tile.TailwareTileUpdates
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import libtailscale.Libtailscale

internal fun shouldPublishStopping(ownsNativeVpn: Boolean): Boolean = ownsNativeVpn

internal const val VPN_START_WATCHDOG_MILLIS = 70_000L
internal const val NATIVE_VPN_TEARDOWN_ACK_TIMEOUT_MILLIS = 10_000L
internal const val NATIVE_VPN_TEARDOWN_FORCE_GRACE_MILLIS = 5_000L

private enum class NativeVpnTeardownContinuation {
  StopOwner,
  KeepLoginForeground,
  Restart,
}

private data class PendingNativeVpnTeardown(
    val generation: Long,
    val continuation: NativeVpnTeardownContinuation,
    val command: DesiredVpnState?,
    var stopStartId: Int,
    val removeForeground: Boolean,
)

internal fun ownsLoginForegroundRelease(
    activeRequestId: Long?,
    releasedRequestId: Long,
): Boolean = activeRequestId == releasedRequestId

internal fun loginForegroundOwnersAfterRelease(
    activeRequestIds: Set<Long>,
    releasedRequestId: Long,
): Set<Long> = activeRequestIds - releasedRequestId

internal fun shouldCloseForegroundAfterOwnedBackendStop(
    backendAlreadyStopped: Boolean,
    desiredState: DesiredVpnState,
): Boolean = backendAlreadyStopped && !desiredState.enabled

internal fun shouldSkipPendingVpnCleanupBackendMutation(
    backendAlreadyStopped: Boolean,
    backendStopAwaitedThroughGeneration: Long,
    vpnCleanupGeneration: Long,
    desiredState: DesiredVpnState,
): Boolean =
    backendAlreadyStopped &&
        vpnCleanupGeneration > 0L &&
        vpnCleanupGeneration <= backendStopAwaitedThroughGeneration &&
        !desiredState.enabled

internal fun ownsCommandCleanup(
    expectedOwner: DesiredVpnState,
    currentState: DesiredVpnState,
): Boolean = expectedOwner == currentState

internal enum class PersistedServiceStartAction {
  StopWithoutInitialization,
}

internal fun persistedServiceStartAction(
    foregroundStarted: Boolean,
): PersistedServiceStartAction = PersistedServiceStartAction.StopWithoutInitialization

internal fun shouldStartPersistedVpnService(
    desiredEnabled: Boolean,
    vpnAuthorized: Boolean,
): Boolean = false

internal fun shouldReconcileAfterOwnedStop(
    serviceStopped: Boolean,
    ownerStartId: Int,
    latestStartId: Int,
): Boolean = !serviceStopped && latestStartId > ownerStartId

internal enum class ForegroundNotificationOwner {
  None,
  LoginOnly,
  Vpn,
}

internal fun foregroundNotificationOwner(
    activeLoginRequestId: Long?,
    vpnRequested: Boolean,
    tunnelActive: Boolean,
): ForegroundNotificationOwner =
    when {
      vpnRequested || tunnelActive -> ForegroundNotificationOwner.Vpn
      activeLoginRequestId != null -> ForegroundNotificationOwner.LoginOnly
      else -> ForegroundNotificationOwner.None
    }

internal fun shouldUseStickyForegroundRestart(
    activeCommand: DesiredVpnState?,
    vpnRequested: Boolean,
    tunnelActive: Boolean,
): Boolean = false

internal enum class ServiceEntryAuthorization {
  RejectPassive,
  EngineCleanup,
  InteractiveLogin,
  ExplicitVpn,
}

/** Pure front-door policy. Persisted state and Android service recreation are never authority. */
internal fun serviceEntryAuthorization(
    action: String?,
    hasEngineCapability: Boolean,
    hasBoundLoginRequest: Boolean,
    hasBoundVpnCommand: Boolean,
): ServiceEntryAuthorization =
    when (action) {
      IPNService.ACTION_START_FOREGROUND_ONLY ->
          if (hasBoundLoginRequest) {
            ServiceEntryAuthorization.InteractiveLogin
          } else {
            ServiceEntryAuthorization.RejectPassive
          }
      IPNService.ACTION_START_VPN,
      IPNService.ACTION_RESTART_VPN ->
          if (hasBoundVpnCommand) {
            ServiceEntryAuthorization.ExplicitVpn
          } else {
            ServiceEntryAuthorization.RejectPassive
          }
      IPNService.ACTION_STOP_VPN,
      IPNService.ACTION_RELEASE_FOREGROUND_ONLY,
      IPNService.ACTION_REFRESH_NOTIFICATION ->
          if (hasEngineCapability) {
            ServiceEntryAuthorization.EngineCleanup
          } else {
            ServiceEntryAuthorization.RejectPassive
          }
      else -> ServiceEntryAuthorization.RejectPassive
    }

internal enum class RejectedServiceStartDecision {
  PreserveAuthorizedOwner,
  StopPassive,
}

/** Prevents a delayed A envelope from clearing a newer in-process B Connect generation. */
internal fun rejectedServiceStartDecision(
    currentState: DesiredVpnState,
    hasBoundCurrentVpnCommand: Boolean,
    hasAuthorizedLoginOwner: Boolean,
): RejectedServiceStartDecision =
    if ((currentState.enabled && hasBoundCurrentVpnCommand) || hasAuthorizedLoginOwner) {
      RejectedServiceStartDecision.PreserveAuthorizedOwner
    } else {
      RejectedServiceStartDecision.StopPassive
    }

internal fun vpnDisconnectGeneration(
    activeCommand: DesiredVpnState?,
    currentState: DesiredVpnState,
): Long =
    if (!currentState.enabled) {
      currentState.generation
    } else {
      activeCommand?.generation ?: currentState.generation
    }

internal fun ownsVpnStatusCallback(
    activeCommand: DesiredVpnState?,
    currentState: DesiredVpnState,
    callbackGeneration: Long,
    status: Boolean,
    closed: Boolean,
): Boolean {
  val owner = activeCommand ?: return false
  if (owner.generation != callbackGeneration || currentState != owner) return false
  return !status || (owner.enabled && !closed)
}

internal fun nativeStartupFailureOwner(
    callbackGeneration: Long,
    currentState: DesiredVpnState,
): DesiredVpnState? =
    currentState.takeIf { owner -> owner.enabled && owner.generation == callbackGeneration }

internal data class VpnStatusUpdatePlan(
    val notificationRunning: Boolean,
    val cancelStartWatchdog: Boolean,
    val scheduleStartWatchdog: Boolean,
)

internal fun vpnStatusUpdatePlan(status: Boolean): VpnStatusUpdatePlan =
    VpnStatusUpdatePlan(
        notificationRunning = status,
        cancelStartWatchdog = status,
        scheduleStartWatchdog = !status,
    )

internal enum class VpnStartWatchdogDecision {
  None,
  WaitForLogin,
  WaitForNetwork,
  StopNeedsLogin,
  StopNoTunnel,
}

internal enum class TerminalVpnFailureCleanup {
  CleanupFailedOwner,
  ReconcileNewerOwner,
  CleanupUnownedService,
}

internal fun terminalVpnFailureCleanup(
    failedCommand: DesiredVpnState?,
    rollbackCommand: DesiredVpnState?,
): TerminalVpnFailureCleanup =
    when {
      failedCommand == null -> TerminalVpnFailureCleanup.CleanupUnownedService
      rollbackCommand != null -> TerminalVpnFailureCleanup.CleanupFailedOwner
      else -> TerminalVpnFailureCleanup.ReconcileNewerOwner
    }

internal fun vpnStartWatchdogDecision(
    commandOwner: DesiredVpnState,
    currentState: DesiredVpnState,
    ipnState: Ipn.State,
    underlyingNetworkStatus: UnderlyingNetworkStatus,
    tunnelActive: Boolean,
    closed: Boolean,
    hasLiveLoginForegroundOwner: Boolean = false,
): VpnStartWatchdogDecision {
  if (closed || tunnelActive || !commandOwner.enabled || currentState != commandOwner) {
    return VpnStartWatchdogDecision.None
  }
  if (hasLiveLoginForegroundOwner || ipnState == Ipn.State.NeedsMachineAuth) {
    return VpnStartWatchdogDecision.WaitForLogin
  }
  return if (ipnState == Ipn.State.NeedsLogin) {
    VpnStartWatchdogDecision.StopNeedsLogin
  } else if (underlyingNetworkStatus != UnderlyingNetworkStatus.Online) {
    VpnStartWatchdogDecision.WaitForNetwork
  } else {
    VpnStartWatchdogDecision.StopNoTunnel
  }
}

open class IPNService : VpnService(), libtailscale.IPNService {
  private val TAG = "IPNService"
  private val randomID: String = UUID.randomUUID().toString()
  private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
  @Volatile private var initializedApp: App? = null
  @Volatile private var activeCommand: DesiredVpnState? = null
  @Volatile private var closed = false
  @Volatile private var foregroundStarted = false
  @Volatile private var tunnelActive = false
  @Volatile private var vpnRequested = false
  @Volatile private var latestStartId = 0
  private val activeLoginForegroundRequestIds = ConcurrentHashMap.newKeySet<Long>()
  private val activeLoginForegroundCapabilities = ConcurrentHashMap<Long, Long>()
  @Volatile private var activeCommandStartId = 0
  private var startWatchdog: Runnable? = null
  private val nativeVpnTeardownCoordinator = NativeVpnTeardownCoordinator()
  @Volatile private var pendingNativeVpnTeardown: PendingNativeVpnTeardown? = null
  private var nativeVpnTeardownForceTimeout: Runnable? = null
  private var nativeVpnTeardownHardTimeout: Runnable? = null

  override fun id(): String {
    return randomID
  }

  override fun commandGeneration(): Long = TailwareVpnStateStore.desiredVpnState(this).generation

  override fun isCommandAuthorized(commandGeneration: Long): Boolean {
    val command = activeCommand ?: return false
    if (command.generation != commandGeneration) return false
    return TailwareActivationCapability.withVpnCommandAuthorization(commandGeneration) {
      canMutateAuthorizedTunnel(command)
    } ?: false
  }

  /**
   * Atomically transfers ownership of a native tunnel failure to a new OFF generation.
   *
   * Go must call this before tearing down a failed TUN. If a newer user command already won, the
   * claim fails and the stale native error is not allowed to change Android state.
   */
  override fun handleVpnServiceError(commandGeneration: Long): Boolean {
    val failedCommand =
        nativeStartupFailureOwner(
            callbackGeneration = commandGeneration,
            currentState = TailwareVpnStateStore.desiredVpnState(this),
        ) ?: return false
    val ownerStartId = activeCommandStartId.takeIf { it > 0 } ?: latestStartId
    val stopCommand =
        try {
          TailwareVpnStateStore.rollbackFailedVpnStartIfCurrent(
              context = this,
              failedCommand = failedCommand,
          )
        } catch (error: VpnStatePersistenceException) {
          containVpnStatePersistenceFailure(error)
        } ?: return false
    TailwareActivationCapability.revokeVpnCommand(commandGeneration)
    // Native readiness/configuration failures happen before the normal notifier can start. Keep a
    // process-scoped, replayable user error so opening/recreating the watch Activity still explains
    // why the exact owned start rolled back to OFF. The detailed cause remains in the native log.
    initializedApp
        ?.getAppScopedViewModel()
        ?.setVpnServiceError(
            TailwareStrings.selectedText(
                this,
                R.string.wear_android_did_not_allow_tailware_to_start_the_vpn_service,
            ))
    mainHandler.post {
      if (!isCurrentCommand(stopCommand)) return@post
      activeCommand = stopCommand
      activeCommandStartId = ownerStartId
      initializedApp?.setWantRunning(
          wantRunning = false,
          commandOwner = stopCommand,
      )
      if (!ownsCommandCleanup(stopCommand, TailwareVpnStateStore.desiredVpnState(this))) {
        reconcileCurrentDesiredState(latestStartId)
        return@post
      }
      if (hasAcknowledgedLoginForegroundOwner()) {
        stopVpnWhileKeepingLoginForeground(stopCommand, ownerStartId)
        return@post
      }
      close()
      publishVpnStatus(false)
      stopOwnedService(ownerStartId)
    }
    return true
  }

  override fun updateVpnStatus(status: Boolean, commandGeneration: Long) {
    // Go can call this from a worker thread. Serialize status, notification, and lifecycle changes
    // with onStartCommand on the service's main thread.
    mainHandler.post { applyVpnStatus(status, commandGeneration) }
  }

  override fun onCreate() {
    super.onCreate()
    activeInstance = this
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.action
    val loginRequestId =
        intent
            ?.takeIf { action == ACTION_START_FOREGROUND_ONLY }
            ?.takeIf { it.hasExtra(EXTRA_LOGIN_FOREGROUND_REQUEST_ID) }
            ?.getLongExtra(EXTRA_LOGIN_FOREGROUND_REQUEST_ID, INVALID_LOGIN_FOREGROUND_REQUEST)
            ?: INVALID_LOGIN_FOREGROUND_REQUEST
    val commandGeneration =
        intent
            ?.takeIf { action == ACTION_START_VPN || action == ACTION_RESTART_VPN }
            ?.takeIf { it.hasExtra(EXTRA_COMMAND_GENERATION) }
            ?.getLongExtra(EXTRA_COMMAND_GENERATION, Long.MIN_VALUE) ?: Long.MIN_VALUE
    val consumedLoginCapability =
        loginRequestId
            .takeIf { it != INVALID_LOGIN_FOREGROUND_REQUEST }
            ?.let(TailwareActivationCapability::consumeLoginRequest)
    val entryAuthorization =
        serviceEntryAuthorization(
            action = action,
            hasEngineCapability = TailwareActivationCapability.hasEngineCapability(),
            hasBoundLoginRequest = consumedLoginCapability != null,
            hasBoundVpnCommand =
                commandGeneration != Long.MIN_VALUE &&
                    TailwareActivationCapability.acceptsVpnCommand(commandGeneration),
        )
    if (entryAuthorization == ServiceEntryAuthorization.RejectPassive) {
      return rejectPassiveServiceStart(startId, action)
    }
    val authorizedIntent = checkNotNull(intent)
    latestStartId = startId
    return when (action) {
      ACTION_REFRESH_NOTIFICATION -> {
        if (!foregroundStarted) {
          stopOwnedService(startId)
          return START_NOT_STICKY
        }
        if (!refreshOwnedForegroundNotification()) {
          stopAfterForegroundFailure(activeCommand, startId)
          return START_NOT_STICKY
        }
        START_NOT_STICKY
      }
      ACTION_STOP_VPN -> {
        val dispatchedCleanupGeneration =
            if (authorizedIntent.hasExtra(EXTRA_COMMAND_GENERATION)) {
              authorizedIntent.getLongExtra(EXTRA_COMMAND_GENERATION, 0L)
            } else {
              0L
            }
        val authenticationEpoch =
            if (authorizedIntent.hasExtra(EXTRA_AUTHENTICATION_EPOCH)) {
              authorizedIntent.getLongExtra(EXTRA_AUTHENTICATION_EPOCH, 0L)
            } else {
              null
            }
        val command =
            acceptCommand(authorizedIntent, requestedEnabled = false)
                ?: run {
                  // A newer desired-state writer safely superseded this dispatched OFF before
                  // service receipt. Retire only its generation; a genuinely newer failed cleanup
                  // remains fenced in App.
                  UninitializedApp.get().markVpnServiceCleanupComplete(dispatchedCleanupGeneration)
                  return reconcileCurrentDesiredState(startId)
                }
        cancelStartWatchdog()
        activeCommand = command
        activeCommandStartId = startId
        requestBackendStopForVpnCleanup(
            command = command,
            authenticationEpoch = authenticationEpoch,
            cleanupGeneration = command.generation,
        )
        val current = TailwareVpnStateStore.desiredVpnState(this)
        if (!ownsCommandCleanup(command, current)) {
          return reconcileCurrentDesiredState(startId)
        }
        if (hasAcknowledgedLoginForegroundOwner()) {
          return stopVpnWhileKeepingLoginForeground(command, startId)
        }
        close()
        stopOwnedService(startId)
        START_NOT_STICKY
      }
      ACTION_RESTART_VPN -> {
        val command =
            acceptCommand(authorizedIntent, requestedEnabled = true)
                ?: return reconcileCurrentDesiredState(startId)
        if (!isAuthorizedVpnCommand(command)) {
          return failClosedUnboundVpnCommand(command, startId)
        }
        cancelStartWatchdog()
        nativeVpnTeardownCoordinator.onStarted(command.generation)
        closed = false
        activeCommand = command
        activeCommandStartId = startId
        if (!isAuthorizedVpnCommand(command)) {
          return failClosedUnboundVpnCommand(command, startId)
        }
        if (!showForegroundNotification(vpnRunning = false)) {
          stopAfterForegroundFailure(command, startId)
          return START_NOT_STICKY
        }
        val app =
            try {
              if (!isAuthorizedVpnCommand(command)) {
                return failClosedUnboundVpnCommand(command, startId)
              }
              requireInitializedApp()
            } catch (error: Exception) {
              TSLog.e(TAG, "Failed to initialize the VPN engine for restart: $error")
              stopAfterStartFailure(command, startId)
              return START_NOT_STICKY
            }
        scheduleRestartWatchdog(command, startId)
        if (!isAuthorizedVpnCommand(command)) {
          return failClosedUnboundVpnCommand(command, startId)
        }
        // A restart is an implementation detail, not a user-requested disconnect. The completion
        // closure is allowed to run only while this exact command generation still owns the state.
        app.setWantRunning(
            wantRunning = false,
            reconcileDesiredState = false,
            commandOwner = command,
            onSuccess = {
              mainHandler.post {
                if (!isAuthorizedVpnCommand(command)) {
                  return@post
                }
                cancelStartWatchdog()
                val keepLoginForeground = hasAcknowledgedLoginForegroundOwner()
                val awaitingNativeAcknowledgement =
                    closeInternal(
                        clearLoginForegroundOwners = !keepLoginForeground,
                        removeForeground = !keepLoginForeground,
                        continuation = NativeVpnTeardownContinuation.Restart,
                        continuationCommand = command,
                    )
                if (awaitingNativeAcknowledgement) {
                  return@post
                }
                if (keepLoginForeground) {
                  closed = false
                  activeCommand = command
                  activeCommandStartId = startId
                  if (!refreshOwnedForegroundNotification()) {
                    close()
                    stopOwnedService(startId)
                    return@post
                  }
                } else {
                  stopOwnedService(startId)
                }
                UninitializedApp.get().restoreVPN(command)
              }
            },
            onFailure = { error ->
              mainHandler.post {
                if (activeCommand != command ||
                    TailwareVpnStateStore.desiredVpnState(this) != command) {
                  return@post
                }
                TSLog.e(TAG, "Failed to stop the backend for VPN restart: $error")
                cancelStartWatchdog()
                stopAfterStartFailure(command, startId)
              }
            })
        START_NOT_STICKY
      }
      ACTION_START_FOREGROUND_ONLY -> {
        // Interactive login needs process/network priority, but must not initialize the Go engine
        // before Android has accepted the foreground notification.
        closed = false
        val requestId =
            authorizedIntent.getLongExtra(
                EXTRA_LOGIN_FOREGROUND_REQUEST_ID,
                INVALID_LOGIN_FOREGROUND_REQUEST,
            )
        if (requestId == INVALID_LOGIN_FOREGROUND_REQUEST) {
          TSLog.e(TAG, "Foreground-only login start is missing its request ID")
          stopAfterForegroundFailure(command = null, startId = startId)
          return START_NOT_STICKY
        }
        activeLoginForegroundRequestIds.add(requestId)
        activeLoginForegroundCapabilities[requestId] = checkNotNull(consumedLoginCapability)
        // A reauthentication lease shares this service with a live VPN. Refresh through the
        // current owner so login priority cannot replace a connected notification with "Starting"
        // or hide its disconnect action.
        val promoted = refreshOwnedForegroundNotification()
        if (!promoted) {
          activeLoginForegroundRequestIds.remove(requestId)
          activeLoginForegroundCapabilities.remove(requestId)
          UninitializedApp.get()
              .completeLoginForegroundPromotion(
                  requestId,
                  Result.failure(
                      IllegalStateException(
                          "Android did not allow Tailware to start the VPN service.")),
              )
          if (UninitializedApp.get().hasLoginForegroundOwner()) {
            return if (shouldUseStickyForegroundRestart(
                activeCommand,
                vpnRequested,
                tunnelActive,
            )) {
              START_NOT_STICKY
            } else {
              START_NOT_STICKY
            }
          }
          val owner =
              activeCommand
                  ?: TailwareVpnStateStore.desiredVpnState(this).takeIf { desired ->
                    desired.enabled
                  }
          stopAfterForegroundFailure(command = owner, startId = startId)
          return START_NOT_STICKY
        }
        if (!UninitializedApp.get()
            .completeLoginForegroundPromotion(requestId, Result.success(Unit))) {
          // The caller timed out or was cancelled before Android promoted the service. Do not leave
          // an ownerless foreground-only service behind, but also do not close a VPN command that
          // already owns this shared service.
          TSLog.w(TAG, "Discarding an expired foreground-only login promotion request")
          activeLoginForegroundRequestIds.remove(requestId)
          activeLoginForegroundCapabilities.remove(requestId)
          if (UninitializedApp.get().hasLoginForegroundOwner()) {
            return if (shouldUseStickyForegroundRestart(
                activeCommand,
                vpnRequested,
                tunnelActive,
            )) {
              START_NOT_STICKY
            } else {
              START_NOT_STICKY
            }
          }
          val desiredState = TailwareVpnStateStore.desiredVpnState(this)
          return if (shouldCloseForegroundAfterOwnedBackendStop(
              backendAlreadyStopped = true,
              desiredState = desiredState,
          )) {
            // Promotion was abandoned before its caller could begin any LocalAPI login mutation.
            // Closing this provisional owner must not create an unowned destructive PATCH.
            closeAfterOwnedBackendStop(desiredState, startId)
          } else {
            reconcileCurrentDesiredState(startId)
          }
        }
        if (shouldUseStickyForegroundRestart(activeCommand, vpnRequested, tunnelActive)) {
          START_NOT_STICKY
        } else {
          START_NOT_STICKY
        }
      }
      ACTION_RELEASE_FOREGROUND_ONLY -> {
        val backendAlreadyStopped =
            authorizedIntent.getBooleanExtra(EXTRA_LOGIN_BACKEND_ALREADY_STOPPED, false)
        val backendStopAwaitedThroughGeneration =
            authorizedIntent.getLongExtra(
                EXTRA_LOGIN_BACKEND_STOP_AWAITED_THROUGH_GENERATION,
                0L,
            )
        val vpnCleanupGeneration = authorizedIntent.getLongExtra(EXTRA_VPN_CLEANUP_GENERATION, 0L)
        val authenticationEpoch =
            if (authorizedIntent.hasExtra(EXTRA_AUTHENTICATION_EPOCH)) {
              authorizedIntent.getLongExtra(EXTRA_AUTHENTICATION_EPOCH, 0L)
            } else {
              null
            }
        val requestId =
            authorizedIntent.getLongExtra(
                EXTRA_LOGIN_FOREGROUND_REQUEST_ID,
                INVALID_LOGIN_FOREGROUND_REQUEST,
            )
        if (requestId == INVALID_LOGIN_FOREGROUND_REQUEST) {
          TSLog.e(TAG, "Foreground-only login release is missing its request ID")
          return reconcileCurrentDesiredState(startId)
        }
        activeLoginForegroundRequestIds.remove(requestId)
        activeLoginForegroundCapabilities.remove(requestId)
        val app = UninitializedApp.get()
        // App publishes logical release before dispatch. Treat its coordinator as the process-wide
        // source of truth so a service-side ID left by an earlier failed release cannot outlive the
        // final real owner. A release for one owner must never close another owner's protection.
        if (app.hasLoginForegroundOwner() && vpnCleanupGeneration == 0L) {
          return if (shouldUseStickyForegroundRestart(activeCommand, vpnRequested, tunnelActive)) {
            START_NOT_STICKY
          } else {
            START_NOT_STICKY
          }
        }
        if (!app.hasLoginForegroundOwner()) {
          activeLoginForegroundRequestIds.clear()
          activeLoginForegroundCapabilities.clear()
        }
        val desiredState = TailwareVpnStateStore.desiredVpnState(this)
        if (vpnCleanupGeneration != 0L) {
          // A previous OFF dispatch failed before reaching this service. Reconcile that causal VPN
          // cleanup even if a newer login now owns foreground protection; desired OFF preserves the
          // newer login while stopping the stale VPN, and desired ON supersedes the old cleanup.
          if (shouldSkipPendingVpnCleanupBackendMutation(
              backendAlreadyStopped,
              backendStopAwaitedThroughGeneration,
              vpnCleanupGeneration,
              desiredState,
          )) {
            // Exact cancellation already awaited WantRunning=false. A replacement login may have
            // started since then, so repeat only Android-owned TUN/service cleanup and do not
            // launch
            // an unfenced PATCH that could turn the replacement backend off.
            val result = closeAfterOwnedBackendStop(desiredState, startId)
            app.markVpnServiceCleanupComplete(vpnCleanupGeneration)
            return result
          }
          if (desiredState.enabled) {
            val result = reconcileCurrentDesiredState(startId, authenticationEpoch)
            app.markVpnServiceCleanupComplete(vpnCleanupGeneration)
            return result
          }
          requestBackendStopForVpnCleanup(
              command = desiredState,
              authenticationEpoch = authenticationEpoch,
              cleanupGeneration = vpnCleanupGeneration,
          )
          if (!ownsCommandCleanup(desiredState, TailwareVpnStateStore.desiredVpnState(this))) {
            return reconcileCurrentDesiredState(startId, authenticationEpoch)
          }
          // The request callback acknowledges the token only after WantRunning=false completes or a
          // newer desired-state owner has been reasserted.
          return closeAfterOwnedBackendStop(desiredState, startId)
        }
        if (shouldCloseForegroundAfterOwnedBackendStop(
            backendAlreadyStopped = backendAlreadyStopped,
            desiredState = desiredState,
        )) {
          // Cancellation already awaited the exact-generation WantRunning=false PATCH. Avoid
          // launching a duplicate application-scoped PATCH that could outlive cleanup and race a
          // newly started login; this exact lease may close the desired-OFF service directly.
          return closeAfterOwnedBackendStop(desiredState, startId)
        }
        activeCommand
            ?.takeIf { it.enabled && !tunnelActive && !closed }
            ?.let { command ->
              scheduleStartWatchdog(command, activeCommandStartId.takeIf { it > 0 } ?: startId)
            }
        // Reconciliation closes an ownerless desired-OFF service, but preserves or starts a newer
        // desired-ON VPN command. This makes caller cancellation safe on both sides of the
        // foreground acknowledgement race.
        reconcileCurrentDesiredState(startId, authenticationEpoch)
      }
      ACTION_START_VPN -> {
        val command =
            acceptCommand(authorizedIntent, requestedEnabled = true)
                ?: return reconcileCurrentDesiredState(startId)
        startVpnForCommand(command, startId)
      }
      "android.net.VpnService" -> startFromPersistedState(startId)
      else -> {
        // Null, sticky, and unknown starts carry no process-local activation envelope.
        startFromPersistedState(startId)
      }
    }
  }

  private fun startFromPersistedState(startId: Int): Int {
    return rejectPassiveServiceStart(startId, "persisted-service-start")
  }

  /**
   * Cold, sticky, always-on, forged, and replayed starts terminate on the passive side of the
   * boundary. This function deliberately uses Android logging and SharedPreferences only.
   */
  private fun rejectPassiveServiceStart(startId: Int, action: String?): Int {
    var containmentError: Throwable? = null
    val decision =
        TailwareActivationCapability.withRegistryLock {
          val current = TailwareVpnStateStore.desiredVpnState(this)
          val currentVpnCommandIsExact =
              current.enabled && TailwareActivationCapability.acceptsVpnCommand(current.generation)
          val loginOwnerIsExact =
              activeLoginForegroundCapabilities.values.any(
                  TailwareActivationCapability::acceptsLoginCapability)
          val currentDecision =
              rejectedServiceStartDecision(
                  currentState = current,
                  hasBoundCurrentVpnCommand = currentVpnCommandIsExact,
                  hasAuthorizedLoginOwner = loginOwnerIsExact,
              )
          if (currentDecision == RejectedServiceStartDecision.StopPassive) {
            val clearedGeneration = current.generation.takeIf { current.enabled }
            runCatching {
                  TailwareVpnStateStore.ensureP0Containment(this, forcePassiveCheck = true)
                  val afterMigration = TailwareVpnStateStore.desiredVpnState(this)
                  if (afterMigration.enabled) {
                    TailwareVpnStateStore.setDesiredVpnEnabled(this, false)
                  }
                }
                .onFailure { containmentError = it }
            clearedGeneration?.let(TailwareActivationCapability::revokeVpnCommand)
          }
          currentDecision
        }
    containmentError?.let { error ->
      Log.e(TAG, "P0 containment failed while rejecting service action=$action", error)
    }

    if (decision == RejectedServiceStartDecision.PreserveAuthorizedOwner) {
      // A stale A envelope may arrive after an explicit B Connect has already persisted and bound
      // its newer generation. Reject A without clearing, closing, or stopping B. If this is a new
      // service instance with no live owner yet, B's already-dispatched exact intent will start it.
      if (activeCommand == null &&
          activeLoginForegroundRequestIds.isEmpty() &&
          !foregroundStarted &&
          !vpnRequested &&
          !tunnelActive) {
        closed = true
        if (startId > 0) stopSelfResult(startId)
      }
      return START_NOT_STICKY
    }

    // A rejected cold start has no initializedApp and no native owner, so this branch cannot touch
    // Go, netmon, TUN, protect, routes, or DNS. If a forged/system intent reaches an already-live
    // service, fail closed by tearing down only that previously authorized owner.
    if (initializedApp != null || vpnRequested || tunnelActive) {
      close()
      publishVpnStatus(false)
    } else {
      closed = true
      activeCommand = null
      activeCommandStartId = 0
    }
    if (pendingNativeVpnTeardown == null && foregroundStarted) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      foregroundStarted = false
    }
    if (startId > 0) {
      stopOwnedService(startId)
    } else if (pendingNativeVpnTeardown == null) {
      stopSelf()
    }
    return START_NOT_STICKY
  }

  private fun startVpnForCommand(command: DesiredVpnState, startId: Int): Int {
    if (!isAuthorizedVpnCommand(command)) {
      return failClosedUnboundVpnCommand(command, startId)
    }
    nativeVpnTeardownCoordinator.onStarted(command.generation)
    if (!closed && activeCommand == command && (vpnRequested || tunnelActive)) {
      activeCommandStartId = startId
      // A foreground-only login request may temporarily have replaced the notification. Reassert
      // the real tunnel state before returning from a duplicate/reconciled start.
      if (!showForegroundNotification(vpnRunning = tunnelActive)) {
        stopAfterForegroundFailure(command, startId)
        return START_NOT_STICKY
      }
      return START_NOT_STICKY
    }

    cancelStartWatchdog()
    closed = false
    activeCommand = command
    activeCommandStartId = startId
    if (!showForegroundNotification(vpnRunning = false)) {
      stopAfterForegroundFailure(command, startId)
      return START_NOT_STICKY
    }
    if (!isAuthorizedVpnCommand(command)) {
      return failClosedUnboundVpnCommand(command, startId)
    }

    val app =
        try {
          requireInitializedApp()
        } catch (error: Exception) {
          TSLog.e(TAG, "Failed to initialize the VPN engine: $error")
          stopAfterStartFailure(command, startId)
          return START_NOT_STICKY
        }
    app.setWantRunning(
        wantRunning = true,
        commandOwner = command,
        onFailure = { error ->
          mainHandler.post {
            if (activeCommand != command || !isCurrentCommand(command)) return@post
            TSLog.e(TAG, "Failed to start the Tailscale backend: $error")
            stopAfterStartFailure(command, startId)
          }
        },
    )
    if (!isAuthorizedVpnCommand(command)) {
      return failClosedUnboundVpnCommand(command, startId)
    }

    return try {
      vpnRequested = true
      if (!isAuthorizedVpnCommand(command)) {
        vpnRequested = false
        return failClosedUnboundVpnCommand(command, startId)
      }
      val requested =
          TailwareActivationCapability.withVpnCommandAuthorization(command.generation) {
            if (!isAuthorizedVpnCommand(command)) return@withVpnCommandAuthorization false
            Libtailscale.requestVPN(this, command.generation)
            true
          } == true
      if (!requested) {
        vpnRequested = false
        return failClosedUnboundVpnCommand(command, startId)
      }
      scheduleStartWatchdog(command, startId)
      START_NOT_STICKY
    } catch (error: Exception) {
      TSLog.e(TAG, "Failed to request the VPN tunnel: $error")
      vpnRequested = false
      stopAfterStartFailure(command, startId)
      START_NOT_STICKY
    }
  }

  private fun closeAfterOwnedBackendStop(command: DesiredVpnState, startId: Int): Int {
    check(!command.enabled)
    cancelStartWatchdog()
    activeCommand = command
    activeCommandStartId = startId
    if (hasAcknowledgedLoginForegroundOwner()) {
      return stopVpnWhileKeepingLoginForeground(command, startId)
    }
    close()
    stopOwnedService(startId)
    return START_NOT_STICKY
  }

  private fun requestBackendStopForVpnCleanup(
      command: DesiredVpnState,
      authenticationEpoch: Long?,
      cleanupGeneration: Long,
  ) {
    val app = initializedApp
    if (app == null) {
      // No LocalBackend was initialized in this service process, so Android-owned cleanup is the
      // complete stop obligation for this generation.
      UninitializedApp.get().markVpnServiceCleanupComplete(cleanupGeneration)
      return
    }
    val complete = { UninitializedApp.get().markVpnServiceCleanupComplete(cleanupGeneration) }
    app.setWantRunning(
        wantRunning = false,
        commandOwner = command,
        authenticationEpoch = authenticationEpoch,
        onSuccess = complete,
        onSuperseded = complete,
        onFailure = { error ->
          TSLog.e(TAG, "VPN cleanup generation $cleanupGeneration remains pending: $error")
        },
    )
  }

  private fun reconcileCurrentDesiredState(
      startId: Int,
      authenticationEpoch: Long? = null,
  ): Int {
    val current = TailwareVpnStateStore.desiredVpnState(this)
    return if (current.enabled) {
      if (isAuthorizedVpnCommand(current)) {
        startVpnForCommand(current, startId)
      } else {
        failClosedUnboundVpnCommand(current, startId)
      }
    } else {
      cancelStartWatchdog()
      activeCommand = current
      activeCommandStartId = startId
      initializedApp?.setWantRunning(
          wantRunning = false,
          commandOwner = current,
          authenticationEpoch = authenticationEpoch,
      )
      if (!ownsCommandCleanup(current, TailwareVpnStateStore.desiredVpnState(this))) {
        return reconcileCurrentDesiredState(startId, authenticationEpoch)
      }
      if (hasAcknowledgedLoginForegroundOwner()) {
        return stopVpnWhileKeepingLoginForeground(current, startId)
      }
      close()
      stopOwnedService(startId)
      START_NOT_STICKY
    }
  }

  private fun acceptCommand(
      intent: Intent,
      requestedEnabled: Boolean,
  ): DesiredVpnState? {
    if (!intent.hasExtra(EXTRA_COMMAND_GENERATION)) {
      return null
    }
    val commandGeneration = intent.getLongExtra(EXTRA_COMMAND_GENERATION, Long.MIN_VALUE)
    val current = TailwareVpnStateStore.desiredVpnState(this)
    return current.takeIf {
      isCurrentVpnCommand(
          commandGeneration = commandGeneration,
          requestedEnabled = requestedEnabled,
          currentState = it,
      ) && (!requestedEnabled || TailwareActivationCapability.acceptsVpnCommand(commandGeneration))
    }
  }

  private fun isCurrentCommand(command: DesiredVpnState): Boolean =
      TailwareVpnStateStore.desiredVpnState(this) == command

  private fun isAuthorizedVpnCommand(command: DesiredVpnState): Boolean =
      command.enabled &&
          TailwareActivationCapability.hasVpnCapability() &&
          TailwareActivationCapability.acceptsVpnCommand(command.generation) &&
          isCurrentCommand(command)

  private fun failClosedUnboundVpnCommand(
      command: DesiredVpnState,
      startId: Int,
  ): Int {
    val current = TailwareVpnStateStore.desiredVpnState(this)
    if (current != command && isAuthorizedVpnCommand(current)) {
      return startVpnForCommand(current, startId)
    }
    val safeState =
        try {
          if (current.enabled) {
            TailwareVpnStateStore.rollbackFailedVpnStartIfCurrent(this, current)
                ?: TailwareVpnStateStore.desiredVpnState(this)
          } else {
            current
          }
        } catch (error: VpnStatePersistenceException) {
          containVpnStatePersistenceFailure(error)
        }
    if (current.enabled && !safeState.enabled) {
      TailwareActivationCapability.revokeVpnCommand(current.generation)
    }
    cancelStartWatchdog()
    activeCommand = safeState
    activeCommandStartId = startId
    initializedApp?.setWantRunning(wantRunning = false, commandOwner = safeState)
    close()
    publishVpnStatus(false)
    stopOwnedService(startId)
    return START_NOT_STICKY
  }

  private fun requireInitializedApp(): App =
      initializedApp ?: App.get().also { initializedApp = it }

  /** Drops service-local IDs once App no longer recognizes any logical foreground-login owner. */
  private fun hasAcknowledgedLoginForegroundOwner(): Boolean {
    if (activeLoginForegroundRequestIds.isEmpty()) return false
    if (UninitializedApp.get().hasLoginForegroundOwner()) return true
    activeLoginForegroundRequestIds.clear()
    activeLoginForegroundCapabilities.clear()
    return false
  }

  @Synchronized
  override fun close() {
    closeInternal(clearLoginForegroundOwners = true, removeForeground = true)
  }

  @Synchronized
  private fun closeInternal(
      clearLoginForegroundOwners: Boolean,
      removeForeground: Boolean,
      continuation: NativeVpnTeardownContinuation = NativeVpnTeardownContinuation.StopOwner,
      continuationCommand: DesiredVpnState? = activeCommand,
  ): Boolean {
    if (pendingNativeVpnTeardown != null) return true
    if (closed) return false
    closed = true
    cancelStartWatchdog()
    val hadNativeVpn = vpnRequested || tunnelActive
    val disconnectGeneration =
        vpnDisconnectGeneration(
            activeCommand = activeCommand,
            currentState = TailwareVpnStateStore.desiredVpnState(this),
        )
    vpnRequested = false
    tunnelActive = false
    if (clearLoginForegroundOwners) {
      activeLoginForegroundRequestIds.clear()
      activeLoginForegroundCapabilities.clear()
    }

    if (!hadNativeVpn) {
      activeCommand = null
      activeCommandStartId = 0
      disconnectVPN()
      if (removeForeground && foregroundStarted) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
      }
      return false
    }

    if (shouldPublishStopping(hadNativeVpn)) {
      Notifier.setState(Ipn.State.Stopping)
    }
    val pending =
        PendingNativeVpnTeardown(
            generation = disconnectGeneration,
            continuation = continuation,
            command = continuationCommand,
            stopStartId = activeCommandStartId.takeIf { it > 0 } ?: latestStartId,
            removeForeground = removeForeground,
        )
    val decision =
        nativeVpnTeardownCoordinator.beginTeardown(
            requestedTeardownGeneration = disconnectGeneration)
    if (!decision.scheduleTimeouts) {
      // An unexpected coordinator mismatch must fail closed. Keep the existing foreground owner
      // and do not publish Disconnected; the failure notification makes the stuck barrier visible.
      pendingNativeVpnTeardown = pending
      TSLog.e(
          TAG,
          "Native VPN teardown coordinator rejected generation=$disconnectGeneration: $decision",
      )
      StartVPNWorker.notifyVpnStopFailed(UninitializedApp.get())
      return true
    }
    pendingNativeVpnTeardown = pending
    if (!showStoppingForegroundNotification()) {
      TSLog.e(TAG, "Unable to refresh foreground protection for native VPN teardown")
    }
    scheduleNativeVpnTeardownTimeouts(disconnectGeneration)
    try {
      // Submission is producer-nonblocking. The service remains in foreground Stopping until the
      // exact-generation native acknowledgement proves descriptor and hook closure.
      Libtailscale.serviceDisconnect(this, disconnectGeneration)
    } catch (error: Exception) {
      TSLog.e(TAG, "Failed to submit native VPN teardown: $error")
      requestForcedNativeVpnTeardown(disconnectGeneration)
    }
    return true
  }

  /** Stops VPN ownership while retaining every acknowledged foreground-login owner. */
  private fun stopVpnWhileKeepingLoginForeground(
      command: DesiredVpnState,
      startId: Int,
  ): Int {
    check(!command.enabled)
    val awaitingNativeAcknowledgement =
        closeInternal(
            clearLoginForegroundOwners = false,
            removeForeground = false,
            continuation = NativeVpnTeardownContinuation.KeepLoginForeground,
            continuationCommand = command,
        )
    if (awaitingNativeAcknowledgement) return START_NOT_STICKY
    closed = false
    activeCommand = command
    activeCommandStartId = startId
    publishVpnStatus(false)
    if (!refreshOwnedForegroundNotification()) {
      TSLog.e(TAG, "Unable to retain foreground protection while stopping the VPN")
      close()
      stopOwnedService(startId)
    }
    return START_NOT_STICKY
  }

  override fun disconnectVPN() {
    // Service termination belongs to the Android startId that owns the command. Go may call this
    // while a newer start is already queued, so disconnectVPN must never stop the service
    // unconditionally.
  }

  override fun nativeVpnTeardownComplete(commandGeneration: Long) {
    // Go invokes this only after the exact accepted generation has closed every native TUN
    // descriptor and removed its protect/bind hooks. Serialize the release with service intents.
    mainHandler.post { completeNativeVpnTeardown(commandGeneration) }
  }

  private fun completeNativeVpnTeardown(commandGeneration: Long) {
    val decision = nativeVpnTeardownCoordinator.onNativeAcknowledged(commandGeneration)
    if (decision.outcome == NativeVpnTeardownOutcome.Ignored) {
      TSLog.w(
          TAG, "Ignoring stale native VPN teardown acknowledgement generation=$commandGeneration")
      return
    }
    val pending = pendingNativeVpnTeardown
    if (pending == null || pending.generation != commandGeneration) {
      TSLog.e(
          TAG,
          "Native teardown coordinator completed generation=$commandGeneration without its Android owner",
      )
      return
    }
    cancelNativeVpnTeardownTimeouts()
    pendingNativeVpnTeardown = null

    if (decision.continueWithSuccessor) {
      // The old descriptor barrier is proven closed, but a newer accepted Connect still owns the
      // foreground service. Never flash Disconnected or remove its protection.
      val current = TailwareVpnStateStore.desiredVpnState(this)
      if (current.enabled && isAuthorizedVpnCommand(current)) {
        closed = false
        if (activeCommand != current || !vpnRequested) {
          startVpnForCommand(current, latestStartId.takeIf { it > 0 } ?: pending.stopStartId)
        } else if (!refreshOwnedForegroundNotification()) {
          stopAfterForegroundFailure(current, latestStartId)
        }
      } else {
        // The queued successor itself was superseded by OFF while the old descriptor barrier was
        // closing. Reconcile that exact newest owner now; if it submitted native work, this begins
        // a second exact-generation teardown rather than treating the old acknowledgement as its
        // proof.
        closed = false
        activeCommand = current
        activeCommandStartId = latestStartId.takeIf { it > 0 } ?: pending.stopStartId
        reconcileCurrentDesiredState(activeCommandStartId)
      }
      return
    }

    val currentAfterAcknowledgement = TailwareVpnStateStore.desiredVpnState(this)
    if (currentAfterAcknowledgement.enabled &&
        currentAfterAcknowledgement.generation > commandGeneration &&
        TailwareActivationCapability.acceptsVpnCommand(currentAfterAcknowledgement.generation)) {
      // The newer Connect was persisted/bound but its service intent has not reached this main
      // loop yet, so the pure coordinator could not record it as a successor. Preserve foreground
      // ownership and proactively reconcile the already-authorized exact command instead of
      // flashing Disconnected or stopping the shared service between the two envelopes.
      closed = false
      activeCommand = currentAfterAcknowledgement
      activeCommandStartId = latestStartId.takeIf { it > 0 } ?: pending.stopStartId
      startVpnForCommand(currentAfterAcknowledgement, activeCommandStartId)
      return
    }

    check(decision.removeForegroundProtection && decision.publishDisconnected)
    vpnRequested = false
    tunnelActive = false
    disconnectVPN()
    when (pending.continuation) {
      NativeVpnTeardownContinuation.StopOwner -> {
        activeCommand = null
        activeCommandStartId = 0
        publishVpnStatus(false)
        removeForegroundAfterAcknowledgedTeardown(pending)
        stopOwnedService(pending.stopStartId)
      }
      NativeVpnTeardownContinuation.KeepLoginForeground -> {
        closed = false
        activeCommand = pending.command
        activeCommandStartId = pending.stopStartId
        publishVpnStatus(false)
        if (!refreshOwnedForegroundNotification()) {
          TSLog.e(TAG, "Unable to retain login foreground after native VPN teardown")
          close()
          stopOwnedService(pending.stopStartId)
        }
      }
      NativeVpnTeardownContinuation.Restart -> {
        val command = pending.command
        if (command == null || !isAuthorizedVpnCommand(command)) {
          // A newer state won after the acknowledgement but before restart continuation.
          activeCommand = null
          activeCommandStartId = 0
          reconcileCurrentDesiredState(latestStartId.takeIf { it > 0 } ?: pending.stopStartId)
          return
        }
        if (pending.removeForeground) {
          activeCommand = null
          activeCommandStartId = 0
          removeForegroundAfterAcknowledgedTeardown(pending)
          stopOwnedService(pending.stopStartId)
        } else {
          closed = false
          activeCommand = command
          activeCommandStartId = pending.stopStartId
          if (!refreshOwnedForegroundNotification()) {
            stopAfterForegroundFailure(command, pending.stopStartId)
            return
          }
        }
        UninitializedApp.get().restoreVPN(command)
      }
    }
  }

  private fun scheduleNativeVpnTeardownTimeouts(commandGeneration: Long) {
    cancelNativeVpnTeardownTimeouts()
    val forceTimeout = Runnable { requestForcedNativeVpnTeardown(commandGeneration) }
    nativeVpnTeardownForceTimeout = forceTimeout
    mainHandler.postDelayed(forceTimeout, NATIVE_VPN_TEARDOWN_ACK_TIMEOUT_MILLIS)
  }

  private fun requestForcedNativeVpnTeardown(commandGeneration: Long) {
    val decision = nativeVpnTeardownCoordinator.onForceCloseTimeout(commandGeneration)
    if (!decision.requestForceClose) return
    nativeVpnTeardownForceTimeout?.let(mainHandler::removeCallbacks)
    nativeVpnTeardownForceTimeout = null
    val hardTimeout = Runnable { failNativeVpnTeardownClosed(commandGeneration) }
    nativeVpnTeardownHardTimeout = hardTimeout
    mainHandler.postDelayed(hardTimeout, NATIVE_VPN_TEARDOWN_FORCE_GRACE_MILLIS)

    val app = initializedApp
    if (app == null) {
      TSLog.e(TAG, "Cannot schedule forced native teardown without an initialized owner")
      return
    }
    // Although ForceVPNTeardown installs its barrier quickly, descriptor closure can call into
    // platform/native code. It must never execute on the service main thread.
    app.applicationScope.launch(Dispatchers.IO) {
      val accepted =
          runCatching { Libtailscale.forceVPNTeardown(this@IPNService, commandGeneration) }
              .onFailure { error ->
                TSLog.e(
                    TAG, "Forced native VPN teardown failed generation=$commandGeneration", error)
              }
              .getOrDefault(false)
      if (!accepted) {
        TSLog.e(TAG, "Native force-close rejected generation=$commandGeneration")
      }
    }
  }

  private fun failNativeVpnTeardownClosed(commandGeneration: Long) {
    val decision = nativeVpnTeardownCoordinator.onHardTimeout(commandGeneration)
    if (!decision.hardTimeoutFailedClosed) return
    cancelNativeVpnTeardownTimeouts()
    TSLog.e(
        TAG,
        "Native VPN teardown did not acknowledge generation=$commandGeneration after force-close",
    )
    initializedApp
        ?.getAppScopedViewModel()
        ?.setVpnServiceError(
            TailwareStrings.selectedText(
                this,
                R.string.wear_android_did_not_allow_tailware_to_stop_the_vpn_service,
            ))
    StartVPNWorker.notifyVpnStopFailed(UninitializedApp.get())
    // Keep the mandatory Stopping foreground owner. Only a later exact native acknowledgement
    // may remove it or publish Disconnected.
    showStoppingForegroundNotification()
  }

  private fun cancelNativeVpnTeardownTimeouts() {
    nativeVpnTeardownForceTimeout?.let(mainHandler::removeCallbacks)
    nativeVpnTeardownHardTimeout?.let(mainHandler::removeCallbacks)
    nativeVpnTeardownForceTimeout = null
    nativeVpnTeardownHardTimeout = null
  }

  private fun removeForegroundAfterAcknowledgedTeardown(pending: PendingNativeVpnTeardown) {
    if (pending.removeForeground && foregroundStarted) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      foregroundStarted = false
    }
  }

  override fun onDestroy() {
    close()
    publishVpnStatus(false)
    if (activeInstance === this) activeInstance = null
    super.onDestroy()
  }

  private fun handleFailClosedStopDispatch(commandGeneration: Long) {
    val command = TailwareVpnStateStore.desiredVpnState(this)
    if (command.enabled || command.generation != commandGeneration) return
    if (pendingNativeVpnTeardown?.generation == commandGeneration) return
    cancelStartWatchdog()
    activeCommand = command
    activeCommandStartId = latestStartId
    requestBackendStopForVpnCleanup(
        command = command,
        authenticationEpoch = null,
        cleanupGeneration = command.generation,
    )
    close()
    publishVpnStatus(false)
    stopOwnedService(latestStartId)
  }

  override fun onRevoke() {
    // Android may deliver revocation away from the service main thread. Queue the complete owner
    // transition with onStartCommand so a concurrent release or newer VPN command cannot be
    // overwritten by stale revoke cleanup.
    mainHandler.post(::handleVpnPermissionRevoked)
  }

  private fun handleVpnPermissionRevoked() {
    // VPN permission was granted to another app, so tell the Go backend and then set prepared to be
    // false so that when user attempts to connect again, VpnService.prepare() is called.
    val revokedOwner = activeCommand
    val revokedCapability = TailwareActivationCapability.currentVpnCapability()
    val command =
        try {
          TailwareVpnStateStore.setDesiredVpnEnabled(this, false)
        } catch (error: VpnStatePersistenceException) {
          containVpnStatePersistenceFailure(error)
        }
    revokedOwner?.let { TailwareActivationCapability.revokeVpnCommand(it.generation) }
    TailwareActivationCapability.revokeVpnCapability(revokedCapability)
    activeCommand = command
    activeCommandStartId = latestStartId
    initializedApp?.setWantRunning(
        wantRunning = false,
        commandOwner = command,
    )
    setVpnPrepared(false)
    if (!ownsCommandCleanup(command, TailwareVpnStateStore.desiredVpnState(this))) {
      reconcileCurrentDesiredState(latestStartId)
      return
    }
    if (hasAcknowledgedLoginForegroundOwner()) {
      stopVpnWhileKeepingLoginForeground(command, latestStartId)
      return
    }
    close()
    publishVpnStatus(false)
    stopOwnedService(latestStartId)
  }

  private fun setVpnPrepared(isPrepared: Boolean) {
    initializedApp?.getAppScopedViewModel()?.setVpnPrepared(isPrepared)
  }

  private fun applyVpnStatus(status: Boolean, commandGeneration: Long) {
    val owner = activeCommand
    if (!ownsVpnStatusCallback(
        activeCommand = owner,
        currentState = TailwareVpnStateStore.desiredVpnState(this),
        callbackGeneration = commandGeneration,
        status = status,
        closed = closed,
    )) {
      TSLog.w(
          TAG,
          "Ignoring VPN status=$status from stale generation=$commandGeneration; active=$owner",
      )
      return
    }

    val updatePlan = vpnStatusUpdatePlan(status)
    tunnelActive = status
    if (status) {
      initializedApp?.getAppScopedViewModel()?.setVpnServiceError(null)
    }
    publishVpnStatus(status)
    if (updatePlan.cancelStartWatchdog) {
      cancelStartWatchdog()
    }
    if (!showForegroundNotification(vpnRunning = updatePlan.notificationRunning)) {
      stopAfterForegroundFailure(owner, latestStartId)
      return
    }
    if (updatePlan.scheduleStartWatchdog && owner != null && activeCommand == owner && !closed) {
      scheduleStartWatchdog(owner, activeCommandStartId.takeIf { it > 0 } ?: latestStartId)
    }
  }

  private fun publishVpnStatus(status: Boolean) {
    if (!status && nativeVpnTeardownCoordinator.snapshot().teardownProtectionRequired) {
      TSLog.d(TAG, "Deferring Disconnected publication until exact native teardown acknowledgement")
      return
    }
    initializedApp?.getAppScopedViewModel()?.setVpnActive(status)
    TailwareTileUpdates.request(this)
  }

  private fun scheduleStartWatchdog(command: DesiredVpnState, startId: Int) {
    cancelStartWatchdog()
    val watchdog = Runnable {
      val decision =
          vpnStartWatchdogDecision(
              commandOwner = command,
              currentState = TailwareVpnStateStore.desiredVpnState(this),
              ipnState = Notifier.state.value,
              underlyingNetworkStatus = NetworkChangeCallback.underlyingNetworkStatus.value,
              tunnelActive = tunnelActive,
              closed = closed,
              hasLiveLoginForegroundOwner = hasAcknowledgedLoginForegroundOwner(),
          )
      if (decision == VpnStartWatchdogDecision.None || activeCommand != command) {
        return@Runnable
      }
      when (decision) {
        VpnStartWatchdogDecision.WaitForLogin -> {
          TSLog.d(TAG, "VPN start watchdog is paused for sign-in or device approval")
          scheduleStartWatchdog(command, startId)
          return@Runnable
        }
        VpnStartWatchdogDecision.WaitForNetwork -> {
          TSLog.d(TAG, "VPN start is waiting for a usable underlying network")
          scheduleStartWatchdog(command, startId)
          return@Runnable
        }
        VpnStartWatchdogDecision.StopNeedsLogin ->
            TSLog.w(TAG, "VPN start watchdog stopped a tunnel that requires login")
        VpnStartWatchdogDecision.StopNoTunnel ->
            TSLog.w(TAG, "VPN start watchdog stopped a start with no Android tunnel")
        VpnStartWatchdogDecision.None -> Unit
      }
      stopAfterStartFailure(command, startId)
    }
    startWatchdog = watchdog
    mainHandler.postDelayed(watchdog, VPN_START_WATCHDOG_MILLIS)
  }

  private fun scheduleRestartWatchdog(command: DesiredVpnState, startId: Int) {
    cancelStartWatchdog()
    val watchdog = Runnable {
      if (activeCommand != command ||
          TailwareVpnStateStore.desiredVpnState(this) != command ||
          closed) {
        return@Runnable
      }
      TSLog.e(TAG, "VPN restart timed out while stopping the previous backend state")
      stopAfterStartFailure(command, startId)
    }
    startWatchdog = watchdog
    mainHandler.postDelayed(watchdog, VPN_START_WATCHDOG_MILLIS)
  }

  private fun cancelStartWatchdog() {
    startWatchdog?.let(mainHandler::removeCallbacks)
    startWatchdog = null
  }

  private fun stopAfterForegroundFailure(command: DesiredVpnState?, startId: Int) {
    TSLog.e(TAG, "Stopping VPN service because foreground promotion failed")
    stopAfterStartFailure(command, startId, foregroundUnavailable = true)
  }

  private fun stopAfterPersistedForegroundFailure(startId: Int) {
    TSLog.e(TAG, "Stopping restored VPN service because bootstrap foreground promotion failed")
    val failedOwner = TailwareVpnStateStore.desiredVpnState(this)
    val stopOwner =
        if (failedOwner.enabled) {
          try {
            TailwareVpnStateStore.rollbackFailedVpnStartIfCurrent(this, failedOwner)
          } catch (error: VpnStatePersistenceException) {
            containVpnStatePersistenceFailure(error)
          }
        } else {
          failedOwner
        }
    if (stopOwner != null && !stopOwner.enabled) {
      activeCommand = stopOwner
      activeCommandStartId = startId
      initializedApp?.setWantRunning(wantRunning = false, commandOwner = stopOwner)
    }
    // Do not generically reconcile a concurrent newer ON from this failed sticky start. Its own
    // queued start will receive a fresh foreground/authorization decision.
    close()
    publishVpnStatus(false)
    stopOwnedService(startId)
  }

  /**
   * Ends a terminal start/restart failure without leaving an ownerless desired-ON state behind.
   *
   * Rollback is deliberately attempted before any backend, TUN, foreground, or service cleanup.
   * Only the exact failed generation can claim OFF; if a newer user start already exists, its state
   * is preserved and the shared service reconciles that newer command instead of closing it.
   */
  private fun stopAfterStartFailure(
      command: DesiredVpnState?,
      startId: Int,
      foregroundUnavailable: Boolean = false,
  ) {
    val stopCommand =
        command?.let {
          try {
            TailwareVpnStateStore.rollbackFailedVpnStartIfCurrent(
                context = this,
                failedCommand = it,
            )
          } catch (error: VpnStatePersistenceException) {
            containVpnStatePersistenceFailure(error)
          }
        }
    when (terminalVpnFailureCleanup(command, stopCommand)) {
      TerminalVpnFailureCleanup.ReconcileNewerOwner -> {
        // The failed generation lost ownership between the callback guard and the atomic rollback.
        // Closing this service here could tear down a newer start already assigned to the same
        // instance. Reconcile instead: a newer ON is started/reasserted, while a newer OFF performs
        // its own authoritative cleanup.
        reconcileCurrentDesiredState(startId)
        return
      }
      TerminalVpnFailureCleanup.CleanupFailedOwner -> {
        checkNotNull(stopCommand)
        command?.let { TailwareActivationCapability.revokeVpnCommand(it.generation) }
        activeCommand = stopCommand
        activeCommandStartId = startId
        initializedApp?.setWantRunning(
            wantRunning = false,
            commandOwner = stopCommand,
        )
      }
      TerminalVpnFailureCleanup.CleanupUnownedService -> Unit
    }
    stopCommand?.let { owner ->
      if (!ownsCommandCleanup(owner, TailwareVpnStateStore.desiredVpnState(this))) {
        reconcileCurrentDesiredState(startId)
        return
      }
    }
    if (!foregroundUnavailable && hasAcknowledgedLoginForegroundOwner()) {
      val currentOff = stopCommand ?: TailwareVpnStateStore.desiredVpnState(this)
      if (!currentOff.enabled) {
        stopVpnWhileKeepingLoginForeground(currentOff, startId)
        return
      }
    }
    close()
    if (pendingNativeVpnTeardown == null && foregroundStarted) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      foregroundStarted = false
    }
    publishVpnStatus(false)
    stopOwnedService(startId)
  }

  private fun containVpnStatePersistenceFailure(
      error: VpnStatePersistenceException
  ): DesiredVpnState {
    val app = initializedApp
    if (app != null) {
      return app.containVpnStatePersistenceFailure(error)
    }
    TSLog.e(
        TAG,
        "Desired VPN state persistence failed before App initialization; " +
            "localOff=${error.localOff} cleanupPersisted=${error.cleanupPersisted}: $error",
    )
    return error.localOff
  }

  private fun stopOwnedService(startId: Int) {
    if (startId <= 0) return
    pendingNativeVpnTeardown?.let { pending ->
      // stopSelfResult would remove Android's foreground protection before native closure. Record
      // the newest owning startId and complete it only after exact-generation acknowledgement.
      pending.stopStartId = maxOf(pending.stopStartId, startId)
      return
    }
    val stopped = stopSelfResult(startId)
    if (shouldReconcileAfterOwnedStop(stopped, startId, latestStartId)) {
      // A later Android start already exists. Let that exact startId reconcile the persisted
      // command instead of allowing this older owner to terminate the shared service.
      val newestStartId = latestStartId
      mainHandler.post { reconcileCurrentDesiredState(newestStartId) }
    }
  }

  private fun refreshOwnedForegroundNotification(): Boolean {
    if (nativeVpnTeardownCoordinator.snapshot().teardownProtectionRequired) {
      return showStoppingForegroundNotification()
    }
    return when (foregroundNotificationOwner(
        activeLoginRequestId = activeLoginForegroundRequestIds.firstOrNull(),
        vpnRequested = vpnRequested,
        tunnelActive = tunnelActive,
    )) {
      ForegroundNotificationOwner.LoginOnly ->
          showForegroundNotification(
              hideDisconnectAction = true,
              exitNodeName = null,
              vpnRunning = false,
          )
      ForegroundNotificationOwner.Vpn -> showForegroundNotification(vpnRunning = tunnelActive)
      ForegroundNotificationOwner.None -> showForegroundNotification(vpnRunning = false)
    }
  }

  private fun showStoppingForegroundNotification(): Boolean =
      showForegroundNotification(
          hideDisconnectAction = true,
          exitNodeName = null,
          vpnRunning = false,
          vpnStopping = true,
      )

  private fun showBootstrapForegroundNotification(): Boolean =
      try {
        startForeground(
            UninitializedApp.STATUS_NOTIFICATION_ID,
            NotificationCompat.Builder(this, UninitializedApp.STATUS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(TailwareStrings.selectedText(this, R.string.starting))
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build(),
        )
        foregroundStarted = true
        true
      } catch (error: Exception) {
        TSLog.e(TAG, "Failed to publish bootstrap foreground notification: $error")
        false
      }

  private fun showForegroundNotification(
      hideDisconnectAction: Boolean,
      exitNodeName: String? = null,
      vpnRunning: Boolean,
      vpnStopping: Boolean = false,
  ): Boolean {
    return try {
      startForeground(
          UninitializedApp.STATUS_NOTIFICATION_ID,
          UninitializedApp.get()
              .buildStatusNotification(
                  vpnRunning = vpnRunning,
                  hideDisconnectAction = hideDisconnectAction,
                  exitNodeName = exitNodeName,
                  vpnStarting = !vpnRunning && !vpnStopping,
                  vpnStopping = vpnStopping,
              ))
      foregroundStarted = true
      true
    } catch (error: Exception) {
      TSLog.e(TAG, "Failed to start foreground service: $error")
      false
    }
  }

  private fun showForegroundNotification(vpnRunning: Boolean): Boolean {
    if (nativeVpnTeardownCoordinator.snapshot().teardownProtectionRequired) {
      return showStoppingForegroundNotification()
    }
    val hideDisconnectAction = MDMSettings.forceEnabled.flow.value.value
    val exitNodeName = UninitializedApp.getExitNodeName(Notifier.prefs.value, Notifier.netmap.value)
    return showForegroundNotification(hideDisconnectAction, exitNodeName, vpnRunning)
  }

  private fun configIntent(): PendingIntent {
    return PendingIntent.getActivity(
        this,
        0,
        Intent(this, WearMainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }

  private fun allowApp(b: Builder, name: String) {
    try {
      b.addAllowedApplication(name)
    } catch (e: PackageManager.NameNotFoundException) {
      TSLog.e(TAG, "Failed to add allowed application: $e")
    }
  }

  private fun disallowApp(b: Builder, name: String) {
    try {
      b.addDisallowedApplication(name)
    } catch (e: PackageManager.NameNotFoundException) {
      TSLog.e(TAG, "Failed to add disallowed application: $e")
    }
  }

  override fun newBuilder(): VPNServiceBuilder {
    val command =
        activeCommand
            ?: throw SecurityException(
                "VPN builder access requires the exact live Connect command generation.")
    return TailwareActivationCapability.withVpnCommandAuthorization(command.generation) {
      if (!canMutateAuthorizedTunnel(command)) {
        throw SecurityException(
            "VPN builder access requires the exact live Connect command generation.")
      }
      newBuilderForAuthorizedCommand(command)
    }
        ?: throw SecurityException(
            "VPN builder access requires the exact live Connect command generation.")
  }

  private fun newBuilderForAuthorizedCommand(command: DesiredVpnState): VPNServiceBuilder {
    val b: Builder =
        Builder()
            .setConfigureIntent(configIntent())
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
    b.setMetered(false) // Inherit the metered status from the underlying networks.
    b.setUnderlyingNetworks(null) // Use all available networks.

    val mdmAllowed =
        MDMSettings.includedPackages.flow.value.value?.split(",")?.map { it.trim() } ?: emptyList()
    val mdmDisallowed =
        MDMSettings.excludedPackages.flow.value.value?.split(",")?.map { it.trim() } ?: emptyList()

    var packagesList: List<String>
    var allowPackages: Boolean
    if (mdmAllowed.isNotEmpty()) {
      // An admin defined a list of packages that are exclusively allowed to be used via
      // Tailscale, so only allow those.
      packagesList = mdmAllowed
      allowPackages = true
      TSLog.d(TAG, "Included application packages were set via MDM: $mdmAllowed")
    } else if (mdmDisallowed.isNotEmpty()) {
      // An admin defined a list of packages that are excluded from accessing Tailscale,
      // so ignore user definitions and only exclude those
      packagesList = mdmDisallowed
      allowPackages = false
      TSLog.d(TAG, "Excluded application packages were set via MDM: $mdmDisallowed")
    } else {
      // Otherwise, prevent user manually disallowed apps from getting their traffic + DNS routed
      // via Tailscale
      packagesList = UninitializedApp.get().selectedPackageNames()
      allowPackages = UninitializedApp.get().allowSelectedPackages()
      TSLog.d(TAG, "Application packages were set by user: $packagesList")
    }

    if (allowPackages) {
      for (packageName in packagesList) {
        TSLog.d(TAG, "Including app: $packageName")
        allowApp(b, packageName)
      }
    } else {
      // Make sure to also exclude hard-coded apps that are known to cause issues
      packagesList += UninitializedApp.get().builtInDisallowedPackageNames

      for (packageName in packagesList) {
        TSLog.d(TAG, "Disallowing app: $packageName")
        disallowApp(b, packageName)
      }
    }

    return VPNServiceBuilder(
        builder = b,
        commandGeneration = command.generation,
        isAuthorized = { canMutateAuthorizedTunnel(command) },
    )
  }

  override fun protect(socket: Int): Boolean {
    val command = activeCommand ?: return false
    return TailwareActivationCapability.withVpnCommandAuthorization(command.generation) {
      if (canMutateAuthorizedTunnel(command)) super.protect(socket) else false
    } ?: false
  }

  override fun bindSocketToNetwork(fd: Int): Boolean {
    val command = activeCommand ?: return false
    return TailwareActivationCapability.withVpnCommandAuthorization(command.generation) {
      if (canMutateAuthorizedTunnel(command)) {
        initializedApp?.bindSocketToNetwork(fd) == true
      } else {
        false
      }
    } ?: false
  }

  private fun canMutateAuthorizedTunnel(command: DesiredVpnState): Boolean =
      !closed && vpnRequested && activeCommand == command && isAuthorizedVpnCommand(command)

  companion object {
    @Volatile private var activeInstance: IPNService? = null

    /**
     * Process-local fallback when Android rejects delivery of an OFF service intent.
     *
     * This only schedules work on the already-running service. It never calls stopService, so an
     * active TUN keeps foreground protection until its native exact-generation acknowledgement.
     */
    internal fun requestFailClosedStop(commandGeneration: Long): Boolean {
      val service = activeInstance ?: return false
      service.mainHandler.post { service.handleFailClosedStopDispatch(commandGeneration) }
      return true
    }

    const val ACTION_START_VPN = "com.tailscale.ipn.START_VPN"
    const val ACTION_STOP_VPN = "com.tailscale.ipn.STOP_VPN"
    const val ACTION_RESTART_VPN = "com.tailscale.ipn.RESTART_VPN"
    const val ACTION_START_FOREGROUND_ONLY = "com.tailscale.ipn.START_FOREGROUND_ONLY"
    const val ACTION_RELEASE_FOREGROUND_ONLY = "com.tailscale.ipn.RELEASE_FOREGROUND_ONLY"
    const val ACTION_REFRESH_NOTIFICATION = "com.tailscale.ipn.REFRESH_NOTIFICATION"
    const val EXTRA_COMMAND_GENERATION = "com.tailscale.ipn.extra.COMMAND_GENERATION"
    const val EXTRA_AUTHENTICATION_EPOCH = "com.tailscale.ipn.extra.AUTHENTICATION_EPOCH"
    const val EXTRA_LOGIN_FOREGROUND_REQUEST_ID =
        "com.tailscale.ipn.extra.LOGIN_FOREGROUND_REQUEST_ID"
    const val EXTRA_LOGIN_BACKEND_ALREADY_STOPPED =
        "com.tailscale.ipn.extra.LOGIN_BACKEND_ALREADY_STOPPED"
    const val EXTRA_LOGIN_BACKEND_STOP_AWAITED_THROUGH_GENERATION =
        "com.tailscale.ipn.extra.LOGIN_BACKEND_STOP_AWAITED_THROUGH_GENERATION"
    const val EXTRA_VPN_CLEANUP_GENERATION = "com.tailscale.ipn.extra.VPN_CLEANUP_GENERATION"
    private const val INVALID_LOGIN_FOREGROUND_REQUEST = Long.MIN_VALUE
  }
}
