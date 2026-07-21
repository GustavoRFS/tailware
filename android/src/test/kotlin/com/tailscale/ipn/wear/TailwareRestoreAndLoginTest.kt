// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import android.content.Context
import androidx.work.WorkerParameters
import com.tailscale.ipn.StartVPNWorker
import com.tailscale.ipn.StopVPNWorker
import com.tailscale.ipn.UnderlyingNetworkStatus
import com.tailscale.ipn.VPN_START_WATCHDOG_MILLIS
import com.tailscale.ipn.shouldCloseForegroundAfterOwnedBackendStop
import com.tailscale.ipn.ui.localapi.WANT_RUNNING_LOCAL_API_TIMEOUT_MILLIS
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.viewModel.LatestPairedLoadCoordinator
import com.tailscale.ipn.ui.viewModel.profileReadIsCoherent
import com.tailscale.ipn.ui.viewModel.shouldReleaseLoginForegroundAfterCompletion
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class TailwareRestoreAndLoginTest {
  private fun profile(id: String): IpnLocal.LoginProfile =
      IpnLocal.LoginProfile(
          ID = id,
          Name = id,
          Key = "",
          UserProfile = Tailcfg.UserProfile(DisplayName = id),
          LocalUserID = id,
      )

  @Test
  fun preparingAnotherLoginMethodCannotHideCancellationOrAnotherBusyAction() {
    assertEquals(
        WearAction.Login,
        busyActionAfterPreparingLoginMethod(
            current = WearAction.Login,
            loginCancellationInProgress = true,
        ),
    )
    assertNull(
        busyActionAfterPreparingLoginMethod(
            current = WearAction.Login,
            loginCancellationInProgress = false,
        ))
    assertEquals(
        WearAction.Logout,
        busyActionAfterPreparingLoginMethod(
            current = WearAction.Logout,
            loginCancellationInProgress = false,
        ),
    )
  }

  @Test
  fun rebootNeverRestoresPersistedVpnState() {
    assertFalse(
        shouldRestoreVpnAfterBoot(
            desiredVpnEnabled = true,
            vpnAuthorized = true,
        ))
    assertFalse(
        shouldRestoreVpnAfterBoot(
            desiredVpnEnabled = false,
            vpnAuthorized = true,
        ))
    assertFalse(
        shouldRestoreVpnAfterBoot(
            desiredVpnEnabled = true,
            vpnAuthorized = false,
        ))
  }

  @Test
  fun vpnStartupTimeoutsPreserveTheNativeCausalResult() {
    val nativeAggregateReadinessBudgetMillis = 60_000L
    assertTrue(nativeAggregateReadinessBudgetMillis < WANT_RUNNING_LOCAL_API_TIMEOUT_MILLIS)
    assertTrue(WANT_RUNNING_LOCAL_API_TIMEOUT_MILLIS < VPN_START_WATCHDOG_MILLIS)
  }

  @Test
  fun bootAndAppUpdateRunOnlyThePassiveContainmentMigration() {
    assertTrue(
        shouldRunPassiveContainmentMigration(android.content.Intent.ACTION_MY_PACKAGE_REPLACED))
    assertTrue(shouldRunPassiveContainmentMigration(android.content.Intent.ACTION_BOOT_COMPLETED))
    assertFalse(shouldRunPassiveContainmentMigration(android.content.Intent.ACTION_TIME_CHANGED))
  }

  @Test
  fun persistedPrivate5ToggleWorkersAreSuccessfulNoOpsAfterAnUpgrade() {
    val context = mock<Context>()
    whenever(context.applicationContext).thenReturn(context)
    val parameters = mock<WorkerParameters>()

    // These complete without an initialized App singleton. Any retained startVPN()/stopVPN()
    // mutation would fail here and, more importantly, could override a newer private.6 command.
    assertTrue(StartVPNWorker(context, parameters).doWork().toString().startsWith("Success"))
    assertTrue(StopVPNWorker(context, parameters).doWork().toString().startsWith("Success"))
  }

  @Test
  fun onlyTheNewestCompleteProfilePairCanPublish() {
    val coordinator = LatestPairedLoadCoordinator<List<String>, String>()
    val older = coordinator.begin()
    val newer = coordinator.begin()

    assertNull(coordinator.acceptLeft(newer, Result.success(listOf("B"))))
    val accepted = coordinator.acceptRight(newer, Result.success("B"))!!.getOrThrow()
    assertEquals(newer, accepted.generation)
    assertEquals(listOf("B"), accepted.left)
    assertEquals("B", accepted.right)

    assertNull(coordinator.acceptRight(older, Result.success("A")))
    assertNull(coordinator.acceptLeft(older, Result.success(listOf("A"))))
  }

  @Test
  fun profileReadMustBeStableAcrossBothCurrentProfileReads() {
    assertTrue(
        profileReadIsCoherent(
            beforeCurrentId = "B",
            listedProfileIds = setOf("A", "B"),
            afterCurrentId = "B",
        ))
    assertFalse(
        profileReadIsCoherent(
            beforeCurrentId = "A",
            listedProfileIds = setOf("A", "B"),
            afterCurrentId = "B",
        ))
    assertFalse(
        profileReadIsCoherent(
            beforeCurrentId = "B",
            listedProfileIds = setOf("A"),
            afterCurrentId = "B",
        ))
  }

  @Test
  fun incoherentCurrentListReadRetriesInCurrentListCurrentOrder() = runTest {
    val pending = profile("pending-profile")
    val currentReads = listOf(pending, pending, null, null)
    val profileReads = listOf(emptyList<IpnLocal.LoginProfile>(), emptyList())
    val calls = mutableListOf<String>()
    var currentIndex = 0
    var profilesIndex = 0

    val result =
        readCoherentProfileSnapshot(
            attempts = 2,
            readCurrent = {
              calls += "current"
              Result.success(currentReads[currentIndex++])
            },
            readProfiles = {
              calls += "profiles"
              Result.success(profileReads[profilesIndex++])
            },
            profileId = { it.ID },
        )

    val snapshot = result.getOrThrow()
    assertNull(snapshot.afterCurrent)
    assertTrue(snapshot.profiles.isEmpty())
    assertEquals(
        listOf("current", "profiles", "current", "current", "profiles", "current"),
        calls,
    )
  }

  @Test
  fun incoherentCurrentListReadIsNeverPublishedWhenRetriesAreExhausted() = runTest {
    val pending = profile("pending-profile")

    val result =
        readCoherentProfileSnapshot(
            attempts = 1,
            readCurrent = { Result.success(pending) },
            readProfiles = { Result.success(emptyList()) },
            profileId = { it.ID },
        )

    assertTrue(result.isFailure)
  }

  @Test
  fun logoutRequiresFreshBackendCurrentToMatchTheDisplayedProfile() {
    val accountA = profile("A")
    val accountB = profile("B")

    assertEquals(
        accountB,
        selectProfileForLogout(
            displayedProfileId = "B",
            currentProfile = accountB,
        ),
    )
    assertNull(
        selectProfileForLogout(
            displayedProfileId = "B",
            currentProfile = accountA,
        ))
  }

  @Test
  fun machineApprovalCleanupNeverOwnsAPreExistingCurrentOrDormantProfile() {
    val baseline =
        LoginProfileBaseline(
            currentProfileId = "existing-A",
            profileIds = setOf("existing-A", "dormant-B"),
        )

    val newProfile =
        resolveLoginOwnedMachineAuthProfile(
            beforeCancellationCurrentProfileId = "pending-C",
            afterCancellationCurrentProfileId = "pending-C",
            afterCancellationProfileIds = setOf("existing-A", "dormant-B", "pending-C"),
            profileBaseline = baseline,
        )
    assertTrue(newProfile.ownershipIsSafe)
    assertEquals("pending-C", newProfile.profileId)

    val dormantProfile =
        resolveLoginOwnedMachineAuthProfile(
            beforeCancellationCurrentProfileId = "dormant-B",
            afterCancellationCurrentProfileId = "dormant-B",
            afterCancellationProfileIds = baseline.profileIds,
            profileBaseline = baseline,
        )
    assertTrue(dormantProfile.ownershipIsSafe)
    assertNull(dormantProfile.profileId)

    val restoredWithoutBaseline =
        resolveLoginOwnedMachineAuthProfile(
            beforeCancellationCurrentProfileId = "unknown-profile",
            afterCancellationCurrentProfileId = "unknown-profile",
            afterCancellationProfileIds = setOf("unknown-profile"),
            profileBaseline = null,
        )
    assertFalse(restoredWithoutBaseline.ownershipIsSafe)
    assertNull(restoredWithoutBaseline.profileId)

    val ambiguousNewProfiles =
        resolveLoginOwnedMachineAuthProfile(
            beforeCancellationCurrentProfileId = null,
            afterCancellationCurrentProfileId = "new-C",
            afterCancellationProfileIds = baseline.profileIds + setOf("new-C", "new-D"),
            profileBaseline = baseline,
        )
    assertFalse(ambiguousNewProfiles.ownershipIsSafe)
    assertNull(ambiguousNewProfiles.profileId)
  }

  @Test
  fun lateMachineApprovalAfterANullPreReadStillBindsTheLoginOwnedProfile() {
    val ownership =
        resolveLoginOwnedMachineAuthProfile(
            beforeCancellationCurrentProfileId = null,
            afterCancellationCurrentProfileId = "late-approved-B",
            afterCancellationProfileIds = setOf("existing-A", "late-approved-B"),
            profileBaseline =
                LoginProfileBaseline(
                    currentProfileId = "existing-A",
                    profileIds = setOf("existing-A"),
                ),
        )

    assertTrue(ownership.ownershipIsSafe)
    assertEquals("late-approved-B", ownership.profileId)
  }

  @Test
  fun cleanupRefusesConflictingPreAndPostCancellationOwners() {
    val ownership =
        resolveLoginOwnedMachineAuthProfile(
            beforeCancellationCurrentProfileId = "pending-B",
            afterCancellationCurrentProfileId = "different-C",
            afterCancellationProfileIds = setOf("existing-A", "pending-B", "different-C"),
            profileBaseline =
                LoginProfileBaseline(
                    currentProfileId = "existing-A",
                    profileIds = setOf("existing-A"),
                ),
        )

    assertFalse(ownership.ownershipIsSafe)
    assertNull(ownership.profileId)
  }

  @Test
  fun cleanupRetryKeepsTheExactPreviouslyBoundProfile() {
    val baseline =
        LoginProfileBaseline(
            currentProfileId = "existing-A",
            profileIds = setOf("existing-A"),
        )

    val ownership =
        resolveLoginOwnedMachineAuthProfile(
            beforeCancellationCurrentProfileId = "existing-A",
            afterCancellationCurrentProfileId = "existing-A",
            afterCancellationProfileIds = setOf("existing-A"),
            profileBaseline = baseline,
            retainedOwnedProfileId = "pending-B",
        )

    assertTrue(ownership.ownershipIsSafe)
    assertEquals("pending-B", ownership.profileId)
  }

  @Test
  fun loginUrlIsValidatedOnlyAgainstTheCurrentInMemoryAttempt() {
    val oldUrl = "https://login.tailscale.com/a/old"
    val newUrl = "https://login.tailscale.com/a/new"

    assertFalse(shouldAcceptLoginUrl(null, newUrl))
    assertFalse(shouldAcceptLoginUrl(7L, "  "))
    assertTrue(shouldAcceptLoginUrl(7L, oldUrl))
    assertTrue(shouldAcceptLoginUrl(7L, newUrl))
  }

  @Test
  fun staleLoginGenerationCannotCompleteOrCancelANewerAttempt() {
    assertFalse(isCurrentLoginAttempt(activeGeneration = 8L, expectedGeneration = 7L))
    assertTrue(isCurrentLoginAttempt(activeGeneration = 8L, expectedGeneration = 8L))
  }

  @Test
  fun staleForegroundPromotionCannotReplaceOrReleaseTheNewerLoginLease() {
    val released = mutableListOf<String>()
    var activeLease: String? = null
    val adopt: (String) -> Unit = { lease ->
      activeLease?.let(released::add)
      activeLease = lease
    }

    assertTrue(
        handleLoginForegroundPromotion(
            activeGeneration = 8L,
            expectedGeneration = 8L,
            lease = "lease-b",
            adopt = adopt,
            release = released::add,
        ))
    assertFalse(
        handleLoginForegroundPromotion(
            activeGeneration = 8L,
            expectedGeneration = 7L,
            lease = "lease-a",
            adopt = adopt,
            release = released::add,
        ))

    assertEquals("lease-b", activeLease)
    assertEquals(listOf("lease-a"), released)
  }

  @Test
  fun phoneOpenIsOneFlightWithinTheCurrentLoginAttempt() {
    val coordinator = PhoneLoginOpenCoordinator()
    val first =
        coordinator.begin(
            url = "https://login.tailscale.com/a/shared",
            loginAttemptGeneration = 7L,
        )

    assertTrue(first != null)
    assertNull(
        coordinator.begin(
            url = "https://login.tailscale.com/a/shared",
            loginAttemptGeneration = 7L,
        ))
    assertTrue(coordinator.finish(first!!))
  }

  @Test
  fun reusedUrlDoesNotLetAnOldPhoneCallbackFinishANewerLoginAttempt() {
    val coordinator = PhoneLoginOpenCoordinator()
    val oldAttempt =
        coordinator.begin(
            url = "https://headscale.example/login",
            loginAttemptGeneration = 7L,
        )!!
    val newAttempt =
        coordinator.begin(
            url = "https://headscale.example/login",
            loginAttemptGeneration = 8L,
        )!!

    assertFalse(coordinator.finish(oldAttempt))
    assertTrue(coordinator.finish(newAttempt))
  }

  @Test
  fun activityRecreationCancelsOnlyItsExactPhoneOpenAttempt() {
    val coordinator = PhoneLoginOpenCoordinator()
    val destroyedActivityAttempt =
        coordinator.begin(
            url = "https://login.tailscale.com/a/shared",
            loginAttemptGeneration = 7L,
        )!!
    val currentActivityAttempt =
        coordinator.begin(
            url = "https://login.tailscale.com/a/shared",
            loginAttemptGeneration = 8L,
        )!!

    assertFalse(coordinator.cancel(destroyedActivityAttempt))
    assertTrue(coordinator.cancel(currentActivityAttempt))
    assertTrue(
        coordinator.begin(
            url = "https://login.tailscale.com/a/shared",
            loginAttemptGeneration = 8L,
        ) != null)
  }

  @Test
  fun loginSuccessDoesNotRearmTimeoutAfterAUrlOrMachineApprovalArrived() {
    assertFalse(
        shouldArmLoginCompletionTimeout(
            loginUrl = "https://login.tailscale.com/a/current",
            accountState = WearAccountState.Authenticating,
        ))
    assertFalse(
        shouldArmLoginCompletionTimeout(
            loginUrl = null,
            accountState = WearAccountState.NeedsMachineAuthorization,
        ))
    assertTrue(
        shouldArmLoginCompletionTimeout(
            loginUrl = null,
            accountState = WearAccountState.Authenticating,
        ))
    assertTrue(
        shouldCancelLoginCompletionTimeout(
            busyAction = WearAction.Login,
            loginUrl = null,
            accountState = WearAccountState.NeedsMachineAuthorization,
        ))
    assertFalse(
        shouldCancelLoginCompletionTimeout(
            busyAction = WearAction.Login,
            loginUrl = null,
            accountState = WearAccountState.Authenticating,
        ))
  }

  @Test
  fun wearLoginFailureRetainsForegroundProtectionUntilCancellationCleanup() {
    val failure = Result.failure<Unit>(IllegalStateException("login failed"))

    assertFalse(
        shouldReleaseLoginForegroundAfterCompletion(
            result = failure,
            retainForegroundOnFailure = true,
        ))
    assertTrue(
        shouldReleaseLoginForegroundAfterCompletion(
            result = failure,
            retainForegroundOnFailure = false,
        ))
    assertFalse(
        shouldReleaseLoginForegroundAfterCompletion(
            result = Result.success(Unit),
            retainForegroundOnFailure = false,
        ))
  }

  @Test
  fun callbackQuiescenceAndCleanupShareOneTotalDeadline() = runTest {
    val stuckRequest = Job()
    var cleanupCalled = false

    val timedOut =
        runBoundedLoginCleanup(
            quiescence = stuckRequest,
            timeoutMillis = 100L,
        ) {
          cleanupCalled = true
          Result.success(Unit)
        }

    assertTrue(timedOut.isFailure)
    assertFalse(cleanupCalled)
    stuckRequest.cancel()

    val completedRequest = Job().apply { complete() }
    val completed =
        runBoundedLoginCleanup(
            quiescence = completedRequest,
            timeoutMillis = 100L,
        ) {
          cleanupCalled = true
          Result.success("clean")
        }
    assertEquals("clean", completed.getOrThrow())
    assertTrue(cleanupCalled)
  }

  @Test
  fun timedOutLoginCleanupCanResumeBehindTheSameRetainedOwner() = runTest {
    val retainedOwner = Job()
    var cleanupCalls = 0
    launch { retainedOwner.complete() }

    val result =
        resumeLoginCleanupAfterRetainedQuiescence(
            quiescence = retainedOwner,
            timeoutMillis = 100L,
        ) {
          cleanupCalls += 1
          Result.success("clean")
        }

    assertEquals("clean", result.getOrThrow())
    assertEquals(1, cleanupCalls)
  }

  @Test
  fun retainedOwnerHardTimeoutNeverRunsCleanup() = runTest {
    val retainedOwner = Job()
    var cleanupCalled = false

    val result =
        resumeLoginCleanupAfterRetainedQuiescence(
            quiescence = retainedOwner,
            timeoutMillis = 100L,
        ) {
          cleanupCalled = true
          Result.success(Unit)
        }

    assertTrue(result.isFailure)
    assertFalse(cleanupCalled)
    retainedOwner.cancel()
  }

  @Test
  fun machineAuthCleanupTimeoutWaitsForItsOwnedIoChildrenBeforeReturning() = runTest {
    val childFinished = CompletableDeferred<Unit>()

    val result =
        runOwnedMachineAuthCleanupIo(timeoutMillis = 100L) {
          launch {
            try {
              awaitCancellation()
            } finally {
              childFinished.complete(Unit)
            }
          }
          awaitCancellation()
        }

    assertTrue(result.isFailure)
    assertTrue(childFinished.isCompleted)
  }

  @Test
  fun cleanupTimeoutWaitsForTheApplicationScopedPatchCallback() = runTest {
    lateinit var completePatch: (Result<Unit>) -> Unit
    val cleanup = async {
      runOwnedMachineAuthCleanupIo(timeoutMillis = 100L) {
        awaitCleanupMutationCompletion<Unit> { completion -> completePatch = completion }
      }
    }

    runCurrent()
    advanceTimeBy(100L)
    runCurrent()
    assertFalse(cleanup.isCompleted)

    completePatch(Result.success(Unit))
    runCurrent()
    assertTrue(cleanup.await().isFailure)
  }

  @Test
  fun outerTimeoutCannotSkipTheMandatoryPostCancellationProfileRead() = runTest {
    var verificationRan = false

    val result = runCatching {
      withTimeout(100L) {
        runWithMandatoryPostCancellationVerification(
            cancellation = {
              withContext(NonCancellable) { delay(200L) }
              Unit
            },
            verification = { verificationRan = true },
        )
      }
    }

    assertTrue(result.isFailure)
    assertTrue(verificationRan)
  }

  @Test
  fun delayedDeleteAndRetry404ResolveOnlyAfterExactCoherentAbsence() = runTest {
    var deleteCalls = 0
    var activeDeletes = 0
    var maximumActiveDeletes = 0
    var exactProfilePresent = true

    suspend fun cleanupAttempt(): Result<Unit> =
        runOwnedMachineAuthCleanupIo(timeoutMillis = 100L) {
          val cleanupScope = this
          deleteExactProfileWithFreshVerification(
                  expectedProfileId = "pending-profile",
                  delete = {
                    suspendCancellableCoroutine { continuation ->
                      cleanupScope.launch {
                        deleteCalls += 1
                        activeDeletes += 1
                        maximumActiveDeletes = maxOf(maximumActiveDeletes, activeDeletes)
                        val deleteResult =
                            try {
                              if (deleteCalls == 1) {
                                withContext(NonCancellable) {
                                  delay(200L)
                                  exactProfilePresent = false
                                }
                                Result.success(Unit)
                              } else {
                                Result.failure(
                                    IllegalStateException("Request failed with status 404"))
                              }
                            } finally {
                              activeDeletes -= 1
                            }
                        if (continuation.isActive) {
                          continuation.resume(deleteResult)
                        }
                      }
                    }
                  },
                  freshVerification = {
                    Result.success(
                        ProfileCleanupVerification(
                            beforeCurrentProfileId = null,
                            listedProfileIds =
                                if (exactProfilePresent) setOf("pending-profile") else emptySet(),
                            afterCurrentProfileId = null,
                        ))
                  },
              )
              .getOrThrow()
        }

    val firstAttempt = async { cleanupAttempt() }
    runCurrent()
    advanceTimeBy(100L)
    runCurrent()
    assertFalse(firstAttempt.isCompleted)

    advanceTimeBy(100L)
    runCurrent()
    assertTrue(firstAttempt.await().isFailure)

    val retry = cleanupAttempt()
    assertTrue(retry.isSuccess)
    assertEquals(2, deleteCalls)
    assertEquals(1, maximumActiveDeletes)
  }

  @Test
  fun delete404NeverCleansAnIncoherentCurrentListSnapshot() = runTest {
    val result =
        deleteExactProfileWithFreshVerification(
            expectedProfileId = "pending-profile",
            delete = { Result.failure(IllegalStateException("Request failed with status 404")) },
            freshVerification = {
              Result.success(
                  ProfileCleanupVerification(
                      beforeCurrentProfileId = "pending-profile",
                      listedProfileIds = emptySet(),
                      afterCurrentProfileId = "pending-profile",
                  ))
            },
        )

    assertTrue(result.isFailure)
  }

  @Test
  fun explicitMachineApprovalCancelDeletesOnlyTheExactStillPendingProfile() {
    assertTrue(
        shouldDeletePendingMachineAuthProfile(
            expectedProfileId = "pending-profile",
            currentSelectedProfileId = "pending-profile",
            pendingProfileExists = true,
            backendState = Ipn.State.NeedsMachineAuth,
        ))
    assertFalse(
        shouldDeletePendingMachineAuthProfile(
            expectedProfileId = "pending-profile",
            currentSelectedProfileId = null,
            pendingProfileExists = false,
            backendState = Ipn.State.NeedsLogin,
        ))
    assertFalse(
        shouldDeletePendingMachineAuthProfile(
            expectedProfileId = "pending-profile",
            currentSelectedProfileId = "pending-profile",
            pendingProfileExists = false,
            backendState = Ipn.State.NeedsMachineAuth,
        ))
    assertFalse(
        shouldDeletePendingMachineAuthProfile(
            expectedProfileId = "pending-profile",
            currentSelectedProfileId = "new-profile",
            pendingProfileExists = true,
            backendState = Ipn.State.NeedsMachineAuth,
        ))
    assertFalse(
        shouldDeletePendingMachineAuthProfile(
            expectedProfileId = "pending-profile",
            currentSelectedProfileId = "pending-profile",
            pendingProfileExists = true,
            backendState = Ipn.State.Running,
        ))
    assertTrue(
        shouldDeletePendingMachineAuthProfile(
            expectedProfileId = "pending-profile",
            currentSelectedProfileId = "pending-profile",
            pendingProfileExists = true,
            backendState = Ipn.State.Stopped,
            exactLoginOwnershipKnown = true,
        ))
    assertTrue(
        shouldDeletePendingMachineAuthProfile(
            expectedProfileId = "pending-profile",
            currentSelectedProfileId = "existing-profile",
            pendingProfileExists = true,
            backendState = Ipn.State.Stopped,
            exactLoginOwnershipKnown = true,
            allowedNonPendingSelectedProfileIds = setOf("existing-profile"),
        ))
    assertFalse(
        shouldDeletePendingMachineAuthProfile(
            expectedProfileId = "pending-profile",
            currentSelectedProfileId = "different-profile",
            pendingProfileExists = true,
            backendState = Ipn.State.Stopped,
            exactLoginOwnershipKnown = true,
        ))
  }

  @Test
  fun missingExpectedProfileIsCleanOnlyInACoherentFreshSnapshot() {
    assertTrue(
        machineAuthProfileCleanupPostconditionSatisfied(
            expectedProfileId = "pending-profile",
            beforeCurrentSelectedProfileId = null,
            listedProfileIds = emptySet(),
            afterCurrentSelectedProfileId = null,
        ))
    assertTrue(
        machineAuthProfileCleanupPostconditionSatisfied(
            expectedProfileId = "pending-profile",
            beforeCurrentSelectedProfileId = "new-profile",
            listedProfileIds = setOf("new-profile"),
            afterCurrentSelectedProfileId = "new-profile",
        ))
    assertFalse(
        machineAuthProfileCleanupPostconditionSatisfied(
            expectedProfileId = "pending-profile",
            beforeCurrentSelectedProfileId = "pending-profile",
            listedProfileIds = emptySet(),
            afterCurrentSelectedProfileId = "pending-profile",
        ))
    assertFalse(
        machineAuthProfileCleanupPostconditionSatisfied(
            expectedProfileId = "pending-profile",
            beforeCurrentSelectedProfileId = "new-profile",
            listedProfileIds = setOf("pending-profile"),
            afterCurrentSelectedProfileId = "new-profile",
        ))
  }

  @Test
  fun needsLoginWinsOverAStillCachedProfileAndKeepsReauthenticationVisible() {
    assertEquals(
        WearAccountState.Authenticating,
        resolveWearAccountState(
            ipnState = Ipn.State.NeedsLogin,
            hasProfile = true,
            hasLoginUrl = true,
            hasActiveLoginAttempt = true,
        ),
    )
    assertEquals(
        WearAccountState.SignedOut,
        resolveWearAccountState(
            ipnState = Ipn.State.NeedsLogin,
            hasProfile = true,
            hasLoginUrl = false,
            hasActiveLoginAttempt = false,
        ),
    )
    assertEquals(
        WearAccountState.NeedsMachineAuthorization,
        resolveWearAccountState(
            ipnState = Ipn.State.NeedsMachineAuth,
            hasProfile = true,
            hasLoginUrl = true,
            hasActiveLoginAttempt = true,
        ),
    )
  }

  @Test
  fun recreatedMachineAuthorizationCanRecoverWithoutAnInMemoryAttempt() {
    assertEquals(
        WearAccountState.SignedOut,
        resolveWearAccountState(
            ipnState = Ipn.State.NeedsMachineAuth,
            hasProfile = false,
            hasLoginUrl = false,
            hasActiveLoginAttempt = false,
        ),
    )
    assertTrue(
        shouldRecoverMachineAuthCancellation(
            accountState = WearAccountState.NeedsMachineAuthorization,
            activeLoginAttemptGeneration = null,
        ))
    assertFalse(
        shouldRecoverMachineAuthCancellation(
            accountState = WearAccountState.NeedsMachineAuthorization,
            activeLoginAttemptGeneration = 7L,
        ))
    assertFalse(
        shouldRecoverMachineAuthCancellation(
            accountState = WearAccountState.SignedIn,
            activeLoginAttemptGeneration = null,
        ))
  }

  @Test
  fun cachedProfileIsNotSignedInBeforeTheBackendPublishesAReadyState() {
    assertFalse(isWearAccountReady(Ipn.State.NoState, hasProfile = true))
    assertFalse(isWearAccountReady(Ipn.State.InUseOtherUser, hasProfile = true))
    assertEquals(
        WearAccountState.Loading,
        resolveWearAccountState(
            ipnState = Ipn.State.NoState,
            hasProfile = true,
            hasLoginUrl = false,
            hasActiveLoginAttempt = false,
        ),
    )
    assertEquals(
        WearAccountState.Loading,
        resolveWearAccountState(
            ipnState = Ipn.State.InUseOtherUser,
            hasProfile = true,
            hasLoginUrl = false,
            hasActiveLoginAttempt = false,
        ),
    )
    assertTrue(isWearAccountReady(Ipn.State.Stopped, hasProfile = true))
  }

  @Test
  fun everyNonOnlineNetworkStateUsesLocalProfileRemoval() {
    assertTrue(shouldUseLocalOnlyLogout(UnderlyingNetworkStatus.Offline))
    assertTrue(shouldUseLocalOnlyLogout(UnderlyingNetworkStatus.Limited))
    assertTrue(shouldUseLocalOnlyLogout(UnderlyingNetworkStatus.Unknown))
    assertFalse(shouldUseLocalOnlyLogout(UnderlyingNetworkStatus.Online))
  }

  @Test
  fun pingFailureDiagnosticsMapOnlyToStableLocalizedCategories() {
    assertEquals(PingFailureCategory.TimedOut, pingFailureCategory("context deadline exceeded"))
    assertEquals(PingFailureCategory.NoDiagnostic, pingFailureCategory("  \n  "))
    assertEquals(PingFailureCategory.BackendFailure, pingFailureCategory("no matching peer."))
  }

  @Test
  fun rapidOnOffOnAcceptsOnlyTheLastCommand() {
    val finalState = DesiredVpnState(enabled = true, generation = 3L)

    assertFalse(
        isCurrentVpnCommand(
            commandGeneration = 1L,
            requestedEnabled = true,
            currentState = finalState,
        ))
    assertFalse(
        isCurrentVpnCommand(
            commandGeneration = 2L,
            requestedEnabled = false,
            currentState = finalState,
        ))
    assertTrue(
        isCurrentVpnCommand(
            commandGeneration = 3L,
            requestedEnabled = true,
            currentState = finalState,
        ))
  }

  @Test
  fun persistedLoginCleanupStopDispatchesOnlyWhileItsExactOffGenerationStillOwnsState() {
    val cleanupOff = DesiredVpnState(enabled = false, generation = 4L)

    assertTrue(shouldDispatchPersistedVpnCommand(cleanupOff, cleanupOff))
    assertFalse(
        shouldDispatchPersistedVpnCommand(
            cleanupOff,
            DesiredVpnState(enabled = true, generation = 5L),
        ))
  }

  @Test
  fun ownedForegroundReleaseSkipsADuplicatePatchOnlyAfterVerifiedOffCleanup() {
    val cleanupOff = DesiredVpnState(enabled = false, generation = 4L)
    val newerOn = DesiredVpnState(enabled = true, generation = 5L)

    assertTrue(
        shouldCloseForegroundAfterOwnedBackendStop(
            backendAlreadyStopped = true,
            desiredState = cleanupOff,
        ))
    assertFalse(
        shouldCloseForegroundAfterOwnedBackendStop(
            backendAlreadyStopped = false,
            desiredState = cleanupOff,
        ))
    assertFalse(
        shouldCloseForegroundAfterOwnedBackendStop(
            backendAlreadyStopped = true,
            desiredState = newerOn,
        ))
  }

  @Test
  fun cancellationCompletesOnlyAfterItsExactForegroundLeaseWasReleased() {
    assertTrue(exactForegroundCleanupReleaseSucceeded(hadLease = true, dispatched = true))
    assertFalse(exactForegroundCleanupReleaseSucceeded(hadLease = true, dispatched = false))
    assertFalse(exactForegroundCleanupReleaseSucceeded(hadLease = false, dispatched = true))
  }

  @Test
  fun technicalRestartNeverTurnsAnExplicitlyDisabledVpnOn() {
    assertFalse(shouldDispatchVpnRestart(desiredVpnEnabled = false))
    assertTrue(shouldDispatchVpnRestart(desiredVpnEnabled = true))
  }
}
