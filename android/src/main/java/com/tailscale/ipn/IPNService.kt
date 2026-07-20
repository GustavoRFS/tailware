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
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.wear.DesiredVpnState
import com.tailscale.ipn.wear.TailwareVpnStateStore
import com.tailscale.ipn.wear.WearMainActivity
import com.tailscale.ipn.wear.isCurrentVpnCommand
import com.tailscale.ipn.wear.tile.TailwareTileUpdates
import java.util.UUID
import libtailscale.Libtailscale

internal fun shouldPublishStopping(state: Ipn.State): Boolean =
    state == Ipn.State.Running || state == Ipn.State.Starting

internal enum class VpnStartWatchdogDecision {
  None,
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
    tunnelActive: Boolean,
    closed: Boolean,
): VpnStartWatchdogDecision {
  if (closed || tunnelActive || !commandOwner.enabled || currentState != commandOwner) {
    return VpnStartWatchdogDecision.None
  }
  return if (ipnState == Ipn.State.NeedsLogin) {
    VpnStartWatchdogDecision.StopNeedsLogin
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
  private var startWatchdog: Runnable? = null

  override fun id(): String {
    return randomID
  }

  override fun commandGeneration(): Long = TailwareVpnStateStore.desiredVpnState(this).generation

  /**
   * Atomically transfers ownership of a native tunnel failure to a new OFF generation.
   *
   * Go must call this before tearing down a failed TUN. If a newer user command already won, the
   * claim fails and the stale native error is not allowed to change Android state.
   */
  override fun handleVpnServiceError(commandGeneration: Long): Boolean {
    val failedCommand = DesiredVpnState(enabled = true, generation = commandGeneration)
    val stopCommand =
        TailwareVpnStateStore.rollbackFailedVpnStartIfCurrent(
            context = this,
            failedCommand = failedCommand,
        ) ?: return false
    mainHandler.post {
      if (!isCurrentCommand(stopCommand)) return@post
      activeCommand = stopCommand
      initializedApp?.setWantRunning(
          wantRunning = false,
          commandOwner = stopCommand,
      )
      close()
      publishVpnStatus(false)
    }
    return true
  }

  override fun updateVpnStatus(status: Boolean) {
    // Go can call this from a worker thread. Serialize status, notification, and lifecycle changes
    // with onStartCommand on the service's main thread.
    mainHandler.post { applyVpnStatus(status) }
  }

  override fun onCreate() {
    super.onCreate()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    latestStartId = startId
    return when (intent?.action) {
      ACTION_STOP_VPN -> {
        val command =
            acceptCommand(intent, requestedEnabled = false)
                ?: return reconcileCurrentDesiredState(startId)
        cancelStartWatchdog()
        activeCommand = command
        initializedApp?.setWantRunning(
            wantRunning = false,
            commandOwner = command,
        )
        close()
        stopSelf(startId)
        START_NOT_STICKY
      }
      ACTION_RESTART_VPN -> {
        val command =
            acceptCommand(intent, requestedEnabled = true)
                ?: return reconcileCurrentDesiredState(startId)
        cancelStartWatchdog()
        closed = false
        activeCommand = command
        if (!showForegroundNotification(vpnRunning = false)) {
          stopAfterForegroundFailure(command, startId)
          return START_NOT_STICKY
        }
        val app =
            try {
              requireInitializedApp()
            } catch (error: Exception) {
              TSLog.e(TAG, "Failed to initialize the VPN engine for restart: $error")
              stopAfterStartFailure(command, startId)
              return START_NOT_STICKY
            }
        scheduleRestartWatchdog(command, startId)
        // A restart is an implementation detail, not a user-requested disconnect. The completion
        // closure is allowed to run only while this exact command generation still owns the state.
        app.setWantRunning(
            wantRunning = false,
            reconcileDesiredState = false,
            commandOwner = command,
            onSuccess = {
              mainHandler.post {
                if (TailwareVpnStateStore.desiredVpnState(this) != command) {
                  return@post
                }
                cancelStartWatchdog()
                close()
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
        if (!showForegroundNotification(vpnRunning = false)) {
          val owner =
              activeCommand
                  ?: TailwareVpnStateStore.desiredVpnState(this).takeIf { desired ->
                    desired.enabled
                  }
          stopAfterForegroundFailure(command = owner, startId = startId)
        }
        START_NOT_STICKY
      }
      ACTION_START_VPN -> {
        val command =
            acceptCommand(intent, requestedEnabled = true)
                ?: return reconcileCurrentDesiredState(startId)
        startVpnForCommand(command, startId)
      }
      "android.net.VpnService" -> startFromPersistedState(startId)
      else -> {
        // Android recreated a sticky service after process death. Restore only the explicit
        // persisted ON choice; never let a system restart overwrite an explicit OFF.
        startFromPersistedState(startId)
      }
    }
  }

  private fun startFromPersistedState(startId: Int): Int {
    val expected = TailwareVpnStateStore.desiredVpnState(this)
    if (!expected.enabled || !UninitializedApp.get().isAbleToStartVPN()) {
      return reconcileCurrentDesiredState(startId)
    }
    val command =
        TailwareVpnStateStore.claimEnabledCommandIfCurrent(this, expected)
            ?: return reconcileCurrentDesiredState(startId)
    return startVpnForCommand(command, startId)
  }

  private fun startVpnForCommand(command: DesiredVpnState, startId: Int): Int {
    if (!isCurrentCommand(command)) {
      return reconcileCurrentDesiredState(startId)
    }
    if (!closed && activeCommand == command && (vpnRequested || tunnelActive)) {
      return START_STICKY
    }

    cancelStartWatchdog()
    closed = false
    activeCommand = command
    if (!showForegroundNotification(vpnRunning = false)) {
      stopAfterForegroundFailure(command, startId)
      return START_NOT_STICKY
    }
    if (!isCurrentCommand(command)) {
      return reconcileCurrentDesiredState(startId)
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
    if (!isCurrentCommand(command)) {
      return reconcileCurrentDesiredState(startId)
    }

    return try {
      vpnRequested = true
      Libtailscale.requestVPN(this, command.generation)
      scheduleStartWatchdog(command, startId)
      START_STICKY
    } catch (error: Exception) {
      TSLog.e(TAG, "Failed to request the VPN tunnel: $error")
      vpnRequested = false
      stopAfterStartFailure(command, startId)
      START_NOT_STICKY
    }
  }

  private fun reconcileCurrentDesiredState(startId: Int): Int {
    val current = TailwareVpnStateStore.desiredVpnState(this)
    return if (current.enabled && UninitializedApp.get().isAbleToStartVPN()) {
      startVpnForCommand(current, startId)
    } else {
      cancelStartWatchdog()
      initializedApp?.setWantRunning(
          wantRunning = false,
          commandOwner = current,
      )
      close()
      stopSelf(startId)
      START_NOT_STICKY
    }
  }

  private fun acceptCommand(
      intent: Intent,
      requestedEnabled: Boolean,
  ): DesiredVpnState? {
    if (!intent.hasExtra(EXTRA_COMMAND_GENERATION)) {
      // Notification actions and other direct service intents become the newest explicit command.
      return TailwareVpnStateStore.requestVpnEnabled(this, requestedEnabled)
    }
    val commandGeneration = intent.getLongExtra(EXTRA_COMMAND_GENERATION, Long.MIN_VALUE)
    val current = TailwareVpnStateStore.desiredVpnState(this)
    return current.takeIf {
      isCurrentVpnCommand(
          commandGeneration = commandGeneration,
          requestedEnabled = requestedEnabled,
          currentState = it,
      )
    }
  }

  private fun isCurrentCommand(command: DesiredVpnState): Boolean =
      TailwareVpnStateStore.desiredVpnState(this) == command

  private fun requireInitializedApp(): App =
      initializedApp ?: App.get().also { initializedApp = it }

  @Synchronized
  override fun close() {
    if (closed) return
    closed = true
    cancelStartWatchdog()
    if (shouldPublishStopping(Notifier.state.value)) {
      Notifier.setState(Ipn.State.Stopping)
    }

    val disconnectGeneration =
        activeCommand?.generation ?: TailwareVpnStateStore.desiredVpnState(this).generation
    val hadNativeVpn = vpnRequested || tunnelActive
    vpnRequested = false
    tunnelActive = false
    activeCommand = null
    if (hadNativeVpn) {
      try {
        Libtailscale.serviceDisconnect(this, disconnectGeneration)
      } catch (error: Exception) {
        TSLog.e(TAG, "Failed to disconnect the VPN service: $error")
      }
    }
    disconnectVPN()
    if (foregroundStarted) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      foregroundStarted = false
    }
  }

  override fun disconnectVPN() {
    stopSelf()
  }

  override fun onDestroy() {
    close()
    publishVpnStatus(false)
    super.onDestroy()
  }

  override fun onRevoke() {
    // VPN permission was granted to another app, so tell the Go backend and then set prepared to be
    // false so that when user attempts to connect again, VpnService.prepare() is called.
    val command = TailwareVpnStateStore.setDesiredVpnEnabled(this, false)
    val app = initializedApp ?: App.get().also { initializedApp = it }
    app.setWantRunning(
        wantRunning = false,
        commandOwner = command,
    )
    setVpnPrepared(false)
    close()
    publishVpnStatus(false)
    super.onRevoke()
  }

  private fun setVpnPrepared(isPrepared: Boolean) {
    initializedApp?.getAppScopedViewModel()?.setVpnPrepared(isPrepared)
  }

  private fun applyVpnStatus(status: Boolean) {
    val owner = activeCommand
    val ownerIsCurrent =
        owner != null &&
            owner.enabled &&
            isCurrentVpnCommand(
                commandGeneration = owner.generation,
                requestedEnabled = true,
                currentState = TailwareVpnStateStore.desiredVpnState(this),
            )
    if (status && (!ownerIsCurrent || closed)) {
      TSLog.w(TAG, "Ignoring a VPN-up callback that no longer owns the desired state")
      close()
      publishVpnStatus(false)
      return
    }

    tunnelActive = status
    publishVpnStatus(status)
    if (status) {
      cancelStartWatchdog()
      if (!showForegroundNotification(vpnRunning = true)) {
        stopAfterForegroundFailure(owner, latestStartId)
      }
    }
  }

  private fun publishVpnStatus(status: Boolean) {
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
              tunnelActive = tunnelActive,
              closed = closed,
          )
      if (decision == VpnStartWatchdogDecision.None || activeCommand != command) {
        return@Runnable
      }
      when (decision) {
        VpnStartWatchdogDecision.StopNeedsLogin ->
            TSLog.w(TAG, "VPN start watchdog stopped a tunnel that requires login")
        VpnStartWatchdogDecision.StopNoTunnel ->
            TSLog.w(TAG, "VPN start watchdog stopped a start with no Android tunnel")
        VpnStartWatchdogDecision.None -> Unit
      }
      stopAfterStartFailure(command, startId)
    }
    startWatchdog = watchdog
    mainHandler.postDelayed(watchdog, VPN_START_WATCHDOG_MS)
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
    mainHandler.postDelayed(watchdog, VPN_START_WATCHDOG_MS)
  }

  private fun cancelStartWatchdog() {
    startWatchdog?.let(mainHandler::removeCallbacks)
    startWatchdog = null
  }

  private fun stopAfterForegroundFailure(command: DesiredVpnState?, startId: Int) {
    TSLog.e(TAG, "Stopping VPN service because foreground promotion failed")
    stopAfterStartFailure(command, startId)
  }

  /**
   * Ends a terminal start/restart failure without leaving an ownerless desired-ON state behind.
   *
   * Rollback is deliberately attempted before any backend, TUN, foreground, or service cleanup.
   * Only the exact failed generation can claim OFF; if a newer user start already exists, its state
   * is preserved and the shared service reconciles that newer command instead of closing it.
   */
  private fun stopAfterStartFailure(command: DesiredVpnState?, startId: Int) {
    val stopCommand =
        command?.let {
          TailwareVpnStateStore.rollbackFailedVpnStartIfCurrent(
              context = this,
              failedCommand = it,
          )
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
        activeCommand = stopCommand
        initializedApp?.setWantRunning(
            wantRunning = false,
            commandOwner = stopCommand,
        )
      }
      TerminalVpnFailureCleanup.CleanupUnownedService -> Unit
    }
    close()
    if (foregroundStarted) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      foregroundStarted = false
    }
    publishVpnStatus(false)
    stopSelf(startId)
  }

  private fun showForegroundNotification(
      hideDisconnectAction: Boolean,
      exitNodeName: String? = null,
      vpnRunning: Boolean,
  ): Boolean {
    return try {
      startForeground(
          UninitializedApp.STATUS_NOTIFICATION_ID,
          UninitializedApp.get()
              .buildStatusNotification(
                  vpnRunning = vpnRunning,
                  hideDisconnectAction = hideDisconnectAction,
                  exitNodeName = exitNodeName,
                  vpnStarting = !vpnRunning,
              ))
      foregroundStarted = true
      true
    } catch (error: Exception) {
      TSLog.e(TAG, "Failed to start foreground service: $error")
      false
    }
  }

  private fun showForegroundNotification(vpnRunning: Boolean): Boolean {
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

    return VPNServiceBuilder(b)
  }

  companion object {
    private const val VPN_START_WATCHDOG_MS = 30_000L
    const val ACTION_START_VPN = "com.tailscale.ipn.START_VPN"
    const val ACTION_STOP_VPN = "com.tailscale.ipn.STOP_VPN"
    const val ACTION_RESTART_VPN = "com.tailscale.ipn.RESTART_VPN"
    const val ACTION_START_FOREGROUND_ONLY = "com.tailscale.ipn.START_FOREGROUND_ONLY"
    const val EXTRA_COMMAND_GENERATION = "com.tailscale.ipn.extra.COMMAND_GENERATION"
  }
}
