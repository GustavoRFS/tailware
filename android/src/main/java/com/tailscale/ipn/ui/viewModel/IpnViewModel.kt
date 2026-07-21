// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.LoginForegroundLease
import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.UserID
import com.tailscale.ipn.ui.model.deepCopy
import com.tailscale.ipn.ui.notifier.AuthenticationEvent
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.AdvertisedRoutesHelper
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.wear.TailwareActivationCapability
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class LoginOperation(
    val generation: Long,
    val job: CompletableJob,
    val scope: CoroutineScope,
)

data class LoginForegroundReleaseResult(
    val hadLease: Boolean,
    val dispatched: Boolean,
)

internal class LoginOperationCoordinator {
  private val nextGeneration = AtomicLong()
  private var active: LoginOperation? = null
  private var quiescing: Job? = null

  @Synchronized
  fun begin(parentScope: CoroutineScope): LoginOperation? {
    // A gomobile LocalAPI call is synchronous underneath the coroutine and can take until its own
    // request deadline to return after cancellation. Never let a new login start while that exact
    // cancelled operation still owns a live child: it could otherwise mutate prefs/backend state
    // after the new attempt has begun.
    quiescing?.let { previous ->
      if (!previous.isCompleted) return null
      quiescing = null
    }
    if (active != null) return null
    val job = SupervisorJob(parentScope.coroutineContext[Job])
    return LoginOperation(
            generation = nextGeneration.incrementAndGet(),
            job = job,
            scope = CoroutineScope(parentScope.coroutineContext + job),
        )
        .also { active = it }
  }

  @Synchronized
  fun runIfCurrent(operation: LoginOperation, block: () -> Unit): Boolean {
    if (active !== operation || !operation.job.isActive) return false
    block()
    return true
  }

  @Synchronized
  fun finish(operation: LoginOperation): Boolean {
    if (active !== operation) return false
    active = null
    operation.job.complete()
    return true
  }

  @Synchronized
  fun invalidate(): Job? {
    active?.let { operation ->
      active = null
      operation.job.cancel()
      quiescing = operation.job
    }
    // Keep returning the same cancelled owner on every retry until all of its children have really
    // exited. Coroutine cancellation alone cannot interrupt a synchronous gomobile call.
    return quiescing
  }

  @Synchronized fun isCurrent(operation: LoginOperation): Boolean = active === operation
}

internal fun shouldReleaseLoginForegroundAfterCompletion(
    result: Result<Unit>,
    retainForegroundOnFailure: Boolean,
): Boolean = result.isFailure && !retainForegroundOnFailure

internal fun authenticationEventCompletesEpoch(event: AuthenticationEvent): Boolean =
    !event.bootstrap &&
        (event.loginFinished ||
            event.errMessage != null ||
            (event.state != null && event.state > Ipn.State.NeedsMachineAuth))

internal data class PairedLoadValue<L : Any, R : Any>(
    val generation: Long,
    val left: L,
    val right: R,
)

/**
 * Joins two independently completed LocalAPI reads and accepts only the newest complete pair.
 *
 * Profile list and current-profile endpoints are separate requests. A slower older pair must not
 * overwrite a newer account, and neither half of a pair is safe to publish by itself.
 */
internal class LatestPairedLoadCoordinator<L : Any, R : Any> {
  private data class Attempt<L : Any, R : Any>(
      val generation: Long,
      var left: Result<L>? = null,
      var right: Result<R>? = null,
  )

  private var generation = 0L
  private var active: Attempt<L, R>? = null

  @Synchronized
  fun begin(): Long {
    val next = ++generation
    active = Attempt(next)
    return next
  }

  @Synchronized
  fun acceptLeft(generation: Long, result: Result<L>): Result<PairedLoadValue<L, R>>? {
    val attempt = active?.takeIf { it.generation == generation } ?: return null
    attempt.left = result
    return finishIfComplete(attempt)
  }

  @Synchronized
  fun acceptRight(generation: Long, result: Result<R>): Result<PairedLoadValue<L, R>>? {
    val attempt = active?.takeIf { it.generation == generation } ?: return null
    attempt.right = result
    return finishIfComplete(attempt)
  }

  @Synchronized
  fun fail(generation: Long, error: Throwable): Result<PairedLoadValue<L, R>>? {
    val attempt = active?.takeIf { it.generation == generation } ?: return null
    active = null
    return Result.failure(error)
  }

  /** Claims a coherence retry only if no newer load already won. */
  @Synchronized
  fun invalidateIfCurrent(expectedGeneration: Long): Boolean {
    if (generation != expectedGeneration) return false
    generation += 1L
    active = null
    return true
  }

  @Synchronized fun isCurrent(expectedGeneration: Long): Boolean = generation == expectedGeneration

  private fun finishIfComplete(attempt: Attempt<L, R>): Result<PairedLoadValue<L, R>>? {
    val left = attempt.left ?: return null
    val right = attempt.right ?: return null
    if (active !== attempt) return null
    active = null
    return left.fold(
        onSuccess = { leftValue ->
          right.map { rightValue -> PairedLoadValue(attempt.generation, leftValue, rightValue) }
        },
        onFailure = { Result.failure(it) },
    )
  }
}

internal data class LoginProfilesSnapshot(
    val profiles: List<IpnLocal.LoginProfile>? = null,
    val current: IpnLocal.LoginProfile? = null,
    val revision: Long = 0L,
)

private const val PROFILE_LOAD_MAX_COHERENCE_RETRIES = 2

internal fun profileReadIsCoherent(
    beforeCurrentId: String?,
    listedProfileIds: Set<String>,
    afterCurrentId: String?,
): Boolean =
    beforeCurrentId == afterCurrentId &&
        (afterCurrentId == null || afterCurrentId in listedProfileIds)

/**
 * Base model for most models in this application. Provides common facilities for watching IPN
 * notifications, managing login/logout, updating preferences, etc.
 */
open class IpnViewModel : ViewModel() {
  protected val TAG = this::class.simpleName

  val loggedInUser: StateFlow<IpnLocal.LoginProfile?> = MutableStateFlow(null)
  val loginProfiles: StateFlow<List<IpnLocal.LoginProfile>?> = MutableStateFlow(null)
  private val mutableLoginProfilesSnapshot = MutableStateFlow(LoginProfilesSnapshot())
  internal val loginProfilesSnapshot: StateFlow<LoginProfilesSnapshot> =
      mutableLoginProfilesSnapshot
  private val profileLoadCoordinator =
      LatestPairedLoadCoordinator<List<IpnLocal.LoginProfile>, IpnLocal.LoginProfile>()
  private val profilePublicationLock = Any()

  private val _vpnPrepared = MutableStateFlow(false)
  val vpnPrepared: StateFlow<Boolean> = _vpnPrepared

  // The userId associated with the current node. ie: The logged in user.
  private var selfNodeUserId: UserID? = null

  val isRunningExitNode: StateFlow<Boolean> = MutableStateFlow(false)
  private var lastPrefs: Ipn.Prefs? = null
  private val loginOperationCoordinator = LoginOperationCoordinator()
  private val loginForegroundLease = AtomicReference<LoginForegroundLease?>()
  private val baseAuthenticationEpoch = AtomicLong()
  private val baseAuthenticationRetirements = ConcurrentHashMap.newKeySet<Long>()
  private val authenticationRetirementScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  val prefs = Notifier.prefs
  val netmap = Notifier.netmap
  private val _nodeState = MutableStateFlow(NodeState.NONE)
  val nodeState: StateFlow<NodeState> = _nodeState
  val managedByOrganization = MDMSettings.managedByOrganizationName.flow

  enum class NodeState {
    NONE,
    ACTIVE_AND_RUNNING,
    // Last selected exit node is active but is not being used.
    ACTIVE_NOT_RUNNING,
    // Last selected exit node is currently offline.
    OFFLINE_ENABLED,
    // Last selected exit node has been de-selected and is currently offline.
    OFFLINE_DISABLED,
    // Exit node selection is managed by an administrator, and last selected exit node is currently
    // offline
    OFFLINE_MDM,
    RUNNING_AS_EXIT_NODE
  }

  init {
    viewModelScope.launch {
      Notifier.authenticationEvent.collect { event -> event?.let(::handleBaseAuthenticationEvent) }
    }

    viewModelScope.launch {
      Notifier.state.collect {
        // Reload the user profiles on all state transitions to ensure loggedInUser is correct
        viewModelScope.launch { loadUserProfiles() }
      }
    }

    // This will observe the userId of the current node and reload our user profiles if
    // we discover it has changed (e.g. due to a login or user switch)
    viewModelScope.launch {
      Notifier.netmap.collect {
        it?.SelfNode?.User.let {
          if (it != selfNodeUserId) {
            selfNodeUserId = it
            viewModelScope.launch { loadUserProfiles() }
          }
        }
      }
    }

    viewModelScope.launch {
      Notifier.prefs.collect {
        it?.let {
          lastPrefs = it
          isRunningExitNode.set(AdvertisedRoutesHelper.exitNodeOnFromPrefs(it))
        }
      }
    }

    viewModelScope.launch { loadUserProfiles() }

    viewModelScope.launch {
      combine(prefs, netmap, isRunningExitNode) { prefs, netmap, isRunningExitNode ->
            // Handle nullability for prefs and netmap
            val validPrefs = prefs ?: return@combine NodeState.NONE
            val validNetmap = netmap ?: return@combine NodeState.NONE

            val chosenExitNodeId = validPrefs.activeExitNodeID ?: validPrefs.selectedExitNodeID
            val exitNodePeer =
                chosenExitNodeId?.let { id -> validNetmap.Peers?.find { it.StableID == id } }

            when {
              exitNodePeer?.Online == false -> {
                if (MDMSettings.exitNodeID.flow.value.value != null) {
                  NodeState.OFFLINE_MDM
                } else if (validPrefs.activeExitNodeID != null) {
                  NodeState.OFFLINE_ENABLED
                } else {
                  NodeState.OFFLINE_DISABLED
                }
              }
              exitNodePeer != null -> {
                if (!validPrefs.activeExitNodeID.isNullOrEmpty()) {
                  NodeState.ACTIVE_AND_RUNNING
                } else {
                  NodeState.ACTIVE_NOT_RUNNING
                }
              }
              isRunningExitNode == true -> {
                NodeState.RUNNING_AS_EXIT_NODE
              }
              else -> {
                NodeState.NONE
              }
            }
          }
          .collect { nodeState -> _nodeState.value = nodeState }
    }
    TSLog.d(TAG, "Created")
  }

  // VPN Control
  fun startVPN() {
    if (UninitializedApp.get().startVPN()) {
      transferLoginForegroundToVpn()
    }
  }

  fun stopVPN() {
    UninitializedApp.get().stopVPN()
  }

  // Login/Logout

  /**
   * Order of operations:
   * 1. editPrefs() with maskedPrefs (to allow ControlURL override), LoggedOut=false if AuthKey !=
   *    null
   * 2. start() starts the LocalBackend state machine with WantRunning=true. to avoid a race: if
   *    editPrefs() set WantRunning=true, the backend would fire a cc.Login(LoginDefault) on the
   *    existing control client immediately. start() then tears down this client and creates a new
   *    one
   * 3. startLoginInteractive() is currently required for both interactive and non-interactive
   *    (using auth key) login
   *
   * Any failure short‑circuits the chain and invokes completionHandler once.
   */
  fun login(
      maskedPrefs: Ipn.MaskedPrefs? = null,
      authKey: String? = null,
      useForegroundService: Boolean = true,
      retainForegroundOnFailure: Boolean = false,
      loginCapability: Long = TailwareActivationCapability.NO_CAPABILITY,
      completionHandler: (Result<Unit>) -> Unit = {}
  ) {
    if (!TailwareActivationCapability.acceptsLoginCapability(loginCapability)) {
      completionHandler(
          Result.failure(
              SecurityException(
                  "Login requires a fresh interactive user capability in this process.")))
      return
    }
    val operation =
        loginOperationCoordinator.begin(viewModelScope)
            ?: run {
              completionHandler(
                  Result.failure(
                      IllegalStateException(
                          "The previous sign-in attempt could not be cleaned up in time. Try again.")))
              return
            }
    releaseActiveLoginForeground()
    val continueLogin = continueLogin@{ authenticationEpoch: Long ->
      if (!TailwareActivationCapability.acceptsLoginCapability(loginCapability)) {
        completeLoginOperation(
            operation,
            Result.failure(SecurityException("The interactive login capability expired.")),
            retainForegroundOnFailure,
            completionHandler,
        )
        return@continueLogin
      }
      val client = Client(operation.scope, authenticationEpoch)
      val finalMaskedPrefs = maskedPrefs?.deepCopy() ?: Ipn.MaskedPrefs()
      // Don't set WantRunning=true here. Setting it in editPrefs() triggers cc.Login(LoginDefault)
      // in the Go backend on the existing control client; when the user taps "Log in," login()
      // calls start(), which resets the control client. Instead, carry WantRunning in start()'s
      // UpdatePrefs after Android has confirmed foreground promotion.
      if (authKey != null) {
        finalMaskedPrefs.LoggedOut = false
      }

      client.editPrefs(finalMaskedPrefs) { editResult ->
        editResult.fold(
            onFailure = { error ->
              TSLog.e(TAG, "editPrefs() failed: ${error.message}")
              completeLoginOperation(
                  operation,
                  Result.failure(error),
                  retainForegroundOnFailure,
                  completionHandler,
              )
            },
            onSuccess = { prefs ->
              loginOperationCoordinator.runIfCurrent(operation) {
                if (!TailwareActivationCapability.acceptsLoginCapability(loginCapability)) {
                  completeLoginOperation(
                      operation,
                      Result.failure(
                          SecurityException("The interactive login capability expired.")),
                      retainForegroundOnFailure,
                      completionHandler,
                  )
                  return@runIfCurrent
                }
                // Authentication may start LocalBackend, but it must stay disconnected. A later,
                // separate Connect gesture is the only path allowed to set WantRunning=true.
                prefs.WantRunning = false
                val opts = Ipn.Options(UpdatePrefs = prefs, AuthKey = authKey)
                client.start(opts) { startResult ->
                  startResult.fold(
                      onFailure = { error ->
                        TSLog.e(TAG, "start() failed: ${error.message}")
                        completeLoginOperation(
                            operation,
                            Result.failure(error),
                            retainForegroundOnFailure,
                            completionHandler,
                        )
                      },
                      onSuccess = {
                        loginOperationCoordinator.runIfCurrent(operation) {
                          if (!TailwareActivationCapability.acceptsLoginCapability(
                              loginCapability)) {
                            completeLoginOperation(
                                operation,
                                Result.failure(
                                    SecurityException("The interactive login capability expired.")),
                                retainForegroundOnFailure,
                                completionHandler,
                            )
                            return@runIfCurrent
                          }
                          client.startLoginInteractive { loginResult ->
                            loginResult.fold(
                                onFailure = { error ->
                                  TSLog.e(
                                      TAG,
                                      "startLoginInteractive() failed: ${error.message}",
                                  )
                                  completeLoginOperation(
                                      operation,
                                      Result.failure(error),
                                      retainForegroundOnFailure,
                                      completionHandler,
                                  )
                                },
                                onSuccess = {
                                  completeLoginOperation(
                                      operation,
                                      Result.success(Unit),
                                      retainForegroundOnFailure,
                                      completionHandler,
                                  )
                                },
                            )
                          }
                        }
                      },
                  )
                }
              }
            },
        )
      }
    }
    val beginAuthenticationAndContinue: suspend () -> Unit = beginAuthentication@{
      if (!TailwareActivationCapability.acceptsLoginCapability(loginCapability)) {
        completeLoginOperation(
            operation,
            Result.failure(SecurityException("The interactive login capability expired.")),
            retainForegroundOnFailure,
            completionHandler,
        )
        return@beginAuthentication
      }
      val preparation =
          runCatching { prepareAuthenticationEpoch() }.getOrElse { Result.failure(it) }
      if (preparation.isFailure) {
        completeLoginOperation(
            operation,
            Result.failure(
                preparation.exceptionOrNull()
                    ?: IllegalStateException("Unable to prepare the sign-in attempt.")),
            retainForegroundOnFailure,
            completionHandler,
        )
      } else {
        val authenticationEpoch =
            withContext(NonCancellable + Dispatchers.IO) {
              Notifier.beginAuthenticationWithOwner { epoch ->
                // Publish native epoch ownership while holding both the Notifier boundary and the
                // same operation monitor used by invalidate(). Cancellation therefore either
                // captures this exact epoch, or wins first and makes the creator retire it before
                // any LocalAPI mutation starts.
                loginOperationCoordinator.runIfCurrent(operation) {
                  onAuthenticationEpochStarted(epoch)
                }
              }
            }
        if (authenticationEpoch == null) {
          return@beginAuthentication
        } else if (authenticationEpoch == 0L) {
          completeLoginOperation(
              operation,
              Result.failure(IllegalStateException("The native sign-in service is unavailable.")),
              retainForegroundOnFailure,
              completionHandler,
          )
        } else {
          loginOperationCoordinator.runIfCurrent(operation) { continueLogin(authenticationEpoch) }
        }
      }
    }

    if (!useForegroundService) {
      operation.scope.launch { beginAuthenticationAndContinue() }
      return
    }
    // Do not mutate prefs or start the backend until IPNService confirms that startForeground()
    // itself succeeded. Merely enqueueing startForegroundService() is not sufficient on Wear OS.
    operation.scope.launch {
      UninitializedApp.get()
          .startForegroundForLogin(loginCapability)
          .onSuccess { lease ->
            val accepted =
                loginOperationCoordinator.runIfCurrent(operation) { adoptLoginForeground(lease) }
            if (!accepted) {
              // No backend mutation can start until this exact operation adopts the lease. If
              // cancellation won first, release the provisional owner without asking IPNService
              // to launch a second, unowned WantRunning=false PATCH.
              UninitializedApp.get().releaseForegroundForLogin(lease, backendAlreadyStopped = true)
            } else {
              beginAuthenticationAndContinue()
            }
          }
          .onFailure { error ->
            completeLoginOperation(
                operation,
                Result.failure(error),
                retainForegroundOnFailure,
                completionHandler,
            )
          }
    }
  }

  private fun completeLoginOperation(
      operation: LoginOperation,
      result: Result<Unit>,
      retainForegroundOnFailure: Boolean,
      completionHandler: (Result<Unit>) -> Unit,
  ) {
    if (!loginOperationCoordinator.finish(operation)) return
    val authenticationEpoch = baseAuthenticationEpoch.get().takeIf { it != 0L }
    if (result.isFailure) retireBaseAuthentication(cancel = true)
    if (shouldReleaseLoginForegroundAfterCompletion(result, retainForegroundOnFailure)) {
      releaseActiveLoginForeground(
          backendAlreadyStopped = authenticationEpoch == null,
          authenticationEpoch = authenticationEpoch,
      )
    }
    completionHandler(result)
  }

  internal fun adoptLoginForeground(lease: LoginForegroundLease) {
    val previous = loginForegroundLease.getAndSet(lease)
    if (previous != null && previous != lease) {
      UninitializedApp.get().releaseForegroundForLogin(previous)
    }
  }

  protected fun invalidateLoginOperation(): Job? {
    val quiescence = invalidateLoginCallbacks()
    val authenticationEpoch = baseAuthenticationEpoch.get().takeIf { it != 0L }
    retireBaseAuthentication(cancel = true)
    releaseActiveLoginForeground(
        backendAlreadyStopped = authenticationEpoch == null,
        authenticationEpoch = authenticationEpoch,
    )
    return quiescence
  }

  /**
   * Invalidates only the callback chain.
   *
   * Wear login cancellation deliberately keeps its foreground lease while awaiting this job and
   * cleaning up LocalBackend. Releasing that lease belongs to the final cleanup phase.
   */
  protected fun invalidateLoginCallbacks(): Job? = loginOperationCoordinator.invalidate()

  protected fun releaseLoginForegroundProtection(
      backendAlreadyStopped: Boolean = false,
      authenticationEpoch: Long? = null,
      backendStopAwaitedThroughGeneration: Long = 0L,
  ): LoginForegroundReleaseResult =
      releaseActiveLoginForeground(
          backendAlreadyStopped = backendAlreadyStopped,
          authenticationEpoch = authenticationEpoch,
          backendStopAwaitedThroughGeneration = backendStopAwaitedThroughGeneration,
      )

  protected fun hasLoginForegroundProtection(): Boolean = loginForegroundLease.get() != null

  protected fun transferLoginForegroundToVpn() {
    loginForegroundLease.getAndSet(null)?.let(UninitializedApp.get()::transferForegroundLoginToVpn)
  }

  private fun releaseActiveLoginForeground(
      backendAlreadyStopped: Boolean = false,
      authenticationEpoch: Long? = null,
      backendStopAwaitedThroughGeneration: Long = 0L,
  ): LoginForegroundReleaseResult {
    val lease =
        loginForegroundLease.getAndSet(null)
            ?: return LoginForegroundReleaseResult(hadLease = false, dispatched = false)
    val dispatched =
        UninitializedApp.get()
            .releaseForegroundForLogin(
                lease,
                backendAlreadyStopped,
                authenticationEpoch,
                backendStopAwaitedThroughGeneration,
            )
    // releaseForegroundForLogin publishes logical release before dispatch and retains its own
    // orphan retry token on a hard failure. Restoring this object would create a phantom lease that
    // neither App nor IPNService acknowledges as foreground ownership.
    return LoginForegroundReleaseResult(hadLease = true, dispatched = dispatched)
  }

  override fun onCleared() {
    invalidateLoginOperation()
    super.onCleared()
  }

  fun loginWithAuthKey(
      authKey: String,
      useForegroundService: Boolean = true,
      retainForegroundOnFailure: Boolean = false,
      loginCapability: Long = TailwareActivationCapability.NO_CAPABILITY,
      completionHandler: (Result<Unit>) -> Unit = {},
  ) {
    val prefs = Ipn.MaskedPrefs()
    prefs.WantRunning = false
    login(
        prefs,
        authKey = authKey,
        useForegroundService = useForegroundService,
        retainForegroundOnFailure = retainForegroundOnFailure,
        loginCapability = loginCapability,
        completionHandler = completionHandler,
    )
  }

  fun loginWithCustomControlURL(
      controlURL: String,
      useForegroundService: Boolean = true,
      retainForegroundOnFailure: Boolean = false,
      loginCapability: Long = TailwareActivationCapability.NO_CAPABILITY,
      completionHandler: (Result<Unit>) -> Unit = {},
  ) {
    val prefs = Ipn.MaskedPrefs()
    prefs.ControlURL = controlURL
    login(
        prefs,
        useForegroundService = useForegroundService,
        retainForegroundOnFailure = retainForegroundOnFailure,
        loginCapability = loginCapability,
        completionHandler = completionHandler,
    )
  }

  fun logout(
      authenticationEpoch: Long? = null,
      completionHandler: (Result<String>) -> Unit = {},
  ) {
    Client(viewModelScope, authenticationEpoch).logout { result ->
      result
          .onSuccess { TSLog.d(TAG, "Logout started: $it") }
          .onFailure { TSLog.e(TAG, "Error starting logout: ${it.message}") }
      completionHandler(result)
    }
  }

  // User Profiles

  private fun loadUserProfiles(coherenceRetry: Int = 0) {
    val generation = profileLoadCoordinator.begin()
    Client(viewModelScope).currentProfile beforeCurrent@{ beforeResult ->
      val before =
          beforeResult.getOrElse { error ->
            profileLoadCoordinator.fail(generation, error)?.let(::publishLoadedProfiles)
            return@beforeCurrent
          }
      Client(viewModelScope).profiles profiles@{ profilesResult ->
        val profiles =
            profilesResult.getOrElse { error ->
              profileLoadCoordinator.fail(generation, error)?.let(::publishLoadedProfiles)
              return@profiles
            }
        Client(viewModelScope).currentProfile afterCurrent@{ afterResult ->
          val after =
              afterResult.getOrElse { error ->
                profileLoadCoordinator.fail(generation, error)?.let(::publishLoadedProfiles)
                return@afterCurrent
              }
          val beforeId = before.takeUnless { it.isEmpty() }?.ID
          val afterId = after.takeUnless { it.isEmpty() }?.ID
          if (!profileReadIsCoherent(
              beforeCurrentId = beforeId,
              listedProfileIds = profiles.mapTo(mutableSetOf()) { it.ID },
              afterCurrentId = afterId,
          )) {
            if (coherenceRetry < PROFILE_LOAD_MAX_COHERENCE_RETRIES &&
                profileLoadCoordinator.invalidateIfCurrent(generation)) {
              loadUserProfiles(coherenceRetry + 1)
            } else {
              profileLoadCoordinator
                  .fail(
                      generation,
                      IllegalStateException(
                          "Account changed while Tailware was loading its profile snapshot."),
                  )
                  ?.let(::publishLoadedProfiles)
            }
            return@afterCurrent
          }
          profileLoadCoordinator
              .acceptLeft(generation, Result.success(profiles))
              ?.let(::publishLoadedProfiles)
          profileLoadCoordinator
              .acceptRight(generation, Result.success(after))
              ?.let(::publishLoadedProfiles)
        }
      }
    }
  }

  /** Runs before the native epoch and before any login-related LocalAPI mutation. */
  protected open suspend fun prepareAuthenticationEpoch(): Result<Unit> = Result.success(Unit)

  /** Called synchronously before the first LocalAPI mutation of a fresh native login epoch. */
  protected open fun onAuthenticationEpochStarted(epoch: Long) {
    baseAuthenticationEpoch.set(epoch)
    Notifier.authenticationEvent.replayCache.lastOrNull()?.let(::handleBaseAuthenticationEvent)
  }

  private fun handleBaseAuthenticationEvent(event: AuthenticationEvent) {
    val epoch = baseAuthenticationEpoch.get()
    if (epoch == 0L || event.epoch != epoch || !authenticationEventCompletesEpoch(event)) return
    val cancel = event.errMessage != null
    val quiescence = if (cancel) invalidateLoginCallbacks() else null
    retireBaseAuthentication(
        cancel = cancel,
        quiescence = quiescence,
        releaseForegroundOnSuccess = cancel,
    )
  }

  private fun retireBaseAuthentication(
      cancel: Boolean,
      quiescence: Job? = null,
      releaseForegroundOnSuccess: Boolean = false,
  ) {
    val epoch = baseAuthenticationEpoch.get()
    if (epoch == 0L || !baseAuthenticationRetirements.add(epoch)) return
    authenticationRetirementScope.launch {
      try {
        quiescence?.join()
        val retired =
            if (cancel) {
              Notifier.cancelAuthentication(epoch)
            } else {
              Notifier.finishAuthentication(epoch)
            }
        if (retired && baseAuthenticationEpoch.compareAndSet(epoch, 0L)) {
          if (releaseForegroundOnSuccess) {
            // Native cancellation disconnects control but intentionally leaves WantRunning
            // unchanged. Let the service perform the exact-epoch WantRunning=false reconciliation
            // before it releases the foreground owner.
            releaseActiveLoginForeground(
                backendAlreadyStopped = false,
                authenticationEpoch = epoch,
            )
          }
        }
      } finally {
        baseAuthenticationRetirements.remove(epoch)
      }
    }
  }

  private fun publishLoadedProfiles(
      result: Result<PairedLoadValue<List<IpnLocal.LoginProfile>, IpnLocal.LoginProfile>>
  ) {
    result
        .onSuccess { loaded ->
          publishLoginProfilesFromLoad(
              profiles = loaded.left,
              current = loaded.right.takeUnless { it.isEmpty() },
              loadGeneration = loaded.generation,
          )
        }
        .onFailure { TSLog.e(TAG, "Error loading account profiles: ${it.message}") }
  }

  private fun publishLoginProfilesFromLoad(
      profiles: List<IpnLocal.LoginProfile>?,
      current: IpnLocal.LoginProfile?,
      loadGeneration: Long,
  ) {
    synchronized(profilePublicationLock) {
      if (!profileLoadCoordinator.isCurrent(loadGeneration)) return
      val revision = mutableLoginProfilesSnapshot.value.revision + 1
      mutableLoginProfilesSnapshot.set(LoginProfilesSnapshot(profiles, current, revision))
      loginProfiles.set(profiles)
      loggedInUser.set(current)
    }
  }

  /**
   * Starts a fresh coherent read after a backend profile mutation.
   *
   * Starting a new load invalidates every older request while preserving a still-newer backend
   * notification load only until this later, authoritative read replaces it.
   */
  protected fun refreshLoginProfiles() {
    loadUserProfiles()
  }

  fun switchProfile(profile: IpnLocal.LoginProfile, completionHandler: (Result<String>) -> Unit) {
    val switchProfile = { Client(viewModelScope).switchProfile(profile) { completionHandler(it) } }
    Client(viewModelScope).editPrefs(Ipn.MaskedPrefs().apply { WantRunning = false }) { result ->
      result
          .onSuccess { switchProfile() }
          .onFailure { TSLog.e(TAG, "Error setting wantRunning to false: ${it.message}") }
    }
  }

  fun addProfile(completionHandler: (Result<String>) -> Unit) {
    Client(viewModelScope).addProfile {
      if (it.isSuccess) {
        login()
      }
      completionHandler(it)
    }
  }

  fun deleteProfile(profile: IpnLocal.LoginProfile, completionHandler: (Result<String>) -> Unit) {
    Client(viewModelScope).deleteProfile(profile) {
      viewModelScope.launch { loadUserProfiles() }
      completionHandler(it)
    }
  }

  // Exit Node Manipulation

  fun toggleExitNode() {
    val prefs = prefs.value ?: return

    LoadingIndicator.start()
    if (prefs.activeExitNodeID != null) {
      // We have an active exit node so we should keep it, but disable it
      Client(viewModelScope).setUseExitNode(false) { LoadingIndicator.stop() }
    } else if (prefs.selectedExitNodeID != null) {
      // We have a prior exit node to enable
      Client(viewModelScope).setUseExitNode(true) { LoadingIndicator.stop() }
    } else {
      // This should not be possible.  In this state the button is hidden
      TSLog.e(TAG, "No exit node to disable and no prior exit node to enable")
    }
  }

  fun setRunningExitNode(isOn: Boolean) {
    LoadingIndicator.start()
    lastPrefs?.let { currentPrefs ->
      val newPrefs: Ipn.MaskedPrefs
      if (isOn) {
        newPrefs = setZeroRoutes(currentPrefs)
      } else {
        newPrefs = removeAllZeroRoutes(currentPrefs)
      }
      Client(viewModelScope).editPrefs(newPrefs) { result ->
        LoadingIndicator.stop()
        TSLog.d("RunExitNodeViewModel", "Edited prefs: $result")
      }
    }
  }

  private fun setZeroRoutes(prefs: Ipn.Prefs): Ipn.MaskedPrefs {
    val newRoutes = (removeAllZeroRoutes(prefs).AdvertiseRoutes ?: emptyList()).toMutableList()
    newRoutes.add("0.0.0.0/0")
    newRoutes.add("::/0")
    val newPrefs = Ipn.MaskedPrefs()
    newPrefs.AdvertiseRoutes = newRoutes
    return newPrefs
  }

  private fun removeAllZeroRoutes(prefs: Ipn.Prefs): Ipn.MaskedPrefs {
    val newRoutes = emptyList<String>().toMutableList()
    (prefs.AdvertiseRoutes ?: emptyList()).forEach {
      if (it != "0.0.0.0/0" && it != "::/0") {
        newRoutes.add(it)
      }
    }
    val newPrefs = Ipn.MaskedPrefs()
    newPrefs.AdvertiseRoutes = newRoutes
    return newPrefs
  }
}
