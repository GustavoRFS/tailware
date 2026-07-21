// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import com.tailscale.ipn.ForegroundNotificationOwner
import com.tailscale.ipn.LOGIN_FOREGROUND_PROMOTION_TIMEOUT_MILLIS
import com.tailscale.ipn.LoginForegroundPromotionCoordinator
import com.tailscale.ipn.PersistedServiceStartAction
import com.tailscale.ipn.TerminalVpnFailureCleanup
import com.tailscale.ipn.UnderlyingNetworkStatus
import com.tailscale.ipn.VpnStartWatchdogDecision
import com.tailscale.ipn.failedStopFallbackWithLoginOwner
import com.tailscale.ipn.foregroundNotificationOwner
import com.tailscale.ipn.loginForegroundOwnersAfterRelease
import com.tailscale.ipn.nativeStartupFailureOwner
import com.tailscale.ipn.ownsCommandCleanup
import com.tailscale.ipn.ownsLoginForegroundRelease
import com.tailscale.ipn.ownsVpnStatusCallback
import com.tailscale.ipn.persistedServiceStartAction
import com.tailscale.ipn.runBestEffortNotificationUpdate
import com.tailscale.ipn.shouldForceStopVpnServiceAfterFailedDispatch
import com.tailscale.ipn.shouldPublishOptionalDisconnectedNotification
import com.tailscale.ipn.shouldPublishStopping
import com.tailscale.ipn.shouldReconcileAfterOwnedStop
import com.tailscale.ipn.shouldSkipPendingVpnCleanupBackendMutation
import com.tailscale.ipn.shouldStartPersistedVpnService
import com.tailscale.ipn.shouldUseStickyForegroundRestart
import com.tailscale.ipn.terminalVpnFailureCleanup
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.viewModel.LoginOperationCoordinator
import com.tailscale.ipn.vpnCleanupGenerationAfterOutcome
import com.tailscale.ipn.vpnDisconnectGeneration
import com.tailscale.ipn.vpnStartWatchdogDecision
import com.tailscale.ipn.vpnStatusUpdatePlan
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Private5VpnServiceLifecycleTest {
  private val owner = DesiredVpnState(enabled = true, generation = 12L)

  @Test
  fun foregroundLoginPromotionRequiresOneExactLiveAcknowledgement() = runBlocking {
    val coordinator = LoginForegroundPromotionCoordinator()
    val liveRequest = coordinator.begin()

    assertTrue(coordinator.complete(liveRequest.id, Result.success(Unit)))
    assertTrue(liveRequest.completion.await().isSuccess)
    assertFalse(coordinator.abandon(liveRequest.id))
    assertFalse(coordinator.complete(liveRequest.id, Result.success(Unit)))
    assertTrue(coordinator.hasOwner())
    coordinator.release(liveRequest.id)
    assertFalse(coordinator.hasOwner())

    val failedRequest = coordinator.begin()
    val promotionFailure = IllegalStateException("foreground rejected")
    assertTrue(coordinator.complete(failedRequest.id, Result.failure(promotionFailure)))
    assertEquals(promotionFailure, failedRequest.completion.await().exceptionOrNull())

    val abandonedRequest = coordinator.begin()
    assertTrue(coordinator.abandon(abandonedRequest.id))
    assertFalse(coordinator.complete(abandonedRequest.id, Result.success(Unit)))
  }

  @Test
  fun failedOffFallbackIsSerializedAgainstANewerLoginOnlyOwner() {
    val coordinator = LoginForegroundPromotionCoordinator()
    val requestFence = coordinator.currentRequestFence()
    val newerLogin = coordinator.begin()
    var stopRan = false

    assertFalse(
        coordinator.runUnlessNewerOwner(
            requestFence = requestFence,
            preserveNewerOwner = true,
        ) {
          stopRan = true
        })
    assertFalse(stopRan)

    // A real TUN still gives OFF cleanup priority, but serialization ensures the login owner that
    // begins afterward will dispatch a fresh start rather than being killed after its begin.
    assertTrue(
        coordinator.runUnlessNewerOwner(
            requestFence = requestFence,
            preserveNewerOwner = false,
        ) {
          stopRan = true
        })
    assertTrue(stopRan)

    assertTrue(coordinator.complete(newerLogin.id, Result.success(Unit)))
    coordinator.transferToVpn(newerLogin.id)
    assertFalse(coordinator.hasOwner())
  }

  @Test
  fun failedOffDispatchNeverKillsAnExistingLoginForegroundOwner() {
    assertTrue(
        failedStopFallbackWithLoginOwner(
            vpnWasDesired = false,
            tunnelWasActive = false,
            vpnCleanupWasPending = false,
            hasLoginForegroundOwner = true,
        ) == true)
    assertTrue(
        failedStopFallbackWithLoginOwner(
            vpnWasDesired = false,
            tunnelWasActive = true,
            vpnCleanupWasPending = false,
            hasLoginForegroundOwner = true,
        ) == false)
    assertTrue(
        failedStopFallbackWithLoginOwner(
            vpnWasDesired = true,
            tunnelWasActive = false,
            vpnCleanupWasPending = false,
            hasLoginForegroundOwner = true,
        ) == false)
    assertTrue(
        failedStopFallbackWithLoginOwner(
            vpnWasDesired = false,
            tunnelWasActive = false,
            vpnCleanupWasPending = true,
            hasLoginForegroundOwner = true,
        ) == false)
    assertNull(
        failedStopFallbackWithLoginOwner(
            vpnWasDesired = false,
            tunnelWasActive = false,
            vpnCleanupWasPending = false,
            hasLoginForegroundOwner = false,
        ))
  }

  @Test
  fun olderCleanupAcknowledgementCannotEraseNewerFailedOffEvidence() {
    assertEquals(
        12L,
        vpnCleanupGenerationAfterOutcome(
            currentGeneration = 12L,
            outcomeGeneration = 11L,
            handled = true,
        ))
    assertEquals(
        0L,
        vpnCleanupGenerationAfterOutcome(
            currentGeneration = 12L,
            outcomeGeneration = 12L,
            handled = true,
        ))
    assertEquals(
        13L,
        vpnCleanupGenerationAfterOutcome(
            currentGeneration = 12L,
            outcomeGeneration = 13L,
            handled = false,
        ))
  }

  @Test
  fun pendingCleanupSkipsDuplicateBackendStopOnlyAfterAwaitedCleanup() {
    val desiredOff = DesiredVpnState(enabled = false, generation = 14L)
    assertTrue(
        shouldSkipPendingVpnCleanupBackendMutation(
            backendAlreadyStopped = true,
            backendStopAwaitedThroughGeneration = 14L,
            vpnCleanupGeneration = 14L,
            desiredState = desiredOff,
        ))
    assertFalse(
        shouldSkipPendingVpnCleanupBackendMutation(
            backendAlreadyStopped = true,
            backendStopAwaitedThroughGeneration = 0L,
            vpnCleanupGeneration = 14L,
            desiredState = desiredOff,
        ))
    assertFalse(
        shouldSkipPendingVpnCleanupBackendMutation(
            backendAlreadyStopped = true,
            backendStopAwaitedThroughGeneration = 14L,
            vpnCleanupGeneration = 15L,
            desiredState = desiredOff,
        ))
    assertFalse(
        shouldSkipPendingVpnCleanupBackendMutation(
            backendAlreadyStopped = true,
            backendStopAwaitedThroughGeneration = 14L,
            vpnCleanupGeneration = 14L,
            desiredState = desiredOff.copy(enabled = true),
        ))
  }

  @Test
  fun foregroundLoginDispatchWaitsForTheCompleteOffLifecycleGate() {
    val coordinator = LoginForegroundPromotionCoordinator()
    val fallbackEntered = CountDownLatch(1)
    val allowFallbackToFinish = CountDownLatch(1)
    val loginAttempting = CountDownLatch(1)
    val loginDispatched = CountDownLatch(1)

    val fallback =
        thread(start = true, name = "vpn-off-lifecycle") {
          coordinator.runLifecycle {
            fallbackEntered.countDown()
            assertTrue(allowFallbackToFinish.await(2, TimeUnit.SECONDS))
          }
        }
    assertTrue(fallbackEntered.await(2, TimeUnit.SECONDS))
    val login =
        thread(start = true, name = "foreground-login-dispatch") {
          loginAttempting.countDown()
          coordinator.beginWithDispatch { loginDispatched.countDown() }
        }
    assertTrue(loginAttempting.await(2, TimeUnit.SECONDS))
    assertFalse(loginDispatched.await(100, TimeUnit.MILLISECONDS))

    allowFallbackToFinish.countDown()
    assertTrue(loginDispatched.await(2, TimeUnit.SECONDS))
    fallback.join(2_000)
    login.join(2_000)
    assertFalse(fallback.isAlive)
    assertFalse(login.isAlive)
  }

  @Test
  fun failedForegroundLoginDispatchDoesNotLeaveAnOwnerFence() {
    val coordinator = LoginForegroundPromotionCoordinator()
    val failure = IllegalStateException("foreground dispatch rejected")

    val thrown = runCatching { coordinator.beginWithDispatch { throw failure } }.exceptionOrNull()

    assertSame(failure, thrown)
    assertFalse(coordinator.hasOwner())
  }

  @Test
  fun foregroundLoginReleaseCanOnlyReconcileItsExactRequestOwner() {
    assertTrue(ownsLoginForegroundRelease(activeRequestId = 42L, releasedRequestId = 42L))
    assertFalse(ownsLoginForegroundRelease(activeRequestId = 43L, releasedRequestId = 42L))
    assertFalse(ownsLoginForegroundRelease(activeRequestId = null, releasedRequestId = 42L))
  }

  @Test
  fun foregroundLoginServiceRetainsEveryOtherExactOwner() {
    val owners = setOf(41L, 42L, 43L)

    assertEquals(setOf(41L, 43L), loginForegroundOwnersAfterRelease(owners, 42L))
    assertEquals(owners, loginForegroundOwnersAfterRelease(owners, 99L))
    assertTrue(loginForegroundOwnersAfterRelease(setOf(42L), 42L).isEmpty())
  }

  @Test
  fun logicalForegroundLoginReleaseRetainsOtherLiveOwners() = runBlocking {
    val coordinator = LoginForegroundPromotionCoordinator()
    val first = coordinator.begin()
    val second = coordinator.begin()
    assertTrue(coordinator.complete(first.id, Result.success(Unit)))
    assertTrue(coordinator.complete(second.id, Result.success(Unit)))

    assertTrue(coordinator.release(second.id))
    assertTrue(coordinator.hasOwner())
    assertTrue(coordinator.release(first.id))
    assertFalse(coordinator.hasOwner())
  }

  @Test
  fun foregroundRefreshPreservesTheCurrentOwnerType() {
    assertEquals(
        ForegroundNotificationOwner.LoginOnly,
        foregroundNotificationOwner(
            activeLoginRequestId = 42L,
            vpnRequested = false,
            tunnelActive = false,
        ),
    )
    assertEquals(
        ForegroundNotificationOwner.Vpn,
        foregroundNotificationOwner(
            activeLoginRequestId = 42L,
            vpnRequested = true,
            tunnelActive = false,
        ),
    )
    assertEquals(
        ForegroundNotificationOwner.Vpn,
        foregroundNotificationOwner(
            activeLoginRequestId = 42L,
            vpnRequested = false,
            tunnelActive = true,
        ),
    )
    assertEquals(
        ForegroundNotificationOwner.None,
        foregroundNotificationOwner(
            activeLoginRequestId = null,
            vpnRequested = false,
            tunnelActive = false,
        ),
    )
  }

  @Test
  fun coldStickyStartStopsWithoutForegroundOrStateRestoration() {
    assertEquals(
        PersistedServiceStartAction.StopWithoutInitialization,
        persistedServiceStartAction(foregroundStarted = false),
    )
    assertEquals(
        PersistedServiceStartAction.StopWithoutInitialization,
        persistedServiceStartAction(foregroundStarted = true),
    )
    assertTrue(LOGIN_FOREGROUND_PROMOTION_TIMEOUT_MILLIS > 5_000L)
  }

  @Test
  fun persistedServiceStateNeverAuthorizesAStart() {
    assertFalse(shouldStartPersistedVpnService(desiredEnabled = true, vpnAuthorized = true))
    assertFalse(shouldStartPersistedVpnService(desiredEnabled = true, vpnAuthorized = false))
    assertFalse(shouldStartPersistedVpnService(desiredEnabled = false, vpnAuthorized = true))
    assertFalse(shouldStartPersistedVpnService(desiredEnabled = false, vpnAuthorized = false))
  }

  @Test
  fun oldCommandCleanupCannotOwnANewerWriterOrStopItsStartId() {
    val oldOff = DesiredVpnState(enabled = false, generation = 13L)
    val newerOn = DesiredVpnState(enabled = true, generation = 14L)

    assertTrue(ownsCommandCleanup(oldOff, oldOff))
    assertFalse(ownsCommandCleanup(oldOff, newerOn))
    assertTrue(
        shouldReconcileAfterOwnedStop(
            serviceStopped = false,
            ownerStartId = 8,
            latestStartId = 9,
        ))
    assertFalse(
        shouldReconcileAfterOwnedStop(
            serviceStopped = true,
            ownerStartId = 8,
            latestStartId = 9,
        ))
  }

  @Test
  fun canceledLoginGenerationCannotRunAnyLaterMutationCallback() = runBlocking {
    val coordinator = LoginOperationCoordinator()
    val first = requireNotNull(coordinator.begin(CoroutineScope(coroutineContext)))
    assertNull(coordinator.begin(CoroutineScope(coroutineContext)))
    coordinator.invalidate()!!.join()
    val second = requireNotNull(coordinator.begin(CoroutineScope(coroutineContext)))
    var mutations = 0

    assertFalse(coordinator.runIfCurrent(first) { mutations++ })
    assertTrue(coordinator.runIfCurrent(second) { mutations++ })
    assertEquals(1, mutations)

    val canceledJob = coordinator.invalidate()
    assertSame(second.job, canceledJob)
    canceledJob!!.join()
    assertFalse(coordinator.runIfCurrent(second) { mutations++ })
    assertEquals(1, mutations)
  }

  @Test
  fun canceledBlockingLoginOwnerIsRetainedAcrossRetriesUntilItReallyQuiesces() = runBlocking {
    val coordinator = LoginOperationCoordinator()
    val first = requireNotNull(coordinator.begin(CoroutineScope(coroutineContext)))
    val childStarted = CompletableDeferred<Unit>()
    val releaseBlockingChild = CompletableDeferred<Unit>()
    first.scope.launch {
      try {
        childStarted.complete(Unit)
        awaitCancellation()
      } finally {
        withContext(NonCancellable) { releaseBlockingChild.await() }
      }
    }
    childStarted.await()

    val firstBarrier = coordinator.invalidate()
    assertNotNull(firstBarrier)
    assertFalse(firstBarrier!!.isCompleted)
    assertSame(firstBarrier, coordinator.invalidate())
    assertNull(coordinator.begin(CoroutineScope(coroutineContext)))

    releaseBlockingChild.complete(Unit)
    firstBarrier.join()
    val retry = coordinator.begin(CoroutineScope(coroutineContext))
    assertNotNull(retry)
    coordinator.invalidate()!!.join()
  }

  @Test
  fun everyServiceOwnerIsNonSticky() {
    assertFalse(
        shouldUseStickyForegroundRestart(
            activeCommand = null,
            vpnRequested = false,
            tunnelActive = false,
        ))
    assertFalse(
        shouldUseStickyForegroundRestart(
            activeCommand = owner,
            vpnRequested = false,
            tunnelActive = false,
        ))
    assertFalse(
        shouldUseStickyForegroundRestart(
            activeCommand = null,
            vpnRequested = true,
            tunnelActive = false,
        ))
    assertFalse(
        shouldUseStickyForegroundRestart(
            activeCommand = null,
            vpnRequested = false,
            tunnelActive = true,
        ))
  }

  @Test
  fun failedStopOwnerCheckAndStopAreIndivisibleFromANewerOnCommand() {
    val gate = VpnStateOperationGate()
    val fallbackEntered = CountDownLatch(1)
    val allowFallbackStopToFinish = CountDownLatch(1)
    val newerOnIsAttempting = CountDownLatch(1)
    val newerOnEntered = CountDownLatch(1)
    val fallbackStopFinished = AtomicBoolean(false)
    val newerOnObservedCompletedFallback = AtomicBoolean(false)

    val fallback =
        thread(start = true, name = "failed-stop-fallback") {
          gate.run {
            fallbackEntered.countDown()
            assertTrue(allowFallbackStopToFinish.await(2, TimeUnit.SECONDS))
            fallbackStopFinished.set(true)
          }
        }
    assertTrue(fallbackEntered.await(2, TimeUnit.SECONDS))

    val newerOn =
        thread(start = true, name = "newer-on-command") {
          newerOnIsAttempting.countDown()
          gate.run {
            newerOnObservedCompletedFallback.set(fallbackStopFinished.get())
            newerOnEntered.countDown()
          }
        }
    assertTrue(newerOnIsAttempting.await(2, TimeUnit.SECONDS))
    assertFalse(newerOnEntered.await(100, TimeUnit.MILLISECONDS))

    allowFallbackStopToFinish.countDown()
    assertTrue(newerOnEntered.await(2, TimeUnit.SECONDS))
    fallback.join(2_000)
    newerOn.join(2_000)
    assertFalse(fallback.isAlive)
    assertFalse(newerOn.isAlive)
    assertTrue(newerOnObservedCompletedFallback.get())
  }

  @Test
  fun failedReceiverStopMayForceStopOnlyItsExactStillCurrentOffOwner() {
    val offOwner = DesiredVpnState(enabled = false, generation = 13L)
    assertTrue(
        shouldForceStopVpnServiceAfterFailedDispatch(
            wasVpnActive = true,
            failedCommand = offOwner,
            currentState = offOwner,
        ))
    assertFalse(
        shouldForceStopVpnServiceAfterFailedDispatch(
            wasVpnActive = false,
            failedCommand = offOwner,
            currentState = offOwner,
        ))
    assertFalse(
        shouldForceStopVpnServiceAfterFailedDispatch(
            wasVpnActive = true,
            failedCommand = offOwner,
            currentState = DesiredVpnState(enabled = true, generation = 14L),
        ))
  }

  @Test
  fun forcedServiceCloseDisconnectsNativeVpnWithTheCurrentOffGeneration() {
    val currentOff = DesiredVpnState(enabled = false, generation = 13L)
    assertEquals(
        currentOff.generation,
        vpnDisconnectGeneration(activeCommand = owner, currentState = currentOff),
    )
    assertEquals(
        owner.generation,
        vpnDisconnectGeneration(
            activeCommand = owner,
            currentState = DesiredVpnState(enabled = true, generation = 14L),
        ),
    )
  }

  @Test
  fun stoppingIsPublishedOnlyForAnOwnedNativeTunnel() {
    assertTrue(shouldPublishStopping(ownsNativeVpn = true))
    assertFalse(shouldPublishStopping(ownsNativeVpn = false))
  }

  @Test
  fun nativeVpnStatusCallbacksBelongToOneExactCommandGeneration() {
    assertTrue(
        ownsVpnStatusCallback(
            activeCommand = owner,
            currentState = owner,
            callbackGeneration = owner.generation,
            status = true,
            closed = false,
        ))
    assertFalse(
        ownsVpnStatusCallback(
            activeCommand = DesiredVpnState(enabled = true, generation = 13L),
            currentState = DesiredVpnState(enabled = true, generation = 13L),
            callbackGeneration = owner.generation,
            status = false,
            closed = false,
        ))
    assertFalse(
        ownsVpnStatusCallback(
            activeCommand = owner,
            currentState = DesiredVpnState(enabled = false, generation = 13L),
            callbackGeneration = owner.generation,
            status = true,
            closed = false,
        ))
    assertFalse(
        ownsVpnStatusCallback(
            activeCommand = owner,
            currentState = owner,
            callbackGeneration = owner.generation,
            status = true,
            closed = true,
        ))
  }

  @Test
  fun nativeStartupFailureCanOnlyBeClaimedByItsExactCurrentOnOwner() {
    assertEquals(
        owner,
        nativeStartupFailureOwner(
            callbackGeneration = owner.generation,
            currentState = owner,
        ),
    )
    assertEquals(
        null,
        nativeStartupFailureOwner(
            callbackGeneration = owner.generation,
            currentState = DesiredVpnState(enabled = true, generation = owner.generation + 1),
        ),
    )
    assertEquals(
        null,
        nativeStartupFailureOwner(
            callbackGeneration = owner.generation,
            currentState = DesiredVpnState(enabled = false, generation = owner.generation),
        ),
    )
  }

  @Test
  fun nativeTunnelDropRefreshesConnectingNotificationAndRearmsWatchdog() {
    val dropped = vpnStatusUpdatePlan(status = false)
    assertFalse(dropped.notificationRunning)
    assertFalse(dropped.cancelStartWatchdog)
    assertTrue(dropped.scheduleStartWatchdog)

    val connected = vpnStatusUpdatePlan(status = true)
    assertTrue(connected.notificationRunning)
    assertTrue(connected.cancelStartWatchdog)
    assertFalse(connected.scheduleStartWatchdog)
  }

  @Test
  fun startWatchdogStopsNeedsLoginAndMissingTunnelStates() {
    assertEquals(
        VpnStartWatchdogDecision.StopNeedsLogin,
        vpnStartWatchdogDecision(
            commandOwner = owner,
            currentState = owner,
            ipnState = Ipn.State.NeedsLogin,
            underlyingNetworkStatus = UnderlyingNetworkStatus.Online,
            tunnelActive = false,
            closed = false,
        ),
    )
    assertEquals(
        VpnStartWatchdogDecision.StopNoTunnel,
        vpnStartWatchdogDecision(
            commandOwner = owner,
            currentState = owner,
            ipnState = Ipn.State.Starting,
            underlyingNetworkStatus = UnderlyingNetworkStatus.Online,
            tunnelActive = false,
            closed = false,
        ),
    )
  }

  @Test
  fun startWatchdogWaitsForAndRearmsAroundAnExactLiveLoginLease() {
    assertEquals(
        VpnStartWatchdogDecision.WaitForLogin,
        vpnStartWatchdogDecision(
            commandOwner = owner,
            currentState = owner,
            ipnState = Ipn.State.NeedsLogin,
            underlyingNetworkStatus = UnderlyingNetworkStatus.Online,
            tunnelActive = false,
            closed = false,
            hasLiveLoginForegroundOwner = true,
        ),
    )
    assertEquals(
        VpnStartWatchdogDecision.StopNeedsLogin,
        vpnStartWatchdogDecision(
            commandOwner = owner,
            currentState = owner,
            ipnState = Ipn.State.NeedsLogin,
            underlyingNetworkStatus = UnderlyingNetworkStatus.Online,
            tunnelActive = false,
            closed = false,
            hasLiveLoginForegroundOwner = false,
        ),
    )
  }

  @Test
  fun startWatchdogWaitsForMachineApprovalWithoutAnInMemoryLoginLease() {
    assertEquals(
        VpnStartWatchdogDecision.WaitForLogin,
        vpnStartWatchdogDecision(
            commandOwner = owner,
            currentState = owner,
            ipnState = Ipn.State.NeedsMachineAuth,
            underlyingNetworkStatus = UnderlyingNetworkStatus.Online,
            tunnelActive = false,
            closed = false,
            hasLiveLoginForegroundOwner = false,
        ),
    )
  }

  @Test
  fun startWatchdogPreservesDesiredOnWhileTheUnderlyingNetworkIsUnavailable() {
    for (networkStatus in
        listOf(
            UnderlyingNetworkStatus.Unknown,
            UnderlyingNetworkStatus.Limited,
            UnderlyingNetworkStatus.Offline,
        )) {
      assertEquals(
          VpnStartWatchdogDecision.WaitForNetwork,
          vpnStartWatchdogDecision(
              commandOwner = owner,
              currentState = owner,
              ipnState = Ipn.State.Starting,
              underlyingNetworkStatus = networkStatus,
              tunnelActive = false,
              closed = false,
          ),
      )
    }
  }

  @Test
  fun startWatchdogNeverStopsACompletedClosedOrSupersededCommand() {
    assertEquals(
        VpnStartWatchdogDecision.None,
        vpnStartWatchdogDecision(
            commandOwner = owner,
            currentState = owner,
            ipnState = Ipn.State.Running,
            underlyingNetworkStatus = UnderlyingNetworkStatus.Online,
            tunnelActive = true,
            closed = false,
        ),
    )
    assertEquals(
        VpnStartWatchdogDecision.None,
        vpnStartWatchdogDecision(
            commandOwner = owner,
            currentState = owner,
            ipnState = Ipn.State.Starting,
            underlyingNetworkStatus = UnderlyingNetworkStatus.Online,
            tunnelActive = false,
            closed = true,
        ),
    )
    assertEquals(
        VpnStartWatchdogDecision.None,
        vpnStartWatchdogDecision(
            commandOwner = owner,
            currentState = DesiredVpnState(enabled = false, generation = 13L),
            ipnState = Ipn.State.Starting,
            underlyingNetworkStatus = UnderlyingNetworkStatus.Online,
            tunnelActive = false,
            closed = false,
        ),
    )
  }

  @Test
  fun terminalFailureRollsTheExactOwningOnGenerationForwardToOff() {
    assertEquals(
        DesiredVpnState(enabled = false, generation = 13L),
        terminalVpnFailureRollback(currentState = owner, failedCommand = owner),
    )
  }

  @Test
  fun terminalFailureNeverOverwritesANewerUserStartOrStop() {
    assertEquals(
        null,
        terminalVpnFailureRollback(
            currentState = DesiredVpnState(enabled = true, generation = 13L),
            failedCommand = owner,
        ),
    )
    assertEquals(
        null,
        terminalVpnFailureRollback(
            currentState = DesiredVpnState(enabled = false, generation = 13L),
            failedCommand = owner,
        ),
    )
  }

  @Test
  fun terminalFailureCannotTurnAnOffCommandIntoAnotherGeneration() {
    val offCommand = DesiredVpnState(enabled = false, generation = 12L)
    assertEquals(
        null,
        terminalVpnFailureRollback(currentState = offCommand, failedCommand = offCommand),
    )
  }

  @Test
  fun staleTerminalFailureReconcilesInsteadOfClosingANewerOwner() {
    assertEquals(
        TerminalVpnFailureCleanup.ReconcileNewerOwner,
        terminalVpnFailureCleanup(failedCommand = owner, rollbackCommand = null),
    )
  }

  @Test
  fun claimedRollbackAndForegroundOnlyFailureHaveDistinctCleanupPaths() {
    val rollback = DesiredVpnState(enabled = false, generation = 13L)
    assertEquals(
        TerminalVpnFailureCleanup.CleanupFailedOwner,
        terminalVpnFailureCleanup(failedCommand = owner, rollbackCommand = rollback),
    )
    assertEquals(
        TerminalVpnFailureCleanup.CleanupUnownedService,
        terminalVpnFailureCleanup(failedCommand = null, rollbackCommand = null),
    )
  }

  @Test
  fun optionalNotificationCannotOverwriteConnectingOrConnectedForegroundState() {
    assertFalse(
        shouldPublishOptionalDisconnectedNotification(
            state = Ipn.State.Stopped,
            vpnActive = false,
            desiredVpnEnabled = true,
        ))
    assertFalse(
        shouldPublishOptionalDisconnectedNotification(
            state = Ipn.State.Stopped,
            vpnActive = true,
            desiredVpnEnabled = false,
        ))
    assertTrue(
        shouldPublishOptionalDisconnectedNotification(
            state = Ipn.State.Stopped,
            vpnActive = false,
            desiredVpnEnabled = false,
        ))
  }

  @Test
  fun optionalNotificationFailureIsContainedAndLaterUpdatesStillRun() {
    var failures = 0
    assertFalse(
        runBestEffortNotificationUpdate(
            update = { throw SecurityException("notification service unavailable") },
            onFailure = { failures += 1 },
        ))

    var laterUpdateRan = false
    assertTrue(
        runBestEffortNotificationUpdate(
            update = { laterUpdateRan = true },
            onFailure = { failures += 1 },
        ))
    assertEquals(1, failures)
    assertTrue(laterUpdateRan)
  }
}
