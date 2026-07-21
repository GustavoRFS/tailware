// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

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
import com.tailscale.ipn.ui.Links
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Health
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.AuthenticationEvent
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.viewModel.IpnViewModel
import com.tailscale.ipn.ui.viewModel.profileReadIsCoherent
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.wear.localization.TailwareStrings
import com.tailscale.ipn.wear.tile.TailwareTileUpdates
import java.text.Collator
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
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

private const val LOGIN_RETIREMENT_FAILURE_MESSAGE =
    "The previous sign-in attempt could not be cleaned up in time. Try again."

internal fun shouldAcceptLoginUrl(activeAttemptGeneration: Long?, candidateUrl: String?): Boolean =
    activeAttemptGeneration != null && !candidateUrl.isNullOrBlank()

internal fun isCurrentLoginAttempt(activeGeneration: Long?, expectedGeneration: Long): Boolean =
    activeGeneration == expectedGeneration

internal fun busyActionAfterPreparingLoginMethod(
    current: WearAction?,
    loginCancellationInProgress: Boolean,
): WearAction? =
    when {
      current != WearAction.Login -> current
      loginCancellationInProgress -> WearAction.Login
      else -> null
    }

internal fun isAuthenticationEventOwnedBy(
    activeAuthenticationEpoch: Long?,
    eventEpoch: Long,
): Boolean =
    activeAuthenticationEpoch != null &&
        activeAuthenticationEpoch != 0L &&
        activeAuthenticationEpoch == eventEpoch

internal data class AuthenticationRetirementOwner(
    val epoch: Long,
    val loginAttemptGeneration: Long?,
)

internal fun shouldIgnoreAuthenticationEventDuringRetirement(
    retirementOwner: AuthenticationRetirementOwner?,
    eventEpoch: Long,
): Boolean = retirementOwner?.epoch == eventEpoch

internal fun shouldSupersedeAuthenticationRetirement(
    retirementOwner: AuthenticationRetirementOwner?,
    eventEpoch: Long,
): Boolean = retirementOwner != null && eventEpoch > retirementOwner.epoch

internal fun shouldSupersedeAuthenticationOwner(
    activeAuthenticationEpoch: Long?,
    eventEpoch: Long,
): Boolean =
    activeAuthenticationEpoch != null &&
        activeAuthenticationEpoch != 0L &&
        eventEpoch > activeAuthenticationEpoch

internal data class AuthenticationSupersessionPlan(
    val supersedesRetirement: Boolean,
    val supersedesActiveOwner: Boolean,
    val supersedesRetainedCancellation: Boolean,
) {
  val supersedesAnyOwner: Boolean
    get() = supersedesRetirement || supersedesActiveOwner || supersedesRetainedCancellation
}

internal fun authenticationSupersessionPlan(
    authenticationSupersessionAllowed: Boolean,
    retirementOwner: AuthenticationRetirementOwner?,
    activeAuthenticationEpoch: Long?,
    loginCancellationInProgress: Boolean,
    retainedCancellationAuthenticationEpoch: Long?,
    eventEpoch: Long,
): AuthenticationSupersessionPlan {
  if (!authenticationSupersessionAllowed) {
    return AuthenticationSupersessionPlan(
        supersedesRetirement = false,
        supersedesActiveOwner = false,
        supersedesRetainedCancellation = false,
    )
  }
  return AuthenticationSupersessionPlan(
      supersedesRetirement = shouldSupersedeAuthenticationRetirement(retirementOwner, eventEpoch),
      supersedesActiveOwner =
          shouldSupersedeAuthenticationOwner(activeAuthenticationEpoch, eventEpoch),
      supersedesRetainedCancellation =
          loginCancellationInProgress &&
              shouldSupersedeAuthenticationOwner(
                  retainedCancellationAuthenticationEpoch, eventEpoch),
  )
}

internal fun authenticationEventMayPresentLoginUrl(event: AuthenticationEvent): Boolean =
    event.epoch != 0L && !event.browseToURL.isNullOrBlank()

internal fun shouldPresentOwnedAuthenticationUrl(
    acceptLoginUrls: Boolean,
    activeAttemptGeneration: Long?,
    activeAuthenticationEpoch: Long?,
    event: AuthenticationEvent,
): Boolean =
    acceptLoginUrls &&
        shouldAcceptLoginUrl(activeAttemptGeneration, event.browseToURL) &&
        isAuthenticationEventOwnedBy(activeAuthenticationEpoch, event.epoch) &&
        authenticationEventMayPresentLoginUrl(event)

internal fun shouldReplayOwnedBootstrapLoginUrl(
    acceptLoginUrls: Boolean,
    activeAttemptGeneration: Long?,
    activeAuthenticationEpoch: Long?,
    event: AuthenticationEvent,
): Boolean =
    event.bootstrap &&
        shouldPresentOwnedAuthenticationUrl(
            acceptLoginUrls,
            activeAttemptGeneration,
            activeAuthenticationEpoch,
            event,
        )

internal fun authenticationAdoptionBlockedByLogout(
    busyAction: WearAction?,
    hasDestructiveLogoutOwner: Boolean,
): Boolean = busyAction == WearAction.Logout || hasDestructiveLogoutOwner

internal fun authenticationRetirementIsStillOwned(
    retirementOwner: AuthenticationRetirementOwner,
    activeAuthenticationEpoch: Long?,
    activeLoginAttemptGeneration: Long?,
): Boolean =
    retirementOwner.epoch == activeAuthenticationEpoch &&
        retirementOwner.loginAttemptGeneration == activeLoginAttemptGeneration

internal enum class AuthenticationRetirementCompletion {
  ClearOwner,
  RetainForRetry,
  IgnoreSuperseded,
}

internal fun authenticationRetirementCompletion(
    retirementOwner: AuthenticationRetirementOwner,
    activeAuthenticationEpoch: Long?,
    activeLoginAttemptGeneration: Long?,
    nativeRetired: Boolean,
): AuthenticationRetirementCompletion =
    when {
      !authenticationRetirementIsStillOwned(
          retirementOwner,
          activeAuthenticationEpoch,
          activeLoginAttemptGeneration,
      ) -> AuthenticationRetirementCompletion.IgnoreSuperseded
      nativeRetired -> AuthenticationRetirementCompletion.ClearOwner
      else -> AuthenticationRetirementCompletion.RetainForRetry
    }

internal fun authenticationRetirementOwnsUiSettlement(
    expectedActionGeneration: Long?,
    currentActionGeneration: Long,
    currentBusyAction: WearAction?,
): Boolean =
    expectedActionGeneration != null &&
        expectedActionGeneration == currentActionGeneration &&
        (currentBusyAction == null || currentBusyAction == WearAction.Login)

internal enum class SuccessfulLoginForegroundSettlement {
  StopWithOff,
  TransferToNewerVpn,
  ReleaseWithoutVpnMutation,
}

internal data class SuccessfulLoginSettlementPlan(
    val ownsUiSettlement: Boolean,
    val foregroundSettlement: SuccessfulLoginForegroundSettlement,
)

/**
 * Fences every part of delayed successful-login cleanup against both UI-action and VPN-command
 * generations. A newer desired-ON value is considered a Connect owner only when its exact command
 * generation still has live VPN authority.
 */
internal fun successfulLoginSettlementPlan(
    expectedActionGeneration: Long?,
    desiredVpnGenerationBeforeRetirement: Long,
    currentActionGeneration: Long,
    currentBusyAction: WearAction?,
    currentDesiredVpnState: DesiredVpnState,
    currentVpnCommandAuthorized: Boolean,
): SuccessfulLoginSettlementPlan {
  val actionStillOwned =
      authenticationRetirementOwnsUiSettlement(
          expectedActionGeneration = expectedActionGeneration,
          currentActionGeneration = currentActionGeneration,
          currentBusyAction = currentBusyAction,
      )
  val newerExactConnect =
      currentDesiredVpnState.enabled &&
          currentDesiredVpnState.generation > desiredVpnGenerationBeforeRetirement &&
          currentVpnCommandAuthorized
  val ownsUiSettlement = actionStillOwned && !newerExactConnect
  return SuccessfulLoginSettlementPlan(
      ownsUiSettlement = ownsUiSettlement,
      foregroundSettlement =
          when {
            ownsUiSettlement -> SuccessfulLoginForegroundSettlement.StopWithOff
            newerExactConnect -> SuccessfulLoginForegroundSettlement.TransferToNewerVpn
            else -> SuccessfulLoginForegroundSettlement.ReleaseWithoutVpnMutation
          },
  )
}

internal fun successfulLoginUiStateAfterSettlement(
    state: WearUiState,
    ownsUiSettlement: Boolean,
): WearUiState =
    state.copy(
        desiredVpnEnabled = if (ownsUiSettlement) false else state.desiredVpnEnabled,
        tunnelStatus = if (ownsUiSettlement) WearTunnelStatus.Disconnected else state.tunnelStatus,
        activeLoginMethod = null,
        loginUrl = null,
        loginCode = null,
        phoneLoginStatus = PhoneLoginStatus.Idle,
        phoneLoginError = null,
        busyAction =
            if (ownsUiSettlement && state.busyAction == WearAction.Login) null
            else state.busyAction,
        error =
            if (ownsUiSettlement &&
                state.error?.operation == WearAction.Login &&
                state.error.message == LOGIN_RETIREMENT_FAILURE_MESSAGE) {
              null
            } else {
              state.error
            },
    )

internal fun wearTunnelStatus(
    ipnState: Ipn.State,
    vpnActive: Boolean,
    busyAction: WearAction?,
    desiredVpnEnabled: Boolean,
): WearTunnelStatus =
    when {
      ipnState == Ipn.State.Stopping ||
          busyAction == WearAction.Disconnect ||
          (!desiredVpnEnabled && vpnActive) -> WearTunnelStatus.Disconnecting
      ipnState == Ipn.State.Running && vpnActive -> WearTunnelStatus.Connected
      ipnState == Ipn.State.Starting -> WearTunnelStatus.Connecting
      busyAction == WearAction.Connect -> WearTunnelStatus.Connecting
      desiredVpnEnabled -> WearTunnelStatus.Connecting
      ipnState == Ipn.State.NoState -> WearTunnelStatus.Unknown
      else -> WearTunnelStatus.Disconnected
    }

internal fun shouldCancelLoginMethodOnRouteDisposal(
    activityRecreationInProgress: Boolean,
    disposedMethod: LoginMethod,
    activeMethod: LoginMethod?,
    activeAttemptGeneration: Long?,
    accountState: WearAccountState,
    preserveAuthenticatedContinuation: Boolean,
): Boolean =
    !activityRecreationInProgress &&
        disposedMethod == activeMethod &&
        activeAttemptGeneration != null &&
        !(preserveAuthenticatedContinuation &&
            (accountState == WearAccountState.NeedsMachineAuthorization ||
                accountState == WearAccountState.SignedIn))

/** Independent ownership for destructive profile cleanup; shared UI generations cannot clear it. */
internal class LogoutMutationGate {
  private var ownerGeneration: Long? = null

  @Synchronized
  fun begin(generation: Long): Boolean {
    if (ownerGeneration != null) return false
    ownerGeneration = generation
    return true
  }

  @Synchronized fun blocksProfileMutation(): Boolean = ownerGeneration != null

  @Synchronized fun allowsAuthenticationSupersession(): Boolean = ownerGeneration == null

  @Synchronized fun owns(generation: Long): Boolean = ownerGeneration == generation

  @Synchronized
  fun finish(generation: Long): Boolean {
    if (ownerGeneration != generation) return false
    ownerGeneration = null
    return true
  }
}

internal fun authenticationEventProvesReady(
    activeAuthenticationEpoch: Long?,
    event: AuthenticationEvent,
): Boolean =
    !event.bootstrap &&
        isAuthenticationEventOwnedBy(activeAuthenticationEpoch, event.epoch) &&
        (event.loginFinished || (event.state != null && event.state > Ipn.State.NeedsMachineAuth))

internal fun hasOwnedAuthenticationReadyEvidence(
    activeAuthenticationEpoch: Long?,
    readyAuthenticationEpoch: Long?,
): Boolean =
    activeAuthenticationEpoch != null &&
        activeAuthenticationEpoch != 0L &&
        activeAuthenticationEpoch == readyAuthenticationEpoch

internal fun <Lease> handleLoginForegroundPromotion(
    activeGeneration: Long?,
    expectedGeneration: Long,
    lease: Lease,
    adopt: (Lease) -> Unit,
    release: (Lease) -> Unit,
): Boolean {
  if (!isCurrentLoginAttempt(activeGeneration, expectedGeneration)) {
    release(lease)
    return false
  }
  adopt(lease)
  return true
}

internal fun shouldUseLocalOnlyLogout(networkStatus: UnderlyingNetworkStatus): Boolean =
    networkStatus != UnderlyingNetworkStatus.Online

internal data class OptionalNotificationState(
    val requested: Boolean,
    val effective: Boolean,
    val blockedBySystem: Boolean,
)

internal fun resolveOptionalNotificationState(
    requested: Boolean,
    systemPermissionGranted: Boolean,
): OptionalNotificationState =
    OptionalNotificationState(
        requested = requested,
        effective = requested && systemPermissionGranted,
        blockedBySystem = requested && !systemPermissionGranted,
    )

internal enum class ConnectionControlAction {
  Connect,
  Disconnect,
  OpenVpnSetup,
}

internal fun resolveConnectionControlAction(
    vpnAuthorization: VpnAuthorization,
    desiredVpnEnabled: Boolean,
    errorOperation: WearAction?,
): ConnectionControlAction =
    when {
      vpnAuthorization != VpnAuthorization.Granted -> ConnectionControlAction.OpenVpnSetup
      errorOperation == WearAction.Disconnect && !desiredVpnEnabled ->
          ConnectionControlAction.Disconnect
      desiredVpnEnabled -> ConnectionControlAction.Disconnect
      else -> ConnectionControlAction.Connect
    }

internal data class LatestIntentDispatch<T>(val generation: Long, val value: T)

internal data class LatestIntentSubmission<T>(
    val request: LatestIntentDispatch<T>,
    val dispatchNow: LatestIntentDispatch<T>?,
)

internal data class LatestIntentCompletion<T>(
    val accepted: Boolean,
    val completedLatest: Boolean,
    val next: LatestIntentDispatch<T>?,
)

/**
 * Serializes async writes while retaining only the newest intent that has not started yet.
 *
 * The latest desired value remains available until the backend reports it, preventing an older
 * callback or snapshot from visually undoing a newer user choice.
 */
internal class LatestIntentSerialQueue<T> {
  private var nextGeneration = 0L
  private var latestGeneration = 0L
  private var inFlight: LatestIntentDispatch<T>? = null
  private var pending: LatestIntentDispatch<T>? = null
  private var expectedValue: T? = null
  private var hasExpectedValue = false
  private var expectedValueObserved = false

  @Synchronized
  fun submit(value: T): LatestIntentSubmission<T> {
    val dispatch = LatestIntentDispatch(generation = ++nextGeneration, value = value)
    latestGeneration = dispatch.generation
    expectedValue = value
    hasExpectedValue = true
    expectedValueObserved = false
    if (inFlight == null) {
      inFlight = dispatch
      return LatestIntentSubmission(request = dispatch, dispatchNow = dispatch)
    }
    pending = dispatch
    return LatestIntentSubmission(request = dispatch, dispatchNow = null)
  }

  @Synchronized
  fun complete(generation: Long, success: Boolean): LatestIntentCompletion<T> {
    val current = inFlight
    if (current == null || current.generation != generation) {
      return LatestIntentCompletion(accepted = false, completedLatest = false, next = null)
    }
    val nextDispatch = pending
    pending = null
    inFlight = nextDispatch
    val completedLatest = nextDispatch == null && generation == latestGeneration
    if (completedLatest && (!success || expectedValueObserved)) clearExpectedValue()
    return LatestIntentCompletion(
        accepted = true,
        completedLatest = completedLatest,
        next = nextDispatch,
    )
  }

  @Synchronized
  fun observe(value: T) {
    if (!hasExpectedValue || expectedValue != value) return
    if (inFlight == null && pending == null) {
      clearExpectedValue()
    } else {
      expectedValueObserved = true
    }
  }

  @Synchronized fun optimisticValue(): T? = if (hasExpectedValue) expectedValue else null

  @Synchronized
  fun clear() {
    inFlight = null
    pending = null
    clearExpectedValue()
  }

  private fun clearExpectedValue() {
    expectedValue = null
    hasExpectedValue = false
    expectedValueObserved = false
  }
}

internal data class ExitPreferencesIntent(val exitNodeId: String?, val allowLanAccess: Boolean)

internal data class ConfirmedExitPreferences(
    val selectedExitNodeId: String?,
    val activeExitNodeId: String?,
    val allowLanAccess: Boolean,
)

internal fun resolveConfirmedExitPreferences(
    live: ConfirmedExitPreferences?,
    profileCache: ConfirmedExitPreferences?,
): ConfirmedExitPreferences =
    live
        ?: profileCache
        ?: ConfirmedExitPreferences(
            selectedExitNodeId = null,
            activeExitNodeId = null,
            allowLanAccess = false,
        )

private fun Ipn.Prefs.confirmedExitPreferences(): ConfirmedExitPreferences =
    ConfirmedExitPreferences(
        selectedExitNodeId = selectedExitNodeID,
        activeExitNodeId = activeExitNodeID,
        allowLanAccess = ExitNodeAllowLANAccess,
    )

internal fun projectBackendExitPreferences(
    state: WearUiState,
    selectedExitNodeId: String?,
    activeExitNodeId: String?,
    allowLanAccess: Boolean,
): WearUiState {
  val selectedNode = state.exitNodes.firstOrNull { it.stableId == selectedExitNodeId }
  val activeNode = state.exitNodes.firstOrNull { it.stableId == activeExitNodeId }
  return state.copy(
      selectedExitNodeId = selectedExitNodeId,
      selectedExitNodeName = selectedNode?.displayName,
      activeExitNodeId = activeExitNodeId,
      activeExitNodeName = activeNode?.displayName,
      allowLanAccess = activeExitNodeId != null && allowLanAccess,
      exitNodes =
          state.exitNodes.map { node ->
            node.copy(
                selected = node.stableId == selectedExitNodeId,
                active = node.stableId == activeExitNodeId,
            )
          },
  )
}

private data class ExitPreferencesActionOwner(
    val queueGeneration: Long,
    val action: WearAction,
    val actionGeneration: Long,
)

internal data class PhoneLoginOpenAttempt(
    val token: Long,
    val loginAttemptGeneration: Long,
    val url: String,
)

internal class PhoneLoginOpenCoordinator {
  private var nextToken = 0L
  private var activeAttempt: PhoneLoginOpenAttempt? = null

  @Synchronized
  fun begin(url: String, loginAttemptGeneration: Long): PhoneLoginOpenAttempt? {
    val active = activeAttempt
    if (active?.url == url && active.loginAttemptGeneration == loginAttemptGeneration) return null
    return PhoneLoginOpenAttempt(
            token = ++nextToken,
            loginAttemptGeneration = loginAttemptGeneration,
            url = url,
        )
        .also { activeAttempt = it }
  }

  @Synchronized
  fun finish(attempt: PhoneLoginOpenAttempt): Boolean {
    return clear(attempt)
  }

  @Synchronized
  fun cancel(attempt: PhoneLoginOpenAttempt): Boolean {
    return clear(attempt)
  }

  private fun clear(attempt: PhoneLoginOpenAttempt): Boolean {
    if (activeAttempt != attempt) return false
    activeAttempt = null
    return true
  }
}

private fun displayNameCollator(language: TailwareLanguage): Collator =
    Collator.getInstance(Locale.forLanguageTag(language.languageTag)).apply {
      strength = Collator.PRIMARY
    }

internal fun localizedPeerComparator(language: TailwareLanguage): Comparator<WearPeer> {
  val collator = displayNameCollator(language)
  return compareByDescending<WearPeer> { it.isSelf }
      .thenByDescending { it.online }
      .then(Comparator { left, right -> collator.compare(left.displayName, right.displayName) })
      .thenBy { it.stableId }
}

internal fun localizedExitNodeComparator(language: TailwareLanguage): Comparator<WearExitNode> {
  val collator = displayNameCollator(language)
  return compareByDescending<WearExitNode> { it.active }
      .thenByDescending { it.selected }
      .thenByDescending { it.online }
      .then(Comparator { left, right -> collator.compare(left.displayName, right.displayName) })
      .thenBy { it.stableId }
}

internal fun isWearAccountReady(ipnState: Ipn.State, hasProfile: Boolean): Boolean =
    hasProfile && ipnState > Ipn.State.NeedsMachineAuth

internal fun resolveWearAccountState(
    ipnState: Ipn.State,
    hasProfile: Boolean,
    hasLoginUrl: Boolean,
    hasActiveLoginAttempt: Boolean,
): WearAccountState =
    when {
      ipnState == Ipn.State.NeedsMachineAuth ->
          if (hasProfile || hasLoginUrl || hasActiveLoginAttempt) {
            WearAccountState.NeedsMachineAuthorization
          } else {
            WearAccountState.SignedOut
          }
      // NeedsLogin is authoritative even while LocalAPI still exposes the old profile. This is
      // common during key expiry and reauthentication.
      ipnState == Ipn.State.NeedsLogin ->
          if (hasLoginUrl || hasActiveLoginAttempt) {
            WearAccountState.Authenticating
          } else {
            WearAccountState.SignedOut
          }
      isWearAccountReady(ipnState, hasProfile) -> WearAccountState.SignedIn
      hasLoginUrl || hasActiveLoginAttempt -> WearAccountState.Authenticating
      else -> WearAccountState.Loading
    }

internal fun shouldRecoverMachineAuthCancellation(
    accountState: WearAccountState,
    activeLoginAttemptGeneration: Long?,
): Boolean =
    accountState == WearAccountState.NeedsMachineAuthorization &&
        activeLoginAttemptGeneration == null

internal fun shouldArmLoginCompletionTimeout(
    loginUrl: String?,
    accountState: WearAccountState,
): Boolean = loginUrl.isNullOrBlank() && accountState != WearAccountState.NeedsMachineAuthorization

internal fun shouldCancelLoginCompletionTimeout(
    busyAction: WearAction?,
    loginUrl: String?,
    accountState: WearAccountState,
): Boolean =
    busyAction == WearAction.Login && !shouldArmLoginCompletionTimeout(loginUrl, accountState)

internal suspend fun <T> runBoundedLoginCleanup(
    quiescence: Job?,
    timeoutMillis: Long,
    cleanup: suspend () -> Result<T>,
): Result<T> =
    try {
      withTimeout(timeoutMillis) {
        quiescence?.join()
        cleanup()
      }
    } catch (_: TimeoutCancellationException) {
      Result.failure(
          IllegalStateException(
              "The previous sign-in attempt could not be cleaned up in time. Try again."))
    } catch (error: CancellationException) {
      throw error
    }

internal suspend fun <T> resumeLoginCleanupAfterRetainedQuiescence(
    quiescence: Job,
    timeoutMillis: Long,
    cleanup: suspend () -> Result<T>,
): Result<T> =
    try {
      withTimeout(timeoutMillis) { quiescence.join() }
      cleanup()
    } catch (_: TimeoutCancellationException) {
      Result.failure(
          IllegalStateException(
              "The previous sign-in attempt could not be cleaned up in time. Try again."))
    } catch (error: CancellationException) {
      throw error
    }

/**
 * Gives every LocalAPI request spawned by a machine-auth cleanup the timeout scope as its owner.
 *
 * LocalAPI eventually calls into synchronous gomobile code. Cancellation cannot interrupt that
 * native call, but structured concurrency still waits for its child job to return before this
 * function completes. A retry therefore cannot overlap an older GET or DELETE that is still
 * physically executing.
 */
internal suspend fun <T> runOwnedMachineAuthCleanupIo(
    timeoutMillis: Long,
    cleanup: suspend CoroutineScope.() -> T,
): Result<T> =
    try {
      Result.success(withTimeout(timeoutMillis) { cleanup() })
    } catch (_: TimeoutCancellationException) {
      Result.failure(
          IllegalStateException(
              "The previous sign-in attempt could not be cleaned up in time. Try again."))
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      Result.failure(error)
    }

/**
 * Awaits an app-scoped destructive mutation even after the caller's timeout has fired.
 *
 * [App.setWantRunning] deliberately owns its PATCH in the application scope so it can reconcile a
 * stale command with the newest persisted VPN generation. Cleanup must keep that behavior, but it
 * must not return and retry while the application-scoped PATCH (or its reconciliation PATCH) is
 * still running. Making only this bridge non-cancellable keeps the outer cleanup deadline while
 * retaining ownership until the terminal callback.
 */
internal suspend fun <T> awaitCleanupMutationCompletion(
    start: (((Result<T>) -> Unit) -> Unit)
): Result<T> =
    withContext(NonCancellable) {
      suspendCoroutine { continuation -> start { result -> continuation.resume(result) } }
    }

internal fun exactForegroundCleanupReleaseSucceeded(
    hadLease: Boolean,
    dispatched: Boolean,
): Boolean = hadLease && dispatched

internal fun canReleaseAfterAwaitedBackendCleanup(
    authenticationEpoch: Long?,
    awaitedStopGeneration: Long,
    pendingCleanupGeneration: Long,
): Boolean = authenticationEpoch != null || pendingCleanupGeneration <= awaitedStopGeneration

/** A caller timeout may fail cleanup, but it must never skip the post-cancellation profile read. */
internal suspend fun <C, V> runWithMandatoryPostCancellationVerification(
    cancellation: suspend () -> C,
    verification: suspend CoroutineScope.() -> V,
): Pair<C, V> {
  val cancellationResult =
      try {
        Result.success(cancellation())
      } catch (error: CancellationException) {
        Result.failure(error)
      }
  val verificationResult = withContext(NonCancellable) { runCatching { verification(this) } }
  cancellationResult.exceptionOrNull()?.let { throw it }
  return cancellationResult.getOrThrow() to verificationResult.getOrThrow()
}

internal fun shouldDeletePendingMachineAuthProfile(
    expectedProfileId: String?,
    currentSelectedProfileId: String?,
    pendingProfileExists: Boolean,
    backendState: Ipn.State,
    exactLoginOwnershipKnown: Boolean = false,
    allowedNonPendingSelectedProfileIds: Set<String> = emptySet(),
): Boolean =
    !expectedProfileId.isNullOrBlank() &&
        pendingProfileExists &&
        (currentSelectedProfileId == null ||
            currentSelectedProfileId == expectedProfileId ||
            currentSelectedProfileId in allowedNonPendingSelectedProfileIds) &&
        (exactLoginOwnershipKnown ||
            backendState == Ipn.State.NeedsLogin ||
            backendState == Ipn.State.NeedsMachineAuth)

internal fun machineAuthProfileCleanupPostconditionSatisfied(
    expectedProfileId: String?,
    beforeCurrentSelectedProfileId: String?,
    listedProfileIds: Set<String>,
    afterCurrentSelectedProfileId: String?,
): Boolean =
    !expectedProfileId.isNullOrBlank() &&
        profileReadIsCoherent(
            beforeCurrentId = beforeCurrentSelectedProfileId,
            listedProfileIds = listedProfileIds,
            afterCurrentId = afterCurrentSelectedProfileId,
        ) &&
        afterCurrentSelectedProfileId != expectedProfileId &&
        expectedProfileId !in listedProfileIds

internal data class ProfileCleanupVerification(
    val beforeCurrentProfileId: String?,
    val listedProfileIds: Set<String>,
    val afterCurrentProfileId: String?,
)

internal data class CoherentProfileRead<T : Any>(
    val beforeCurrent: T?,
    val profiles: List<T>,
    val afterCurrent: T?,
)

internal suspend fun <T : Any> readCoherentProfileSnapshot(
    attempts: Int,
    readCurrent: suspend () -> Result<T?>,
    readProfiles: suspend () -> Result<List<T>>,
    profileId: (T) -> String,
): Result<CoherentProfileRead<T>> =
    try {
      require(attempts > 0)
      repeat(attempts) {
        val before = readCurrent().getOrThrow()
        val profiles = readProfiles().getOrThrow()
        val after = readCurrent().getOrThrow()
        if (profileReadIsCoherent(
            beforeCurrentId = before?.let(profileId),
            listedProfileIds = profiles.mapTo(mutableSetOf(), profileId),
            afterCurrentId = after?.let(profileId),
        )) {
          return Result.success(CoherentProfileRead(before, profiles, after))
        }
      }
      Result.failure(
          IllegalStateException(
              "Account changed while Tailware was verifying cancelled sign-in cleanup."))
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      Result.failure(error)
    }

/** DELETE errors, including a retry's 404, are clean only when this exact ID is freshly absent. */
internal suspend fun deleteExactProfileWithFreshVerification(
    expectedProfileId: String,
    delete: suspend () -> Result<Unit>,
    freshVerification: suspend () -> Result<ProfileCleanupVerification>,
): Result<Unit> =
    try {
      val deleteResult = delete()
      val verification = freshVerification().getOrThrow()
      if (!machineAuthProfileCleanupPostconditionSatisfied(
          expectedProfileId = expectedProfileId,
          beforeCurrentSelectedProfileId = verification.beforeCurrentProfileId,
          listedProfileIds = verification.listedProfileIds,
          afterCurrentSelectedProfileId = verification.afterCurrentProfileId,
      )) {
        // Preserve the DELETE diagnostic when the mutation itself failed. Otherwise report the
        // stronger exact-profile postcondition failure.
        deleteResult.getOrThrow()
        error("The account created by the cancelled sign-in is still present.")
      }
      Result.success(Unit)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      Result.failure(error)
    }

internal fun selectProfileForLogout(
    displayedProfileId: String?,
    currentProfile: IpnLocal.LoginProfile?,
): IpnLocal.LoginProfile? {
  if (displayedProfileId.isNullOrBlank()) return null
  return currentProfile?.takeIf { it.ID == displayedProfileId }
}

internal data class LoginProfileBaseline(
    val currentProfileId: String?,
    val profileIds: Set<String>,
) {
  init {
    require(currentProfileId == null || currentProfileId in profileIds)
  }
}

internal data class LoginOwnedMachineAuthProfileResolution(
    val profileId: String?,
    val ownershipIsSafe: Boolean,
)

internal fun resolveLoginOwnedMachineAuthProfile(
    beforeCancellationCurrentProfileId: String?,
    afterCancellationCurrentProfileId: String?,
    afterCancellationProfileIds: Set<String>,
    profileBaseline: LoginProfileBaseline?,
    retainedOwnedProfileId: String? = null,
): LoginOwnedMachineAuthProfileResolution {
  if (retainedOwnedProfileId != null) {
    return LoginOwnedMachineAuthProfileResolution(
        profileId = retainedOwnedProfileId,
        ownershipIsSafe = profileBaseline?.profileIds?.contains(retainedOwnedProfileId) != true,
    )
  }

  if (profileBaseline == null) {
    val noProfileCanBelongToTheCancelledLogin =
        beforeCancellationCurrentProfileId == null &&
            afterCancellationCurrentProfileId == null &&
            afterCancellationProfileIds.isEmpty()
    return LoginOwnedMachineAuthProfileResolution(
        profileId = null,
        ownershipIsSafe = noProfileCanBelongToTheCancelledLogin,
    )
  }

  val newProfileIds = afterCancellationProfileIds - profileBaseline.profileIds
  val beforeCandidate = beforeCancellationCurrentProfileId?.takeIf { it in newProfileIds }
  val afterCandidate = afterCancellationCurrentProfileId?.takeIf { it in newProfileIds }
  if (beforeCandidate != null && afterCandidate != null && beforeCandidate != afterCandidate) {
    return LoginOwnedMachineAuthProfileResolution(profileId = null, ownershipIsSafe = false)
  }
  if (beforeCandidate != null) {
    return LoginOwnedMachineAuthProfileResolution(
        profileId = beforeCandidate,
        ownershipIsSafe = true,
    )
  }
  if (afterCandidate != null) {
    return LoginOwnedMachineAuthProfileResolution(
        profileId = afterCandidate.takeIf { newProfileIds == setOf(afterCandidate) },
        ownershipIsSafe = newProfileIds == setOf(afterCandidate),
    )
  }
  return LoginOwnedMachineAuthProfileResolution(
      profileId = newProfileIds.singleOrNull(),
      ownershipIsSafe = newProfileIds.size <= 1,
  )
}

internal fun pingFailureCategory(diagnostic: String?): PingFailureCategory {
  val detail = diagnostic?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
  return when {
    detail.contains("timeout", ignoreCase = true) ||
        detail.contains("deadline exceeded", ignoreCase = true) -> PingFailureCategory.TimedOut
    detail.isBlank() -> PingFailureCategory.NoDiagnostic
    else -> PingFailureCategory.BackendFailure
  }
}

/**
 * Wear-specific application state and actions layered on the existing Android Tailscale backend.
 *
 * This class contains no Compose or Activity code. Android's current VpnService state is the only
 * authoritative authorization signal.
 */
class TailwareViewModel internal constructor(private val app: App) : IpnViewModel() {
  private data class RetainedLoginCleanup(
      val authenticationEpoch: Long?,
      val exactLoginAttemptKnown: Boolean,
      val profileBaseline: LoginProfileBaseline?,
      val profileCleanupRequired: Boolean,
      var machineAuthorizationCleanup: Boolean,
      var authenticationCancellationVerified: Boolean = false,
      var ownedProfileId: String? = null,
  )

  private val appViewModel = app.getAppScopedViewModel()
  private val localPreferences = app.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
  private val initialOptionalNotificationState = currentOptionalNotificationState()
  private val initialLanguage =
      TailwareStrings.selectedLanguage(app).also {
        val preferenceValue = TailwareStrings.preferenceValue(app)
        if (localPreferences.getString(KEY_LANGUAGE, null) != preferenceValue) {
          localPreferences.edit { putString(KEY_LANGUAGE, preferenceValue) }
        }
      }
  private var actionGeneration = 0L
  private var busyTimeoutJob: Job? = null
  private var acceptLoginUrls = false
  private var loginAttemptGeneration = 0L
  private var activeLoginAttemptGeneration: Long? = null
  private var activeAuthenticationEpoch: Long? = null
  private var readyAuthenticationEpoch: Long? = null
  private var pendingAuthenticationRetirement: AuthenticationRetirementOwner? = null
  private var authenticationRetirementInFlight = false
  private var loginProfileBaseline: LoginProfileBaseline? = null
  private var retainedLoginCleanup: RetainedLoginCleanup? = null
  private var loginRequiresProfilePostcondition = false
  private var activeLoginCapability: Long? = null
  private var loginCancellationInProgress = false
  private var loginCancellationGeneration = 0L
  private var loginCleanupRequired = false
  private var machineAuthProfileCleanupRequired = false
  private var loginForegroundProtectionActive = false
  private var activityRecreationInProgress = false
  private var activeLoginVpnSettlementRequestSequence: Long? = null

  private data class PendingLoginStart(val capability: Long, val start: () -> Boolean)

  private var pendingLoginStart: PendingLoginStart? = null
  private var postCleanupError: WearError? = null
  private val phoneLoginOpenCoordinator = PhoneLoginOpenCoordinator()
  private val logoutMutationGate = LogoutMutationGate()
  private var logoutProfile: IpnLocal.LoginProfile? = null
  private var logoutCompletionNotice: WearError? = null
  private val exitPreferencesQueue = LatestIntentSerialQueue<ExitPreferencesIntent>()
  private var latestExitPreferencesAction: ExitPreferencesActionOwner? = null
  private var exitPreferencesProfileId: String? = null
  private var lastConfirmedExitPreferences: ConfirmedExitPreferences? = null

  private fun replacePendingLoginStart(capability: Long, start: () -> Boolean) {
    discardPendingLoginStart()
    pendingLoginStart = PendingLoginStart(capability, start)
  }

  private fun takePendingLoginStart(): PendingLoginStart? =
      pendingLoginStart.also { pendingLoginStart = null }

  private fun discardPendingLoginStart() {
    pendingLoginStart?.capability?.let(TailwareActivationCapability::revokeLoginCapability)
    pendingLoginStart = null
  }

  private fun launchPendingLoginStart(pending: PendingLoginStart?) {
    if (pending != null && !pending.start()) {
      TailwareActivationCapability.revokeLoginCapability(pending.capability)
    }
  }

  private val _uiState =
      MutableStateFlow(
          WearUiState(
              language = initialLanguage,
              desiredVpnEnabled = TailwareVpnStateStore.desiredVpnEnabled(app),
              optionalNotificationsRequested = initialOptionalNotificationState.requested,
              optionalNotificationsBlockedBySystem =
                  initialOptionalNotificationState.blockedBySystem,
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
    if (!TailwareStrings.followsSystemLanguage(app)) {
      applyAppLocale(_uiState.value.language)
    }
    // LoginFinished is modeled upstream as state even though it is an event. Never replay a
    // completion from an older Activity/ViewModel into a new login attempt.
    Notifier.loginFinished.set(null)
    observeBackend()
    observeAuthenticationEvents()
    observeVpnServiceErrors()
  }

  override fun onAuthenticationEpochStarted(epoch: Long) {
    pendingAuthenticationRetirement
        ?.takeIf { owner -> owner.epoch != epoch }
        ?.let {
          // Beginning a newer native epoch is authoritative proof that the older retirement owner
          // was superseded. Its in-flight completion is ignored by exact token comparison.
          pendingAuthenticationRetirement = null
          authenticationRetirementInFlight = false
        }
    if (activeLoginAttemptGeneration == null || loginCancellationInProgress) {
      viewModelScope.launch(Dispatchers.IO) { Notifier.finishAuthentication(epoch) }
      return
    }
    activeAuthenticationEpoch = epoch
    readyAuthenticationEpoch = null
    Notifier.authenticationEvent.replayCache
        .lastOrNull()
        ?.takeIf { event ->
          shouldReplayOwnedBootstrapLoginUrl(
              acceptLoginUrls = acceptLoginUrls,
              activeAttemptGeneration = activeLoginAttemptGeneration,
              activeAuthenticationEpoch = activeAuthenticationEpoch,
              event = event,
          )
        }
        ?.let(::presentOwnedAuthenticationUrl)
  }

  override suspend fun prepareAuthenticationEpoch(): Result<Unit> {
    if (!loginRequiresProfilePostcondition) return Result.success(Unit)
    val requestScope = CoroutineScope(currentCoroutineContext())
    return coherentMachineAuthProfileSnapshot(requestScope).map { snapshot ->
      loginProfileBaseline = snapshot.asLoginProfileBaseline()
    }
  }

  private fun observeBackend() {
    val healthWarnings =
        app.healthNotifier?.currentWarnings ?: flowOf<Set<Health.UnhealthyState>>(emptySet())
    viewModelScope.launch {
      combine(Notifier.state, Notifier.netmap, Notifier.prefs, loginProfilesSnapshot) {
              ipnState,
              netmap,
              prefs,
              profileSnapshot ->
            BackendSnapshot(
                ipnState,
                netmap,
                prefs,
                profileSnapshot.current,
                profileSnapshot.profiles,
            )
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

  private fun observeAuthenticationEvents() {
    viewModelScope.launch {
      Notifier.authenticationEvent.filterNotNull().collect { event ->
        val supersededOlderOwner = supersedeAuthenticationOwnerIfNewer(event.epoch)
        if (shouldIgnoreAuthenticationEventDuringRetirement(
            pendingAuthenticationRetirement,
            event.epoch,
        )) {
          return@collect
        }
        val url = event.browseToURL.takeIf { authenticationEventMayPresentLoginUrl(event) }
        var adoptedBackendOwner = false
        if (url != null &&
            activeLoginAttemptGeneration == null &&
            !loginCancellationInProgress &&
            !loginCleanupRequired &&
            !authenticationAdoptionBlockedByLogout(
                busyAction = _uiState.value.busyAction,
                hasDestructiveLogoutOwner =
                    logoutMutationGate.blocksProfileMutation() || logoutProfile != null,
            ) &&
            (Notifier.state.value == Ipn.State.NeedsLogin ||
                Notifier.state.value == Ipn.State.NeedsMachineAuth)) {
          adoptedBackendOwner = beginBackendReauthentication(event.epoch)
        }
        if (supersededOlderOwner && !adoptedBackendOwner) {
          releaseSupersededAuthenticationForeground()
        }

        if (!isAuthenticationEventOwnedBy(activeAuthenticationEpoch, event.epoch)) {
          return@collect
        }

        if (authenticationEventProvesReady(activeAuthenticationEpoch, event)) {
          readyAuthenticationEpoch = event.epoch
        }

        presentOwnedAuthenticationUrl(event)

        // A replacement watcher's first notification can be the only copy of an in-flight URL.
        // It is safe to present that exact owned URL, but bootstrap state/error/terminal fields are
        // replay rather than fresh completion evidence and must not settle the attempt.
        if (event.bootstrap) return@collect

        event.errMessage?.let { backendMessage ->
          handleOwnedLoginError(backendMessage)
          return@collect
        }

        if (event.loginFinished) {
          acceptLoginUrls = false
          busyTimeoutJob?.cancel()
          Notifier.browseToURL.set(null)
          Notifier.loginFinished.set(null)
          _uiState.update {
            it.copy(
                loginUrl = null,
                loginCode = null,
                phoneLoginStatus = PhoneLoginStatus.Idle,
                phoneLoginError = null,
            )
          }
        }

        if (hasOwnedAuthenticationReadyEvidence(
            activeAuthenticationEpoch,
            readyAuthenticationEpoch,
        ) && _uiState.value.accountState == WearAccountState.SignedIn) {
          activeLoginAttemptGeneration?.let(::finishSuccessfulLogin)
        }
      }
    }
  }

  private fun supersedeAuthenticationOwnerIfNewer(eventEpoch: Long): Boolean {
    val owner = pendingAuthenticationRetirement
    val supersession =
        authenticationSupersessionPlan(
            authenticationSupersessionAllowed =
                logoutMutationGate.allowsAuthenticationSupersession(),
            retirementOwner = owner,
            activeAuthenticationEpoch = activeAuthenticationEpoch,
            loginCancellationInProgress = loginCancellationInProgress,
            retainedCancellationAuthenticationEpoch = retainedLoginCleanup?.authenticationEpoch,
            eventEpoch = eventEpoch,
        )
    // A native epoch that appears after Logout starts is not allowed to invalidate its callback
    // fences. The event collector rejects its URL/adoption while this owner remains active.
    if (!supersession.supersedesAnyOwner) return false
    val supersedesRetirement = supersession.supersedesRetirement
    val supersedesActiveOwner = supersession.supersedesActiveOwner
    val supersedesRetainedCancellation = supersession.supersedesRetainedCancellation

    if (supersedesRetirement) {
      pendingAuthenticationRetirement = null
      authenticationRetirementInFlight = false
    }
    if (supersedesRetainedCancellation) {
      // The cleanup coroutine checks this generation before every remaining backend mutation.
      // Invalidating it also prevents its terminal callback from settling the replacement epoch.
      loginCancellationGeneration += 1L
      loginCancellationInProgress = false
      loginCleanupRequired = false
      retainedLoginCleanup = null
      machineAuthProfileCleanupRequired = false
      discardPendingLoginStart()
      postCleanupError = null
    }
    if (supersedesActiveOwner || supersedesRetainedCancellation) {
      invalidateLoginCallbacks()
      activeLoginCapability?.let(TailwareActivationCapability::revokeLoginCapability)
      activeLoginCapability = null
      activeLoginAttemptGeneration = null
      activeAuthenticationEpoch = null
      readyAuthenticationEpoch = null
      loginProfileBaseline = null
      retainedLoginCleanup = null
      loginRequiresProfilePostcondition = false
      acceptLoginUrls = false
      if (_uiState.value.busyAction == WearAction.Login) {
        actionGeneration += 1L
        busyTimeoutJob?.cancel()
      }
      _uiState.update { state ->
        state.copy(
            activeLoginMethod = null,
            loginUrl = null,
            loginCode = null,
            phoneLoginStatus = PhoneLoginStatus.Idle,
            phoneLoginError = null,
            busyAction = if (state.busyAction == WearAction.Login) null else state.busyAction,
        )
      }
    }
    return supersedesActiveOwner || supersedesRetainedCancellation
  }

  private fun presentOwnedAuthenticationUrl(event: AuthenticationEvent) {
    if (!shouldPresentOwnedAuthenticationUrl(
        acceptLoginUrls = acceptLoginUrls,
        activeAttemptGeneration = activeLoginAttemptGeneration,
        activeAuthenticationEpoch = activeAuthenticationEpoch,
        event = event,
    )) {
      return
    }
    val url = checkNotNull(event.browseToURL)
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

  private fun releaseSupersededAuthenticationForeground() {
    if (!loginForegroundProtectionActive && !hasLoginForegroundProtection()) return
    val release = releaseLoginForegroundProtection()
    loginForegroundProtectionActive = hasLoginForegroundProtection()
    if (release.hadLease && !release.dispatched) {
      TSLog.w("TailwareViewModel", "Unable to release superseded sign-in foreground ownership")
    }
  }

  private fun observeVpnServiceErrors() {
    var lastVpnServiceError: String? = null
    viewModelScope.launch {
      appViewModel.vpnServiceError.collect { message ->
        if (message == null) {
          val clearedMessage = lastVpnServiceError
          lastVpnServiceError = null
          if (clearedMessage != null) {
            _uiState.update { state ->
              if (state.error?.origin == WearErrorOrigin.Global &&
                  state.error.message == clearedMessage) {
                state.copy(error = null)
              } else {
                state
              }
            }
          }
          return@collect
        }
        lastVpnServiceError = message
        _uiState.update {
          it.copy(
              desiredVpnEnabled = TailwareVpnStateStore.desiredVpnEnabled(app),
              tunnelStatus = WearTunnelStatus.Disconnected,
              busyAction = if (it.busyAction == WearAction.Connect) null else it.busyAction,
              error =
                  WearError(
                      operation = WearAction.Connect,
                      message = message,
                      origin = WearErrorOrigin.Global,
                  ),
          )
        }
      }
    }
  }

  private fun handleOwnedLoginError(backendMessage: String) {
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

    val attemptGeneration = activeLoginAttemptGeneration ?: return
    startLoginCancellation(
        WearError(operation = WearAction.Login, message = message, origin = origin),
        expectedAttemptGeneration = attemptGeneration,
    )
  }

  private fun applyBackendSnapshot(snapshot: BackendSnapshot) {
    val hasProfile = snapshot.profile != null && !snapshot.profile.isEmpty()
    val snapshotProfileId = snapshot.profile?.ID?.takeIf { hasProfile }
    if (snapshotProfileId != exitPreferencesProfileId) {
      exitPreferencesQueue.clear()
      latestExitPreferencesAction = null
      lastConfirmedExitPreferences = null
      exitPreferencesProfileId = snapshotProfileId
    }
    val liveExitPreferences = snapshot.prefs?.confirmedExitPreferences().takeIf { hasProfile }
    if (liveExitPreferences != null) {
      lastConfirmedExitPreferences = liveExitPreferences
      exitPreferencesQueue.observe(
          ExitPreferencesIntent(
              exitNodeId = liveExitPreferences.activeExitNodeId,
              allowLanAccess = liveExitPreferences.allowLanAccess,
          ))
    }
    val confirmedExitPreferences =
        resolveConfirmedExitPreferences(
            live = liveExitPreferences,
            profileCache = lastConfirmedExitPreferences.takeIf { hasProfile },
        )
    val requestedExitPreferences = exitPreferencesQueue.optimisticValue()
    val signedInSnapshot = isWearAccountReady(snapshot.ipnState, hasProfile)
    val ownedAuthenticationReady =
        hasOwnedAuthenticationReadyEvidence(activeAuthenticationEpoch, readyAuthenticationEpoch)
    val acceptedSignedInSnapshot =
        signedInSnapshot &&
            !loginCancellationInProgress &&
            !loginCleanupRequired &&
            (activeLoginAttemptGeneration == null || ownedAuthenticationReady)
    val completedLoginGeneration =
        activeLoginAttemptGeneration?.takeIf { signedInSnapshot && ownedAuthenticationReady }
    if (acceptedSignedInSnapshot) {
      acceptLoginUrls = false
      if (Notifier.browseToURL.value != null) Notifier.browseToURL.set(null)
      if (Notifier.loginFinished.value != null) Notifier.loginFinished.set(null)
    }
    _uiState.update { current ->
      val resolvedAccountState =
          if (signedInSnapshot &&
              (loginCancellationInProgress ||
                  loginCleanupRequired ||
                  (activeLoginAttemptGeneration != null && !ownedAuthenticationReady))) {
            WearAccountState.Authenticating
          } else {
            accountState(snapshot, current)
          }
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
      val selectedExitNodeId =
          if (requestedExitPreferences != null) requestedExitPreferences.exitNodeId
          else confirmedExitPreferences.selectedExitNodeId
      val activeExitNodeId =
          if (requestedExitPreferences != null) requestedExitPreferences.exitNodeId
          else confirmedExitPreferences.activeExitNodeId
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
              .sortedWith(localizedExitNodeComparator(current.language))
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
                  .map { it.toWearPeer(snapshot.netmap) }
                  .sortedWith(localizedPeerComparator(current.language)),
          exitNodes = exitNodeModels,
          selectedExitNodeId = selectedExitNodeId,
          selectedExitNodeName = selectedExitNode?.displayName,
          activeExitNodeId = activeExitNodeId,
          activeExitNodeName = activeExitNode?.displayName,
          allowLanAccess =
              hasProfile &&
                  (requestedExitPreferences?.allowLanAccess
                      ?: confirmedExitPreferences.allowLanAccess),
          tailscaleDnsEnabled =
              resolvedAccountState == WearAccountState.SignedIn && snapshot.prefs?.CorpDNS == true,
          tailscaleSettingsReady =
              resolvedAccountState == WearAccountState.SignedIn && snapshot.prefs != null,
          underlyingNetworkStatus = snapshot.underlyingNetworkStatus,
          healthWarnings = healthWarnings,
          loginUrl = if (acceptedSignedInSnapshot) null else current.loginUrl,
          loginCode = if (acceptedSignedInSnapshot) null else current.loginCode,
          activeLoginMethod = if (acceptedSignedInSnapshot) null else current.activeLoginMethod,
          phoneLoginStatus =
              if (acceptedSignedInSnapshot) PhoneLoginStatus.Idle else current.phoneLoginStatus,
          phoneLoginError = if (acceptedSignedInSnapshot) null else current.phoneLoginError,
          busyAction = settledBusyAction(current.busyAction, snapshot),
      )
    }
    _uiState.value.let { current ->
      if (shouldCancelLoginCompletionTimeout(
          busyAction = current.busyAction,
          loginUrl = current.loginUrl,
          accountState = current.accountState,
      )) {
        busyTimeoutJob?.cancel()
      }
    }
    completedLoginGeneration?.let(::finishSuccessfulLogin)
  }

  private fun accountState(snapshot: BackendSnapshot, current: WearUiState): WearAccountState =
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
  ): WearTunnelStatus = wearTunnelStatus(ipnState, vpnActive, busyAction, desiredVpnEnabled)

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
                  impactsConnectivity = true,
              )
          UnderlyingNetworkStatus.Limited ->
              WearHealthWarning(
                  code = "tailware-underlay-limited",
                  severity = WearHealthSeverity.Medium,
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
                  impactsConnectivity = true,
                  subjectName = it.displayName,
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
                .thenBy { it.code })
  }

  private fun settledBusyAction(busyAction: WearAction?, snapshot: BackendSnapshot): WearAction? =
      when (busyAction) {
        WearAction.Connect ->
            if (snapshot.ipnState == Ipn.State.Running && snapshot.vpnActive) null else busyAction
        WearAction.Disconnect ->
            // Native IPN may reach Stopping/Stopped before Android receives exact descriptor-close
            // acknowledgement. IPNService keeps vpnActive true until that proof arrives.
            if (!snapshot.vpnActive) null else busyAction
        WearAction.Login ->
            when {
              loginCancellationInProgress -> busyAction
              snapshot.profile != null &&
                  hasOwnedAuthenticationReadyEvidence(
                      activeAuthenticationEpoch,
                      readyAuthenticationEpoch,
                  ) &&
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
   * Checks Android's VPN authorization only from a direct Retry or Connect gesture.
   *
   * `VpnService.prepare() == null` is the only authoritative "granted" signal. Wear OS does not
   * provide a supported VPN confirmation flow, so every non-null result requires the one-time ADB
   * bootstrap. Activity resume, composition, boot, Tile refresh, and backend observation must never
   * call this method.
   */
  fun checkVpnAuthorizationFromUserGesture(context: Context): VpnAuthorization {
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
    val vpnCapability = TailwareActivationCapability.grantVpnFromUserGesture(app)
    if (vpnCapability == TailwareActivationCapability.NO_CAPABILITY) {
      setError(WearAction.Connect, "Tailware safety containment could not authorize this start.")
      return
    }
    if (checkVpnAuthorizationFromUserGesture(app) != VpnAuthorization.Granted) {
      TailwareActivationCapability.revokeVpnCapability(vpnCapability)
      setError(WearAction.Connect, "VPN authorization is required before Tailware can connect.")
      return
    }

    appViewModel.setVpnServiceError(null)
    val generation = startBusyAction(WearAction.Connect)
    _uiState.update { it.copy(desiredVpnEnabled = true) }
    val dispatch = app.startVPNCommand(vpnCapability)
    if (!dispatch.dispatched) {
      val reset =
          dispatch.failedStartRollback
              ?: try {
                TailwareVpnStateStore.setDesiredVpnEnabledIfCurrent(app, dispatch.command, false)
              } catch (error: VpnStatePersistenceException) {
                app.containVpnStatePersistenceFailure(error)
              }
      if (reset == null) {
        val newerDesired = TailwareVpnStateStore.desiredVpnState(app)
        updateForAction(WearAction.Connect, generation) {
          it.copy(desiredVpnEnabled = newerDesired.enabled, busyAction = null)
        }
        return
      }
      _uiState.update {
        it.copy(desiredVpnEnabled = false, tunnelStatus = WearTunnelStatus.Disconnected)
      }
      completeFailure(
          WearAction.Connect,
          IllegalStateException("Android did not allow Tailware to start the VPN service."),
          generation,
      )
      TailwareActivationCapability.revokeVpnCapability(vpnCapability)
      return
    }
    clearBusyAfterTimeout(WearAction.Connect, generation)
  }

  fun disconnect() {
    val vpnCapability = TailwareActivationCapability.currentVpnCapability()
    val generation = startBusyAction(WearAction.Disconnect)
    // Preserve evidence from before stopVPNCommand persists OFF. Once desired state is OFF, the
    // in-memory successor can no longer prove that a service/TUN still needs exact-owner cleanup.
    _uiState.update { it.copy(desiredVpnEnabled = false) }
    app.stopVPNCommandAsync { dispatch ->
      viewModelScope.launch {
        if (dispatch.superseded) {
          val currentDesired = TailwareVpnStateStore.desiredVpnState(app)
          updateForAction(WearAction.Disconnect, generation) {
            it.copy(desiredVpnEnabled = currentDesired.enabled, busyAction = null)
          }
          return@launch
        }
        if (!dispatch.dispatched && !dispatch.stopSafelyHandled) {
          val currentDesired = TailwareVpnStateStore.desiredVpnState(app)
          if (currentDesired != dispatch.command) {
            updateForAction(WearAction.Disconnect, generation) {
              it.copy(desiredVpnEnabled = currentDesired.enabled, busyAction = null)
            }
            return@launch
          }
          completeFailure(
              WearAction.Disconnect,
              IllegalStateException("Android did not allow Tailware to stop the VPN service."),
              generation,
          )
        }
        // Successful dispatch is not successful teardown. AppViewModel.vpnActive remains true and
        // the UI remains Disconnecting until IPNService receives exact native acknowledgement.
      }
    }
    TailwareActivationCapability.revokeVpnCapability(vpnCapability)
    clearBusyAfterTimeout(WearAction.Disconnect, generation)
  }

  internal fun performConnectionControl(action: ConnectionControlAction) {
    when (action) {
      ConnectionControlAction.Connect -> connect()
      ConnectionControlAction.Disconnect -> disconnect()
      // Navigation owns this action; keeping it exhaustive here prevents accidental VPN mutation
      // if a stale UI callback reaches the ViewModel after authorization changed.
      ConnectionControlAction.OpenVpnSetup -> Unit
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
    if (logoutMutationGate.blocksProfileMutation()) return
    if (_uiState.value.activeLoginMethod == method) return
    val currentBusyAction = _uiState.value.busyAction
    val preparedBusyAction =
        busyActionAfterPreparingLoginMethod(currentBusyAction, loginCancellationInProgress)
    if (currentBusyAction == WearAction.Login && preparedBusyAction == null) {
      busyTimeoutJob?.cancel()
    }
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
          busyAction = preparedBusyAction,
      )
    }
  }

  fun startInteractiveLogin(presentation: LoginPresentation = LoginPresentation.QrCode): Boolean {
    if (logoutMutationGate.blocksProfileMutation()) return false
    if (loginCancellationInProgress) return false
    if (_uiState.value.busyAction == WearAction.Login) return false
    val loginCapability = TailwareActivationCapability.grantLoginFromUserGesture(app)
    if (loginCapability == TailwareActivationCapability.NO_CAPABILITY) {
      setError(WearAction.Login, "Tailware safety containment could not authorize sign-in.")
      return false
    }
    return startInteractiveLogin(presentation, loginCapability).also { started ->
      if (!started) TailwareActivationCapability.revokeLoginCapability(loginCapability)
    }
  }

  private fun startInteractiveLogin(
      presentation: LoginPresentation,
      loginCapability: Long,
  ): Boolean {
    if (logoutMutationGate.blocksProfileMutation()) return false
    if (!TailwareActivationCapability.acceptsLoginCapability(loginCapability)) return false
    if (loginCleanupRequired) {
      replacePendingLoginStart(loginCapability) {
        startInteractiveLogin(presentation, loginCapability)
      }
      startLoginCancellation()
      return true
    }
    activeLoginCapability
        ?.takeIf { it != loginCapability }
        ?.let(TailwareActivationCapability::revokeLoginCapability)
    activeLoginCapability = loginCapability
    val generation = beginLoginAttempt(LoginMethod.Tailscale, presentation)
    loginForegroundProtectionActive = true
    login(
        maskedPrefs = Ipn.MaskedPrefs().apply { ControlURL = Links.DEFAULT_CONTROL_URL },
        useForegroundService = true,
        retainForegroundOnFailure = true,
        loginCapability = loginCapability,
    ) { result ->
      completeUnitAction(WearAction.Login, result, generation)
    }
    return true
  }

  fun startAuthKeyLogin(authKey: String): Boolean {
    if (logoutMutationGate.blocksProfileMutation()) return false
    if (loginCancellationInProgress) return false
    if (_uiState.value.busyAction == WearAction.Login) return false
    if (authKey.isBlank()) {
      setError(WearAction.Login, "Enter an authentication key.", WearErrorOrigin.AuthenticationKey)
      return false
    }
    val loginCapability = TailwareActivationCapability.grantLoginFromUserGesture(app)
    if (loginCapability == TailwareActivationCapability.NO_CAPABILITY) {
      setError(
          WearAction.Login,
          "Tailware safety containment could not authorize sign-in.",
          WearErrorOrigin.AuthenticationKey,
      )
      return false
    }
    return startAuthKeyLogin(authKey.trim(), loginCapability).also { started ->
      if (!started) TailwareActivationCapability.revokeLoginCapability(loginCapability)
    }
  }

  private fun startAuthKeyLogin(authKey: String, loginCapability: Long): Boolean {
    if (logoutMutationGate.blocksProfileMutation()) return false
    if (!TailwareActivationCapability.acceptsLoginCapability(loginCapability)) return false
    if (loginCleanupRequired) {
      replacePendingLoginStart(loginCapability) { startAuthKeyLogin(authKey, loginCapability) }
      startLoginCancellation()
      return true
    }
    activeLoginCapability
        ?.takeIf { it != loginCapability }
        ?.let(TailwareActivationCapability::revokeLoginCapability)
    activeLoginCapability = loginCapability
    val generation = beginLoginAttempt(LoginMethod.AuthenticationKey, LoginPresentation.QrCode)
    loginForegroundProtectionActive = true
    login(
        maskedPrefs = Ipn.MaskedPrefs().apply { ControlURL = Links.DEFAULT_CONTROL_URL },
        authKey = authKey,
        useForegroundService = true,
        retainForegroundOnFailure = true,
        loginCapability = loginCapability,
    ) { result ->
      completeUnitAction(WearAction.Login, result, generation)
    }
    return true
  }

  fun startCustomControlLogin(controlUrl: String): Boolean {
    if (logoutMutationGate.blocksProfileMutation()) return false
    if (loginCancellationInProgress) return false
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
    val loginCapability = TailwareActivationCapability.grantLoginFromUserGesture(app)
    if (loginCapability == TailwareActivationCapability.NO_CAPABILITY) {
      setError(
          WearAction.Login,
          "Tailware safety containment could not authorize sign-in.",
          WearErrorOrigin.CustomServer,
      )
      return false
    }
    return startCustomControlLogin(normalized, loginCapability).also { started ->
      if (!started) TailwareActivationCapability.revokeLoginCapability(loginCapability)
    }
  }

  private fun startCustomControlLogin(normalized: String, loginCapability: Long): Boolean {
    if (logoutMutationGate.blocksProfileMutation()) return false
    if (!TailwareActivationCapability.acceptsLoginCapability(loginCapability)) return false
    if (loginCleanupRequired) {
      replacePendingLoginStart(loginCapability) {
        startCustomControlLogin(normalized, loginCapability)
      }
      startLoginCancellation()
      return true
    }
    activeLoginCapability
        ?.takeIf { it != loginCapability }
        ?.let(TailwareActivationCapability::revokeLoginCapability)
    activeLoginCapability = loginCapability
    val generation = beginLoginAttempt(LoginMethod.CustomServer, LoginPresentation.QrCode)
    loginForegroundProtectionActive = true
    loginWithCustomControlURL(
        normalized,
        useForegroundService = true,
        retainForegroundOnFailure = true,
        loginCapability = loginCapability,
    ) { result ->
      completeUnitAction(WearAction.Login, result, generation)
    }
    return true
  }

  fun cancelLoginIfActive(method: LoginMethod, preserveAuthenticatedContinuation: Boolean = false) {
    if (logoutMutationGate.blocksProfileMutation()) return
    val current = _uiState.value
    val generation = activeLoginAttemptGeneration ?: return
    if (!shouldCancelLoginMethodOnRouteDisposal(
        activityRecreationInProgress = activityRecreationInProgress,
        disposedMethod = method,
        activeMethod = current.activeLoginMethod,
        activeAttemptGeneration = generation,
        accountState = current.accountState,
        preserveAuthenticatedContinuation = preserveAuthenticatedContinuation,
    )) {
      return
    }
    cancelInteractiveLogin(generation)
  }

  fun regenerateInteractiveLogin(presentation: LoginPresentation) {
    if (logoutMutationGate.blocksProfileMutation()) return
    if (loginCancellationInProgress) return
    val loginCapability = TailwareActivationCapability.grantLoginFromUserGesture(app)
    if (loginCapability == TailwareActivationCapability.NO_CAPABILITY) return
    replacePendingLoginStart(loginCapability) {
      startInteractiveLogin(presentation, loginCapability)
    }
    val generation = activeLoginAttemptGeneration
    if (generation == null) {
      launchPendingLoginStart(takePendingLoginStart())
    } else {
      startLoginCancellation(expectedAttemptGeneration = generation)
    }
  }

  fun cancelInteractiveLogin() {
    if (logoutMutationGate.blocksProfileMutation()) return
    if (activityRecreationInProgress) return
    if (_uiState.value.accountState == WearAccountState.NeedsMachineAuthorization) {
      machineAuthProfileCleanupRequired = true
      // The exact owner is bound from a fresh LocalAPI current-profile read inside cleanup.
      loginCleanupRequired = true
    }
    val generation = activeLoginAttemptGeneration
    if (generation != null) {
      cancelInteractiveLogin(generation)
      return
    }
    if (!shouldRecoverMachineAuthCancellation(_uiState.value.accountState, generation)) return

    // A process restart can restore NeedsMachineAuth without the in-memory owner. Cancellation may
    // still stop the backend, but profile removal fails closed unless cleanup can prove an exact
    // profile against a retained coherent baseline.
    machineAuthProfileCleanupRequired = true
    loginCleanupRequired = true
    startLoginCancellation(expectedAttemptGeneration = null)
  }

  private fun cancelInteractiveLogin(expectedAttemptGeneration: Long) {
    discardPendingLoginStart()
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
      launchPendingLoginStart(takePendingLoginStart())
      return
    }
    postCleanupError = errorAfterCleanup
    // Invalidate and cancel the callback-based LocalAPI chain before cleanup starts. Waiting for
    // its scoped requests to quiesce ensures an old editPrefs/start/login-interactive request
    // cannot
    // mutate the backend after cleanup has already completed.
    val loginOperationQuiescence = invalidateLoginCallbacks()
    loginCancellationInProgress = true
    loginCleanupRequired = true
    loginCancellationGeneration += 1
    val cancellationGeneration = loginCancellationGeneration
    val cleanupOwnership =
        retainedLoginCleanup?.also { retained ->
          if (machineAuthProfileCleanupRequired) {
            retained.machineAuthorizationCleanup = true
          }
        }
            ?: RetainedLoginCleanup(
                    authenticationEpoch = activeAuthenticationEpoch,
                    exactLoginAttemptKnown = activeLoginAttemptGeneration != null,
                    profileBaseline = loginProfileBaseline,
                    profileCleanupRequired =
                        loginRequiresProfilePostcondition && activeAuthenticationEpoch != null,
                    machineAuthorizationCleanup = machineAuthProfileCleanupRequired,
                )
                .also { retainedLoginCleanup = it }
    activeLoginAttemptGeneration = null
    activeAuthenticationEpoch = null
    readyAuthenticationEpoch = null
    loginProfileBaseline = null
    loginRequiresProfilePostcondition = false
    acceptLoginUrls = false
    Notifier.browseToURL.set(null)
    Notifier.loginFinished.set(null)
    Notifier.errMessage.set(null)
    // Persist OFF immediately so reboot cannot resurrect the login's half-started tunnel, but keep
    // the foreground service alive until callback quiescence and LocalBackend cleanup complete.
    val stopCommand = app.persistVpnStopCommand()
    _uiState.update { it.copy(desiredVpnEnabled = false) }
    val cleanupActionGeneration = startBusyAction(WearAction.Login, clearLogin = true)
    val cleanUpMachineAuthProfile = cleanupOwnership.machineAuthorizationCleanup
    viewModelScope.launch {
      val cleanupAttempt: suspend () -> Result<Boolean> = {
        cleanUpCancelledLogin(
            stopCommand = stopCommand,
            ownership = cleanupOwnership,
            cancellationGeneration = cancellationGeneration,
        )
      }
      var result =
          runBoundedLoginCleanup(
              quiescence = loginOperationQuiescence,
              timeoutMillis =
                  if (cleanupOwnership.profileCleanupRequired) {
                    LOGIN_CLEANUP_TIMEOUT_MILLIS * 2
                  } else {
                    LOGIN_CLEANUP_TIMEOUT_MILLIS
                  },
              cleanup = cleanupAttempt,
          )
      if (result.isFailure &&
          loginOperationQuiescence != null &&
          isCurrentLoginCancellation(cancellationGeneration)) {
        // A gomobile request can outlive coroutine cancellation until its own 30-second deadline.
        // Keep the foreground lease and the cancellation owner while awaiting that same retained
        // Job, then make one fresh, idempotent cleanup attempt. Only the terminal result below may
        // release foreground protection or permit a new login.
        result =
            resumeLoginCleanupAfterRetainedQuiescence(
                quiescence = loginOperationQuiescence,
                timeoutMillis = LOGIN_OPERATION_STRAGGLER_TIMEOUT_MILLIS,
                cleanup = cleanupAttempt,
            )
      }
      if (isCurrentLoginCancellation(cancellationGeneration)) {
        completeLoginCancellation(
            result = result.map { Unit },
            generation = cancellationGeneration,
            cleanupActionGeneration = cleanupActionGeneration,
            cleanupAuthenticationEpoch = cleanupOwnership.authenticationEpoch,
            backendStopAwaitedThroughGeneration = stopCommand.generation,
            machineAuthorizationCleanupAttempted = cleanUpMachineAuthProfile,
            cleanedUpMachineAuthorization = result.getOrNull() == true,
        )
      }
    }
  }

  private suspend fun cleanUpCancelledLogin(
      stopCommand: DesiredVpnState,
      ownership: RetainedLoginCleanup,
      cancellationGeneration: Long,
  ): Result<Boolean> =
      runOwnedMachineAuthCleanupIo(timeoutMillis = LOGIN_CLEANUP_TIMEOUT_MILLIS * 2) cleanup@{
        check(isCurrentLoginCancellation(cancellationGeneration)) {
          "The cancelled sign-in cleanup was superseded by a newer authentication session."
        }
        ownership.authenticationEpoch
            ?.takeIf { it != 0L }
            ?.let { epoch ->
              check(!Notifier.hasNewerAuthenticationEpoch(epoch)) {
                "The cancelled sign-in cleanup no longer owns the authentication session."
              }
            }
        ensureLoginCleanupForegroundProtection(cancellationGeneration).getOrThrow()
        // Every Client below uses this timeout scope. If a synchronous gomobile request outlives
        // cancellation, structured concurrency keeps this cleanup as its sole owner and a retry
        // cannot begin until that request has physically returned.
        val requestScope = this
        val beforeCancellationCurrent =
            currentMachineAuthProfile(
                    expectedProfileId = null,
                    allowCached = false,
                    requestScope = requestScope,
                    authenticationEpoch = ownership.authenticationEpoch,
                )
                .getOrThrow()

        // Even when exact epoch cancellation or an outer deadline fails, finish the mandatory
        // fresh coherent read before surfacing the error. Its NonCancellable scope owns every
        // current/list/current child until the native request has physically returned.
        val (backendCancellation, afterCancellationResult) =
            runWithMandatoryPostCancellationVerification(
                cancellation = {
                  cleanUpLoginAttempt(stopCommand, ownership, cancellationGeneration)
                },
                verification = {
                  coherentMachineAuthProfileSnapshot(this, ownership.authenticationEpoch)
                },
            )
        val afterCancellation = afterCancellationResult.getOrThrow()
        backendCancellation.getOrThrow()

        if (!ownership.profileCleanupRequired) {
          return@cleanup ownership.machineAuthorizationCleanup
        }
        check(isCurrentLoginCancellation(cancellationGeneration)) {
          "The cancelled sign-in cleanup was superseded before profile verification."
        }

        val profileOwnership =
            resolveLoginOwnedMachineAuthProfile(
                beforeCancellationCurrentProfileId = beforeCancellationCurrent?.ID,
                afterCancellationCurrentProfileId = afterCancellation.current?.ID,
                afterCancellationProfileIds = afterCancellation.profileIds,
                profileBaseline = ownership.profileBaseline,
                retainedOwnedProfileId = ownership.ownedProfileId,
            )
        check(profileOwnership.ownershipIsSafe) {
          "Tailware could not prove which account belongs to the cancelled sign-in."
        }

        val pendingProfileId = profileOwnership.profileId
        if (pendingProfileId == null) {
          // No profile outside the coherent pre-login baseline exists. In the restored-process
          // case, this is safe only when the fresh snapshot is completely empty.
          return@cleanup ownership.machineAuthorizationCleanup
        }
        ownership.ownedProfileId = pendingProfileId

        val pendingProfile =
            afterCancellation.profiles.firstOrNull { profile -> profile.ID == pendingProfileId }
        if (pendingProfile == null) {
          check(
              machineAuthProfileCleanupPostconditionSatisfied(
                  expectedProfileId = pendingProfileId,
                  beforeCurrentSelectedProfileId = afterCancellation.beforeCurrent?.ID,
                  listedProfileIds = afterCancellation.profileIds,
                  afterCurrentSelectedProfileId = afterCancellation.current?.ID,
              )) {
                "The cancelled sign-in profile snapshot is internally inconsistent."
              }
          // Disconnect (or an earlier timed-out DELETE) already removed the exact profile.
          return@cleanup ownership.machineAuthorizationCleanup
        }

        val backendState =
            currentBackendState(requestScope, ownership.authenticationEpoch).getOrThrow()
        check(isCurrentLoginCancellation(cancellationGeneration)) {
          "The cancelled sign-in cleanup was superseded before profile removal."
        }
        check(
            shouldDeletePendingMachineAuthProfile(
                expectedProfileId = pendingProfileId,
                currentSelectedProfileId = afterCancellation.current?.ID,
                pendingProfileExists = true,
                backendState = backendState,
                // The candidate is accepted only after pre/post reads agree and after comparison
                // with the captured baseline. That is stronger ownership evidence than backend
                // state, which may already have advanced after late administrator approval.
                exactLoginOwnershipKnown = true,
                allowedNonPendingSelectedProfileIds =
                    ownership.profileBaseline?.profileIds.orEmpty(),
            )) {
              "A different account became active while Tailware was cancelling sign-in."
            }

        deleteExactProfileWithFreshVerification(
                expectedProfileId = pendingProfileId,
                delete = {
                  deleteLocalProfile(
                      profile = pendingProfile,
                      requestScope = requestScope,
                      authenticationEpoch = ownership.authenticationEpoch,
                  )
                },
                freshVerification = {
                  coherentMachineAuthProfileSnapshot(requestScope, ownership.authenticationEpoch)
                      .map { it.verification }
                },
            )
            .getOrThrow()

        // DELETE is idempotent from the user's perspective: even a transport/error callback is
        // success when a fresh coherent read proves the exact profile is absent.
        ownership.machineAuthorizationCleanup
      }

  private suspend fun ensureLoginCleanupForegroundProtection(
      cancellationGeneration: Long
  ): Result<Unit> {
    if (hasLoginForegroundProtection()) return Result.success(Unit)
    val loginCapability =
        activeLoginCapability?.takeIf(TailwareActivationCapability::acceptsLoginCapability)
            ?: pendingLoginStart
                ?.capability
                ?.takeIf(TailwareActivationCapability::acceptsLoginCapability)
            ?: return Result.failure(
                SecurityException("The interactive login cleanup capability expired."))
    val lease =
        app.startForegroundForLogin(loginCapability).getOrElse {
          return Result.failure(it)
        }
    if (!isCurrentLoginCancellation(cancellationGeneration)) {
      app.releaseForegroundForLogin(lease, backendAlreadyStopped = true)
      return Result.failure(
          IllegalStateException(
              "The cancelled sign-in cleanup was superseded during foreground promotion."))
    }
    // A newly acknowledged request supersedes any provisional request whose exact release could
    // not be dispatched. Terminal cleanup can therefore require and release one concrete lease.
    adoptLoginForeground(lease)
    loginForegroundProtectionActive = true
    return Result.success(Unit)
  }

  private data class CoherentMachineAuthProfileSnapshot(
      val beforeCurrent: IpnLocal.LoginProfile?,
      val current: IpnLocal.LoginProfile?,
      val profiles: List<IpnLocal.LoginProfile>,
  ) {
    val profileIds: Set<String> = profiles.mapTo(mutableSetOf()) { it.ID }
    val verification: ProfileCleanupVerification
      get() =
          ProfileCleanupVerification(
              beforeCurrentProfileId = beforeCurrent?.ID,
              listedProfileIds = profileIds,
              afterCurrentProfileId = current?.ID,
          )

    fun asLoginProfileBaseline(): LoginProfileBaseline =
        LoginProfileBaseline(currentProfileId = current?.ID, profileIds = profileIds)
  }

  private suspend fun coherentMachineAuthProfileSnapshot(
      requestScope: CoroutineScope,
      authenticationEpoch: Long? = null,
  ): Result<CoherentMachineAuthProfileSnapshot> =
      readCoherentProfileSnapshot(
              attempts = MACHINE_AUTH_PROFILE_COHERENCE_ATTEMPTS,
              readCurrent = {
                currentMachineAuthProfile(
                    expectedProfileId = null,
                    allowCached = false,
                    requestScope = requestScope,
                    authenticationEpoch = authenticationEpoch,
                )
              },
              readProfiles = { machineAuthProfiles(requestScope, authenticationEpoch) },
              profileId = { profile -> profile.ID },
          )
          .map { read ->
            CoherentMachineAuthProfileSnapshot(
                beforeCurrent = read.beforeCurrent,
                current = read.afterCurrent,
                profiles = read.profiles,
            )
          }

  private suspend fun currentMachineAuthProfile(
      expectedProfileId: String? = null,
      allowCached: Boolean = true,
      requestScope: CoroutineScope = viewModelScope,
      authenticationEpoch: Long? = null,
  ): Result<IpnLocal.LoginProfile?> {
    if (allowCached) {
      val current = _uiState.value
      val snapshot = loginProfilesSnapshot.value
      val cached =
          sequenceOf(
                  snapshot.current,
                  snapshot.profiles?.firstOrNull { profile -> profile.ID == expectedProfileId },
                  snapshot.profiles?.firstOrNull { profile -> profile.ID == current.profileId },
              )
              .filterNotNull()
              .firstOrNull { profile ->
                expectedProfileId == null || profile.ID == expectedProfileId
              }
      if (cached != null) return Result.success(cached)
    }

    if (!expectedProfileId.isNullOrBlank()) {
      return suspendCancellableCoroutine { continuation ->
        Client(requestScope, authenticationEpoch).profiles { result ->
          if (continuation.isActive) {
            continuation.resume(
                result.map { profiles ->
                  profiles.firstOrNull { profile -> profile.ID == expectedProfileId }
                })
          }
        }
      }
    }

    return suspendCancellableCoroutine { continuation ->
      Client(requestScope, authenticationEpoch).currentProfile { result ->
        if (continuation.isActive) {
          continuation.resume(result.map { profile -> profile.takeUnless { it.isEmpty() } })
        }
      }
    }
  }

  private suspend fun machineAuthProfiles(
      requestScope: CoroutineScope,
      authenticationEpoch: Long? = null,
  ): Result<List<IpnLocal.LoginProfile>> = suspendCancellableCoroutine { continuation ->
    Client(requestScope, authenticationEpoch).profiles { result ->
      if (continuation.isActive) continuation.resume(result)
    }
  }

  private suspend fun currentBackendState(
      requestScope: CoroutineScope = viewModelScope,
      authenticationEpoch: Long? = null,
  ): Result<Ipn.State> = suspendCancellableCoroutine { continuation ->
    Client(requestScope, authenticationEpoch).status { result ->
      if (continuation.isActive) {
        continuation.resume(
            result.map { status ->
              Ipn.State.entries.firstOrNull { it.name == status.BackendState }
                  ?: Notifier.state.value
            })
      }
    }
  }

  private suspend fun deleteLocalProfile(
      profile: IpnLocal.LoginProfile,
      requestScope: CoroutineScope = viewModelScope,
      authenticationEpoch: Long? = null,
  ): Result<Unit> = suspendCancellableCoroutine { continuation ->
    val startDelete = {
      Client(requestScope, authenticationEpoch).deleteProfile(profile) { result ->
        if (continuation.isActive) continuation.resume(result.map { Unit })
      }
    }
    val started =
        if (authenticationEpoch != null && authenticationEpoch != 0L) {
          Notifier.runIfNoNewerAuthenticationEpoch(authenticationEpoch, startDelete)
        } else {
          startDelete()
          true
        }
    if (!started && continuation.isActive) {
      continuation.resume(
          Result.failure(
              IllegalStateException(
                  "The cancelled sign-in was superseded before profile removal.")))
    }
  }

  private suspend fun cleanUpLoginAttempt(
      stopCommand: DesiredVpnState,
      ownership: RetainedLoginCleanup,
      cancellationGeneration: Long,
  ): Result<Unit> =
      try {
        withTimeout(LOGIN_CLEANUP_TIMEOUT_MILLIS) {
          if (!ownership.authenticationCancellationVerified) {
            withContext(NonCancellable + Dispatchers.IO) {
              // DisconnectControl atomically clears the pending AuthURL without changing the
              // selected profile or node keys. Never synthesize an epoch after process restore:
              // doing so could replace and cancel a newer authentication owner.
              val epoch = ownership.authenticationEpoch
              val cancellationVerified =
                  when {
                    epoch != null && epoch != 0L -> Notifier.cancelAuthentication(epoch)
                    ownership.exactLoginAttemptKnown -> true
                    else -> false
                  }
              check(cancellationVerified) {
                "The cancelled sign-in no longer owns the active authentication session."
              }
              ownership.authenticationCancellationVerified = true
            }
          }
          check(isCurrentLoginCancellation(cancellationGeneration)) {
            "The cancelled sign-in cleanup was superseded before backend shutdown."
          }
          setBackendWantRunningForCleanup(
                  wantRunning = false,
                  commandOwner = stopCommand,
                  authenticationEpoch = ownership.authenticationEpoch,
              )
              .getOrThrow()
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

  private suspend fun setBackendWantRunningForCleanup(
      wantRunning: Boolean,
      commandOwner: DesiredVpnState,
      authenticationEpoch: Long?,
  ): Result<Unit> = awaitCleanupMutationCompletion { complete ->
    val success = { complete(Result.success(Unit)) }
    app.setWantRunning(
        wantRunning = wantRunning,
        commandOwner = commandOwner,
        authenticationEpoch = authenticationEpoch,
        onSuccess = success,
        // If a newer desired-state writer wins while this PATCH is in flight, App first reasserts
        // that newer value and only then completes cleanup as safely superseded. Native execution
        // still requires this exact authentication sequence throughout each PATCH.
        onSuperseded = success,
        onFailure = { error -> complete(Result.failure(error)) },
    )
  }

  internal fun onPhoneLoginOpening(url: String): PhoneLoginOpenAttempt? {
    val current = _uiState.value
    val attemptGeneration = activeLoginAttemptGeneration ?: return null
    if (current.loginUrl != url || current.phoneLoginStatus == PhoneLoginStatus.Opening) return null
    val attempt = phoneLoginOpenCoordinator.begin(url, attemptGeneration) ?: return null
    if (!isCurrentLoginAttempt(activeLoginAttemptGeneration, attemptGeneration) ||
        _uiState.value.loginUrl != url) {
      phoneLoginOpenCoordinator.finish(attempt)
      return null
    }
    _uiState.update { it.copy(phoneLoginStatus = PhoneLoginStatus.Opening, phoneLoginError = null) }
    return attempt
  }

  internal fun onPhoneLoginOpened(attempt: PhoneLoginOpenAttempt) {
    if (!phoneLoginOpenCoordinator.finish(attempt) ||
        !isCurrentLoginAttempt(activeLoginAttemptGeneration, attempt.loginAttemptGeneration) ||
        _uiState.value.loginUrl != attempt.url) {
      return
    }
    _uiState.update { it.copy(phoneLoginStatus = PhoneLoginStatus.Sent, phoneLoginError = null) }
  }

  internal fun onPhoneLoginLaunchFailed(attempt: PhoneLoginOpenAttempt, error: Throwable) {
    if (!phoneLoginOpenCoordinator.finish(attempt) ||
        !isCurrentLoginAttempt(activeLoginAttemptGeneration, attempt.loginAttemptGeneration) ||
        _uiState.value.loginUrl != attempt.url) {
      return
    }
    _uiState.update {
      it.copy(
          phoneLoginStatus = PhoneLoginStatus.Failed,
          phoneLoginError =
              "Could not open the sign-in page on the paired phone: ${error.readableMessage()}",
      )
    }
  }

  internal fun onPhoneLoginLaunchCancelled(attempt: PhoneLoginOpenAttempt) {
    if (!phoneLoginOpenCoordinator.cancel(attempt) ||
        !isCurrentLoginAttempt(activeLoginAttemptGeneration, attempt.loginAttemptGeneration) ||
        _uiState.value.loginUrl != attempt.url) {
      return
    }
    _uiState.update { it.copy(phoneLoginStatus = PhoneLoginStatus.Idle, phoneLoginError = null) }
  }

  fun clearPhoneLoginError() {
    _uiState.update { it.copy(phoneLoginStatus = PhoneLoginStatus.Idle, phoneLoginError = null) }
  }

  fun logoutAccount() {
    val current = _uiState.value
    if (logoutMutationGate.blocksProfileMutation() || current.busyAction == WearAction.Logout)
        return
    if (authenticationRetirementInFlight) {
      setError(
          WearAction.Logout,
          "The previous sign-in attempt could not be cleaned up in time. Try again.",
      )
      return
    }
    val displayedProfileId = current.profileId
    if (displayedProfileId.isNullOrBlank()) {
      setError(
          WearAction.Logout,
          "Account details are still loading. Wait a moment and try signing out again.",
      )
      return
    }
    // Capture only the newest epoch Android had already observed when Logout began. A native login
    // that wins afterward advances the sequence and makes every destructive LocalAPI call fail
    // closed; Logout must never adopt that concurrent attempt as its own fence.
    val logoutAuthenticationSequence =
        activeAuthenticationEpoch ?: Notifier.authenticationMutationSequence()
    val generation = startBusyAction(WearAction.Logout)
    check(logoutMutationGate.begin(generation)) { "A destructive Logout owner is already active." }
    // A queued replacement login must not launch from an older cancellation callback while this
    // independent profile-cleanup owner is still being verified.
    discardPendingLoginStart()
    clearBusyAfterTimeout(WearAction.Logout, generation, LOGOUT_TIMEOUT_MILLIS)
    val authenticationEpoch = activeAuthenticationEpoch
    if (authenticationEpoch != null && authenticationEpoch != 0L) {
      val owner =
          AuthenticationRetirementOwner(
              epoch = authenticationEpoch,
              loginAttemptGeneration = activeLoginAttemptGeneration,
          )
      retireAuthenticationOwner(
          owner = owner,
          onRetired = {
            // Native acknowledgement retires this exact authentication owner even if the Logout
            // action timed out or was superseded while the callback fence was draining. Only the
            // destructive profile mutation remains guarded by the action generation.
            activeLoginAttemptGeneration = null
            activeAuthenticationEpoch = null
            readyAuthenticationEpoch = null
            loginProfileBaseline = null
            loginRequiresProfilePostcondition = false
            if (!isCurrentAction(WearAction.Logout, generation)) return@retireAuthenticationOwner
            beginLogoutProfileLookup(displayedProfileId, generation, logoutAuthenticationSequence)
          },
          onFailure = { error -> completeFailure(WearAction.Logout, error, generation) },
      )
      return
    }
    if (activeLoginAttemptGeneration != null) {
      completeFailure(
          WearAction.Logout,
          IllegalStateException(
              "The previous sign-in attempt could not be cleaned up in time. Try again."),
          generation,
      )
      return
    }
    beginLogoutProfileLookup(displayedProfileId, generation, logoutAuthenticationSequence)
  }

  private fun beginLogoutProfileLookup(
      displayedProfileId: String,
      generation: Long,
      authenticationSequence: Long,
  ) {
    // Destructive account actions never trust a UI/cache/list match. Re-read LocalAPI's exact
    // backend-current profile and require it to still be the account displayed at confirmation.
    Client(viewModelScope, authenticationSequence).currentProfile { result ->
      if (!isCurrentAction(WearAction.Logout, generation)) return@currentProfile
      val freshCurrent =
          result
              .getOrElse {
                completeFailure(WearAction.Logout, it, generation)
                return@currentProfile
              }
              .takeUnless { it.isEmpty() }
      val profile = selectProfileForLogout(displayedProfileId, freshCurrent)
      if (profile == null || _uiState.value.profileId != displayedProfileId) {
        completeFailure(
            WearAction.Logout,
            IllegalStateException(
                "Account details are still loading. Wait a moment and try signing out again."),
            generation,
        )
        return@currentProfile
      }
      performLogoutAccount(profile, generation, authenticationSequence)
    }
  }

  private fun performLogoutAccount(
      profile: IpnLocal.LoginProfile,
      generation: Long,
      authenticationSequence: Long,
  ) {
    if (!isCurrentAction(WearAction.Logout, generation)) return
    val current = _uiState.value
    val useLocalOnlyLogout = shouldUseLocalOnlyLogout(current.underlyingNetworkStatus)
    acceptLoginUrls = false
    readyAuthenticationEpoch = null
    loginProfileBaseline = null
    loginRequiresProfilePostcondition = false
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
    try {
      TailwareVpnStateStore.setDesiredVpnEnabled(app, false)
    } catch (error: VpnStatePersistenceException) {
      app.containVpnStatePersistenceFailure(error)
    }
    val stopDispatch =
        app.stopVPNCommand(
            additionalServiceEvidence = false,
            authenticationEpoch = authenticationSequence,
        )
    if (!stopDispatch.stopSafelyHandled) {
      completeFailure(
          WearAction.Logout,
          IllegalStateException("Android could not safely stop the VPN before sign-out."),
          generation,
      )
      return
    }
    stopLoginForegroundProtection(authenticationSequence)
    if (useLocalOnlyLogout) {
      Client(viewModelScope, authenticationSequence).deleteProfile(profile) { result ->
        if (!isCurrentAction(WearAction.Logout, generation)) return@deleteProfile
        result
            .onSuccess {
              verifyLogoutProfile(
                  generation = generation,
                  authenticationSequence = authenticationSequence,
                  allowLocalCleanup = false,
              )
            }
            .onFailure { completeFailure(WearAction.Logout, it, generation) }
      }
      return
    }
    logout(authenticationEpoch = authenticationSequence) { result ->
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
            verifyLogoutProfile(generation, authenticationSequence)
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
    editPrefs(WearAction.ToggleDns, Ipn.MaskedPrefs().apply { CorpDNS = !prefs.CorpDNS })
  }

  fun toggleAllowLanAccess() {
    val latestIntent = exitPreferencesQueue.optimisticValue()
    val prefs = Notifier.prefs.value
    if (latestIntent == null && prefs == null) {
      setError(WearAction.ToggleLanAccess, "Tailscale preferences are not available yet.")
      return
    }
    val exitNodeId = if (latestIntent != null) latestIntent.exitNodeId else prefs?.activeExitNodeID
    if (exitNodeId == null) {
      setError(WearAction.ToggleLanAccess, "Select an exit node before enabling LAN access.")
      return
    }
    val currentAllowLanAccess =
        latestIntent?.allowLanAccess ?: prefs?.ExitNodeAllowLANAccess ?: false
    submitExitPreferences(
        action = WearAction.ToggleLanAccess,
        intent =
            ExitPreferencesIntent(exitNodeId = exitNodeId, allowLanAccess = !currentAllowLanAccess),
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
    val currentPreferences = Notifier.prefs.value
    val latestIntent = exitPreferencesQueue.optimisticValue()
    submitExitPreferences(
        action = WearAction.SelectExitNode,
        intent =
            ExitPreferencesIntent(
                exitNodeId = stableId,
                allowLanAccess =
                    latestIntent?.allowLanAccess
                        ?: currentPreferences?.ExitNodeAllowLANAccess
                        ?: false,
            ),
    )
  }

  fun clearExitNode() {
    submitExitPreferences(
        action = WearAction.SelectExitNode,
        intent = ExitPreferencesIntent(exitNodeId = null, allowLanAccess = false),
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
                  address = address,
                  status = PingStatus.Running,
              ))
    }
    Client(viewModelScope).ping(peer) { result ->
      result
          .onSuccess { ping ->
            if (ping.Err.isNotBlank()) {
              val failureCategory = pingFailureCategory(ping.Err)
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
                            address = address,
                            status = PingStatus.Failed,
                            failureCategory = failureCategory,
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
            val rawDiagnostic = error.readableMessage()
            val failureCategory = pingFailureCategory(rawDiagnostic)
            TSLog.d(
                TAG,
                "Tailscale disco ping request failed peer=${peer.StableID} target=$address " +
                    "error=$rawDiagnostic",
            )
            updateForAction(WearAction.Ping, generation) {
              it.copy(
                  busyAction = null,
                  pingResult =
                      WearPingResult(
                          peerId = peer.StableID,
                          peerName = peer.displayName,
                          address = address,
                          status = PingStatus.Failed,
                          failureCategory = failureCategory,
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
    localPreferences.edit { putString(KEY_LANGUAGE, language.languageTag) }
    applyAppLocale(language)
    _uiState.update {
      it.copy(
          language = language,
          peers = it.peers.sortedWith(localizedPeerComparator(language)),
          exitNodes = it.exitNodes.sortedWith(localizedExitNodeComparator(language)),
      )
    }
    app.refreshLocalizedNotifications()
    TailwareTileUpdates.request(app)
  }

  /** Imports a language selected through Android's per-app language settings. */
  fun syncLanguageFromSystem() {
    val language = TailwareStrings.selectedLanguage(app)
    val preferenceValue = TailwareStrings.preferenceValue(app)
    if (localPreferences.getString(KEY_LANGUAGE, null) != preferenceValue) {
      localPreferences.edit { putString(KEY_LANGUAGE, preferenceValue) }
    }
    if (_uiState.value.language == language) return
    _uiState.update {
      it.copy(
          language = language,
          peers = it.peers.sortedWith(localizedPeerComparator(language)),
          exitNodes = it.exitNodes.sortedWith(localizedExitNodeComparator(language)),
      )
    }
    app.refreshLocalizedNotifications()
    TailwareTileUpdates.request(app)
  }

  fun setOptionalNotificationsEnabled(enabled: Boolean) {
    localPreferences.edit { putBoolean(KEY_OPTIONAL_NOTIFICATIONS, enabled) }
    syncOptionalNotificationPermission()
  }

  fun onOptionalNotificationPermissionFinished() {
    syncOptionalNotificationPermission()
  }

  fun syncOptionalNotificationPermission() {
    val notificationState = currentOptionalNotificationState()
    _uiState.update {
      it.copy(
          optionalNotificationsRequested = notificationState.requested,
          optionalNotificationsBlockedBySystem = notificationState.blockedBySystem,
      )
    }
    // This uses a separate optional notification ID. App keeps the mandatory foreground VPN
    // notification owned by IPNService while a tunnel is starting or active.
    app.refreshOptionalWearStatusNotification()
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
    appViewModel.setVpnServiceError(null)
    _uiState.update { it.copy(error = null) }
  }

  private fun currentOptionalNotificationState(): OptionalNotificationState {
    val defaultEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
    val requested = localPreferences.getBoolean(KEY_OPTIONAL_NOTIFICATIONS, defaultEnabled)
    return resolveOptionalNotificationState(
        requested = requested,
        systemPermissionGranted = NotificationManagerCompat.from(app).areNotificationsEnabled(),
    )
  }

  private fun submitExitPreferences(action: WearAction, intent: ExitPreferencesIntent) {
    val actionGeneration = startBusyAction(action)
    val submission = exitPreferencesQueue.submit(intent)
    latestExitPreferencesAction =
        ExitPreferencesActionOwner(
            queueGeneration = submission.request.generation,
            action = action,
            actionGeneration = actionGeneration,
        )
    _uiState.update { current ->
      val selectedNode = current.exitNodes.firstOrNull { it.stableId == intent.exitNodeId }
      current.copy(
          selectedExitNodeId = intent.exitNodeId,
          selectedExitNodeName = selectedNode?.displayName,
          activeExitNodeId = intent.exitNodeId,
          activeExitNodeName = selectedNode?.displayName,
          allowLanAccess = intent.allowLanAccess,
          exitNodes =
              current.exitNodes.map { node ->
                node.copy(
                    selected = node.stableId == intent.exitNodeId,
                    active = node.stableId == intent.exitNodeId,
                )
              },
      )
    }
    submission.dispatchNow?.let(::dispatchExitPreferences)
  }

  private fun dispatchExitPreferences(dispatch: LatestIntentDispatch<ExitPreferencesIntent>) {
    val intent = dispatch.value
    val prefs =
        Ipn.MaskedPrefs().apply {
          ExitNodeID = intent.exitNodeId
          ExitNodeAllowLANAccess = intent.allowLanAccess
        }
    Client(viewModelScope).editPrefs(prefs) { result ->
      val completion =
          exitPreferencesQueue.complete(
              generation = dispatch.generation, success = result.isSuccess)
      if (completion.accepted) {
        result.getOrNull()?.let { backendPrefs ->
          if (exitPreferencesProfileId != null &&
              _uiState.value.profileId == exitPreferencesProfileId) {
            // Preserve each successful serial write before dispatching its successor. If A
            // succeeds, B starts, and B fails while the live prefs stream is temporarily null,
            // rollback must restore confirmed A rather than retain optimistic B.
            lastConfirmedExitPreferences = backendPrefs.confirmedExitPreferences()
          }
        }
      }
      completion.next?.let(::dispatchExitPreferences)
      if (!completion.accepted || !completion.completedLatest) return@editPrefs
      val owner =
          latestExitPreferencesAction?.takeIf { it.queueGeneration == dispatch.generation }
              ?: return@editPrefs
      latestExitPreferencesAction = null
      result
          .onSuccess {
            updateForAction(owner.action, owner.actionGeneration) {
              it.copy(busyAction = null, error = null)
            }
          }
          .onFailure { error ->
            val confirmed =
                resolveConfirmedExitPreferences(
                    live = Notifier.prefs.value?.confirmedExitPreferences(),
                    profileCache = lastConfirmedExitPreferences,
                )
            _uiState.update { current ->
              projectBackendExitPreferences(
                  state = current,
                  selectedExitNodeId = confirmed.selectedExitNodeId,
                  activeExitNodeId = confirmed.activeExitNodeId,
                  allowLanAccess = confirmed.allowLanAccess,
              )
            }
            completeFailure(owner.action, error, owner.actionGeneration)
          }
    }
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

  private fun beginLoginAttempt(method: LoginMethod, presentation: LoginPresentation): Long {
    loginAttemptGeneration += 1L
    activeLoginAttemptGeneration = loginAttemptGeneration
    // The login gesture owns this ordering position for its eventual signed-in/disconnected
    // settlement. Every explicit Connect that occurs while native retirement is delayed therefore
    // receives a newer sequence and atomically defeats the old cleanup before it can persist OFF.
    activeLoginVpnSettlementRequestSequence = app.reserveDelayedVpnStopRequest()
    activeAuthenticationEpoch = null
    readyAuthenticationEpoch = null
    loginProfileBaseline = null
    retainedLoginCleanup = null
    loginRequiresProfilePostcondition = true
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

  private fun beginBackendReauthentication(authenticationEpoch: Long): Boolean {
    // Backend-emitted AuthURLs are never user authority. Retire the native epoch instead of
    // adopting it, starting foreground work, or continuing login automatically.
    if (authenticationEpoch != 0L) {
      viewModelScope.launch(Dispatchers.IO) { Notifier.finishAuthentication(authenticationEpoch) }
    }
    return false
  }

  private fun retireAuthenticationOwner(
      owner: AuthenticationRetirementOwner,
      onRetired: () -> Unit,
      onFailure: (Throwable) -> Unit,
  ) {
    val pending = pendingAuthenticationRetirement
    if (pending != null && pending != owner) return
    pendingAuthenticationRetirement = owner
    if (authenticationRetirementInFlight) return
    authenticationRetirementInFlight = true

    // Retirement owns a native callback fence and must outlive Activity/ViewModel cancellation.
    // Only the exact live ViewModel owner is allowed to consume its eventual acknowledgement.
    app.applicationScope.launch(Dispatchers.IO) {
      val result = runCatching { Notifier.finishAuthentication(owner.epoch) }
      viewModelScope.launch completion@{
        if (pendingAuthenticationRetirement != owner) return@completion
        authenticationRetirementInFlight = false
        when (authenticationRetirementCompletion(
            retirementOwner = owner,
            activeAuthenticationEpoch = activeAuthenticationEpoch,
            activeLoginAttemptGeneration = activeLoginAttemptGeneration,
            nativeRetired = result.getOrNull() == true,
        )) {
          AuthenticationRetirementCompletion.ClearOwner -> {
            pendingAuthenticationRetirement = null
            onRetired()
          }
          AuthenticationRetirementCompletion.RetainForRetry ->
              onFailure(
                  result.exceptionOrNull()
                      ?: IllegalStateException(
                          "The previous sign-in attempt could not be cleaned up in time. Try again."))
          AuthenticationRetirementCompletion.IgnoreSuperseded -> {
            pendingAuthenticationRetirement = null
          }
        }
      }
    }
  }

  private fun finishSuccessfulLogin(generation: Long) {
    if (!isCurrentLoginAttempt(activeLoginAttemptGeneration, generation)) return
    val uiSettlementGeneration =
        actionGeneration.takeIf { currentGeneration ->
          authenticationRetirementOwnsUiSettlement(
              expectedActionGeneration = currentGeneration,
              currentActionGeneration = actionGeneration,
              currentBusyAction = _uiState.value.busyAction,
          )
        }
    val desiredVpnGenerationBeforeRetirement = TailwareVpnStateStore.desiredVpnState(app).generation
    val epoch = activeAuthenticationEpoch
    if (epoch == null || epoch == 0L) {
      val delayedVpnStopRequest =
          if (uiSettlementGeneration != null &&
              !TailwareVpnStateStore.desiredVpnState(app).enabled) {
            activeLoginVpnSettlementRequestSequence
          } else {
            null
          }
      completeSuccessfulLoginWithoutAuthenticationOwner(
          generation,
          uiSettlementGeneration,
          desiredVpnGenerationBeforeRetirement,
          delayedVpnStopRequest,
      )
      return
    }
    val owner = AuthenticationRetirementOwner(epoch, generation)
    val pending = pendingAuthenticationRetirement
    if (pending != null && pending != owner) return
    // A duplicate ready notification for the same owner must not reserve a newer cleanup sequence
    // and thereby supersede the sequence already captured by the in-flight retirement callback.
    if (pending == owner && authenticationRetirementInFlight) return

    val delayedVpnStopRequest =
        if (uiSettlementGeneration != null && !TailwareVpnStateStore.desiredVpnState(app).enabled) {
          activeLoginVpnSettlementRequestSequence
        } else {
          null
        }

    acceptLoginUrls = false
    Notifier.browseToURL.set(null)
    Notifier.loginFinished.set(null)
    retireAuthenticationOwner(
        owner = owner,
        onRetired = {
          completeSuccessfulLoginAfterRetirement(
              owner,
              uiSettlementGeneration,
              desiredVpnGenerationBeforeRetirement,
              delayedVpnStopRequest,
          )
        },
        onFailure = {
          if (authenticationRetirementOwnsUiSettlement(
              expectedActionGeneration = uiSettlementGeneration,
              currentActionGeneration = actionGeneration,
              currentBusyAction = _uiState.value.busyAction,
          )) {
            busyTimeoutJob?.cancel()
            _uiState.update { state ->
              state.copy(
                  busyAction = if (state.busyAction == WearAction.Login) null else state.busyAction,
                  error =
                      WearError(
                          operation = WearAction.Login,
                          message = LOGIN_RETIREMENT_FAILURE_MESSAGE,
                          origin = WearErrorOrigin.InteractiveLogin,
                      ),
              )
            }
          }
        },
    )
  }

  private fun completeSuccessfulLoginAfterRetirement(
      owner: AuthenticationRetirementOwner,
      uiSettlementGeneration: Long?,
      desiredVpnGenerationBeforeRetirement: Long,
      delayedVpnStopRequest: Long?,
  ) {
    if (!authenticationRetirementIsStillOwned(
        retirementOwner = owner,
        activeAuthenticationEpoch = activeAuthenticationEpoch,
        activeLoginAttemptGeneration = activeLoginAttemptGeneration,
    )) {
      return
    }
    completeSuccessfulLoginWithoutAuthenticationOwner(
        owner.loginAttemptGeneration ?: return,
        uiSettlementGeneration,
        desiredVpnGenerationBeforeRetirement,
        delayedVpnStopRequest,
    )
  }

  private fun completeSuccessfulLoginWithoutAuthenticationOwner(
      generation: Long,
      uiSettlementGeneration: Long?,
      desiredVpnGenerationBeforeRetirement: Long,
      delayedVpnStopRequest: Long?,
  ) {
    if (!isCurrentLoginAttempt(activeLoginAttemptGeneration, generation)) return
    val currentDesiredVpnState = TailwareVpnStateStore.desiredVpnState(app)
    val settlement =
        successfulLoginSettlementPlan(
            expectedActionGeneration = uiSettlementGeneration,
            desiredVpnGenerationBeforeRetirement = desiredVpnGenerationBeforeRetirement,
            currentActionGeneration = actionGeneration,
            currentBusyAction = _uiState.value.busyAction,
            currentDesiredVpnState = currentDesiredVpnState,
            currentVpnCommandAuthorized =
                TailwareActivationCapability.acceptsVpnCommand(currentDesiredVpnState.generation),
        )
    val vpnCleanupSuperseded =
        settleSuccessfulLoginForeground(
            settlement = settlement.foregroundSettlement,
            delayedVpnStopRequest = delayedVpnStopRequest,
        )
    activeLoginCapability?.let(TailwareActivationCapability::revokeLoginCapability)
    activeLoginCapability = null
    activeLoginAttemptGeneration = null
    activeLoginVpnSettlementRequestSequence = null
    activeAuthenticationEpoch = null
    readyAuthenticationEpoch = null
    loginProfileBaseline = null
    retainedLoginCleanup = null
    loginRequiresProfilePostcondition = false
    loginCleanupRequired = false
    // The reserved OFF operation performs the final ownership check atomically with persistence.
    // If a Connect arrived after the plan above, it superseded cleanup in that tiny last window and
    // must also retain its presentation state.
    val ownsUiSettlement = settlement.ownsUiSettlement && !vpnCleanupSuperseded
    if (ownsUiSettlement) busyTimeoutJob?.cancel()
    Notifier.browseToURL.set(null)
    Notifier.loginFinished.set(null)
    _uiState.update { successfulLoginUiStateAfterSettlement(it, ownsUiSettlement) }
  }

  private fun settleSuccessfulLoginForeground(
      settlement: SuccessfulLoginForegroundSettlement,
      delayedVpnStopRequest: Long?,
  ): Boolean {
    return when (settlement) {
      SuccessfulLoginForegroundSettlement.StopWithOff ->
          stopLoginForegroundProtection(requestSequence = delayedVpnStopRequest)?.superseded == true
      SuccessfulLoginForegroundSettlement.TransferToNewerVpn -> {
        if (!loginForegroundProtectionActive && !hasLoginForegroundProtection()) return false
        loginForegroundProtectionActive = false
        invalidateLoginCallbacks()
        // Desired ON and the exact command capability were checked together immediately before
        // this transfer. Removing the login owner cannot persist or dispatch a newer OFF command.
        transferLoginForegroundToVpn()
        false
      }
      SuccessfulLoginForegroundSettlement.ReleaseWithoutVpnMutation -> {
        if (!loginForegroundProtectionActive && !hasLoginForegroundProtection()) return false
        loginForegroundProtectionActive = false
        invalidateLoginCallbacks()
        val release =
            releaseLoginForegroundProtection(authenticationEpoch = activeAuthenticationEpoch)
        if (release.hadLease && !release.dispatched) {
          TSLog.w("TailwareViewModel", "Unable to release settled login foreground protection")
        }
        false
      }
    }
  }

  private fun stopLoginForegroundProtection(
      authenticationEpoch: Long? = null,
      requestSequence: Long? = null,
  ): com.tailscale.ipn.VpnCommandDispatchResult? {
    if (!loginForegroundProtectionActive) return null
    // Foreground-only login is itself proof that the service may exist even when desired VPN and
    // tunnel-active state are both OFF. Preserve that evidence before releasing the logical lease
    // and persisting another OFF generation.
    loginForegroundProtectionActive = false
    invalidateLoginOperation()
    val dispatch =
        if (requestSequence == null) {
          app.stopVPNCommand(
              additionalServiceEvidence = true,
              authenticationEpoch = authenticationEpoch,
          )
        } else {
          app.stopVPNCommandForReservedRequest(
              requestSequence = requestSequence,
              additionalServiceEvidence = true,
              authenticationEpoch = authenticationEpoch,
          )
        }
    if (!dispatch.stopSafelyHandled) {
      TSLog.w("TailwareViewModel", "Unable to stop login foreground protection")
    }
    return dispatch
  }

  private fun isCurrentLoginCancellation(generation: Long): Boolean =
      loginCancellationInProgress && loginCancellationGeneration == generation

  private fun completeLoginCancellation(
      result: Result<Unit>,
      generation: Long,
      cleanupActionGeneration: Long,
      cleanupAuthenticationEpoch: Long?,
      backendStopAwaitedThroughGeneration: Long,
      machineAuthorizationCleanupAttempted: Boolean = false,
      cleanedUpMachineAuthorization: Boolean = false,
  ) {
    if (!isCurrentLoginCancellation(generation)) return
    loginCancellationInProgress = false
    val ownsCleanupAction = isCurrentAction(WearAction.Login, cleanupActionGeneration)
    val terminalResult =
        if (result.isSuccess) {
          if (!canReleaseAfterAwaitedBackendCleanup(
              authenticationEpoch = cleanupAuthenticationEpoch,
              awaitedStopGeneration = backendStopAwaitedThroughGeneration,
              pendingCleanupGeneration = app.pendingVpnServiceCleanupGeneration(),
          )) {
            // No native epoch authorizes a duplicate backend PATCH, and a newer failed OFF was not
            // covered by the awaited cleanup. Retain foreground protection and fail closed instead
            // of letting a queued replacement login race an unfenced service mutation.
            Result.failure(
                IllegalStateException(
                    "A newer VPN shutdown still needs foreground-protected cleanup. Try again."))
          } else {
            val foregroundRelease =
                releaseLoginForegroundProtection(
                    backendAlreadyStopped = true,
                    authenticationEpoch = cleanupAuthenticationEpoch,
                    backendStopAwaitedThroughGeneration = backendStopAwaitedThroughGeneration,
                )
            if (!exactForegroundCleanupReleaseSucceeded(
                hadLease = foregroundRelease.hadLease,
                dispatched = foregroundRelease.dispatched,
            )) {
              Result.failure(
                  IllegalStateException(
                      "Android could not release the foreground owner for the cancelled sign-in."))
            } else {
              loginForegroundProtectionActive = false
              Result.success(Unit)
            }
          }
        } else {
          result
        }
    terminalResult
        .onSuccess {
          if (machineAuthorizationCleanupAttempted) {
            machineAuthProfileCleanupRequired = false
          }
          loginCleanupRequired = false
          retainedLoginCleanup = null
          loginProfileBaseline = null
          activeLoginCapability?.let(TailwareActivationCapability::revokeLoginCapability)
          activeLoginCapability = null
          refreshLoginProfiles()
          val pending =
              if (ownsCleanupAction) {
                takePendingLoginStart()
              } else {
                discardPendingLoginStart()
                null
              }
          val finalError = postCleanupError
          postCleanupError = null
          if (ownsCleanupAction) {
            _uiState.update { current ->
              val cancelled =
                  current.copy(
                      accountState =
                          if (cleanedUpMachineAuthorization || current.profileId == null) {
                            WearAccountState.SignedOut
                          } else {
                            current.accountState
                          },
                      busyAction = null,
                      loginUrl = null,
                      loginCode = null,
                      activeLoginMethod = null,
                      phoneLoginStatus = PhoneLoginStatus.Idle,
                      phoneLoginError = null,
                      error = finalError,
                  )
              if (cleanedUpMachineAuthorization) {
                cancelled.copy(
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
                )
              } else {
                cancelled
              }
            }
          }
          launchPendingLoginStart(pending)
        }
        .onFailure { error ->
          if (machineAuthorizationCleanupAttempted && cleanedUpMachineAuthorization) {
            machineAuthProfileCleanupRequired = false
          }
          loginCleanupRequired = true
          discardPendingLoginStart()
          postCleanupError = null
          acceptLoginUrls = false
          Notifier.browseToURL.set(null)
          if (ownsCleanupAction) {
            _uiState.update {
              it.copy(
                  busyAction = null,
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
  }

  private fun verifyLogoutProfile(
      generation: Long,
      authenticationSequence: Long,
      allowLocalCleanup: Boolean = true,
      verificationAttempt: Int = 0,
  ) {
    if (!isCurrentAction(WearAction.Logout, generation)) return
    val oldProfile = logoutProfile
    viewModelScope.launch {
      delay(LOGOUT_VERIFICATION_DELAY_MILLIS)
      if (!isCurrentAction(WearAction.Logout, generation)) return@launch
      Client(viewModelScope, authenticationSequence).profiles profilesCallback@{ profilesResult ->
        if (!isCurrentAction(WearAction.Logout, generation)) return@profilesCallback
        val profiles =
            profilesResult.getOrElse {
              completeFailure(WearAction.Logout, it, generation)
              return@profilesCallback
            }
        Client(viewModelScope, authenticationSequence).currentProfile currentProfileCallback@{
            currentResult ->
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
                verifyLogoutProfile(
                    generation,
                    authenticationSequence,
                    true,
                    verificationAttempt + 1,
                )
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
            Client(viewModelScope, authenticationSequence).deleteProfile(
                remainingOldProfile) deleteProfileCallback@{ deleteResult ->
              if (!isCurrentAction(WearAction.Logout, generation)) return@deleteProfileCallback
              deleteResult
                  .onSuccess { verifyLogoutProfile(generation, authenticationSequence, false) }
                  .onFailure { completeFailure(WearAction.Logout, it, generation) }
            }
            return@currentProfileCallback
          }

          if (remainingOldProfile != null &&
              !allowLocalCleanup &&
              verificationAttempt < MAX_LOGOUT_VERIFICATION_ATTEMPTS) {
            verifyLogoutProfile(
                generation = generation,
                authenticationSequence = authenticationSequence,
                allowLocalCleanup = false,
                verificationAttempt = verificationAttempt + 1,
            )
            return@currentProfileCallback
          }

          if (!isCurrentAction(WearAction.Logout, generation)) return@currentProfileCallback
          refreshLoginProfiles()
          if (normalizedCurrent == null && remainingOldProfile == null) {
            val completionNotice = logoutCompletionNotice
            logoutProfile = null
            logoutCompletionNotice = null
            check(logoutMutationGate.finish(generation)) {
              "Verified Logout no longer owns its profile-mutation fence."
            }
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
            val current = _uiState.value
            if (shouldArmLoginCompletionTimeout(current.loginUrl, current.accountState)) {
              clearBusyAfterTimeout(action, generation, LOGIN_TIMEOUT_MILLIS)
            } else {
              busyTimeoutJob?.cancel()
            }
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
      logoutMutationGate.finish(generation)
    }
    val origin =
        if (action == WearAction.Login) _uiState.value.activeLoginMethod.errorOrigin
        else WearErrorOrigin.Global
    if (action == WearAction.Login) {
      startLoginCancellation(
          WearError(operation = action, message = error.readableMessage(), origin = origin))
      return
    }
    updateForAction(action, generation) {
      it.copy(
          busyAction = null,
          error = WearError(operation = action, message = error.readableMessage(), origin = origin),
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
            if (!shouldArmLoginCompletionTimeout(current.loginUrl, current.accountState)) {
              return@launch
            }
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
          if (action == WearAction.Logout && logoutMutationGate.owns(generation)) {
            // The destructive callback fence remains live after a UI timeout. Keep Logout as the
            // visible owner so a login cannot increment actionGeneration and strand verification.
            _uiState.update {
              if (it.busyAction == action && actionGeneration == generation) {
                it.copy(
                    error =
                        WearError(
                            operation = action,
                            message = "The operation is taking longer than expected.",
                            origin = WearErrorOrigin.Global,
                        ))
              } else {
                it
              }
            }
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
    private const val LOGIN_OPERATION_STRAGGLER_TIMEOUT_MILLIS = 20_000L
    private const val MACHINE_AUTH_PROFILE_COHERENCE_ATTEMPTS = 3
    private const val LOGOUT_TIMEOUT_MILLIS = 35_000L
    private const val LOGOUT_VERIFICATION_DELAY_MILLIS = 750L
    private const val MAX_LOGOUT_VERIFICATION_ATTEMPTS = 12
    private const val MAX_BACKEND_ERROR_LENGTH = 180
  }
}
