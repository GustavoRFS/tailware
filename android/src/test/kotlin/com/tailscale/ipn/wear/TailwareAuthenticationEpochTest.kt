// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.AuthenticationEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailwareAuthenticationEpochTest {
  @Test
  fun delayedUrlFromAttemptACannotReplaceAttemptB() {
    val staleUrl =
        AuthenticationEvent(
            sequence = 1,
            epoch = 11,
            browseToURL = "https://login.tailscale.com/a/attempt-a",
        )

    assertFalse(isAuthenticationEventOwnedBy(activeAuthenticationEpoch = 12, staleUrl.epoch))
  }

  @Test
  fun delayedErrorFromAttemptACannotCancelAttemptB() {
    val staleError =
        AuthenticationEvent(
            sequence = 2,
            epoch = 21,
            errMessage = "attempt A was denied",
        )

    assertFalse(isAuthenticationEventOwnedBy(activeAuthenticationEpoch = 22, staleError.epoch))
  }

  @Test
  fun delayedCompletionFromAttemptACannotCompleteAttemptB() {
    val staleCompletion =
        AuthenticationEvent(
            sequence = 3,
            epoch = 31,
            loginFinished = true,
        )

    assertFalse(authenticationEventProvesReady(32, staleCompletion))
  }

  @Test
  fun approvedProfileFromAttemptACannotAutoconnectAsAttemptB() {
    val staleReady =
        AuthenticationEvent(
            sequence = 4,
            epoch = 41,
            state = Ipn.State.Running,
            hasIdentityMutation = true,
        )

    assertFalse(authenticationEventProvesReady(42, staleReady))
    assertFalse(
        hasOwnedAuthenticationReadyEvidence(
            activeAuthenticationEpoch = 42,
            readyAuthenticationEpoch = staleReady.epoch,
        ))
  }

  @Test
  fun currentAttemptCanPublishUrlAndReadyEvidence() {
    val currentUrl =
        AuthenticationEvent(
            sequence = 5,
            epoch = 52,
            browseToURL = "https://headscale.example/register",
        )
    val currentReady =
        AuthenticationEvent(
            sequence = 6,
            epoch = 52,
            state = Ipn.State.Running,
        )

    assertTrue(isAuthenticationEventOwnedBy(52, currentUrl.epoch))
    assertTrue(authenticationEventProvesReady(52, currentReady))
    assertTrue(hasOwnedAuthenticationReadyEvidence(52, currentReady.epoch))
  }

  @Test
  fun bootstrapUrlRemainsPresentableButCannotProveCompletion() {
    val bootstrapUrl =
        AuthenticationEvent(
            sequence = 7,
            epoch = 53,
            browseToURL = "https://login.tailscale.com/a/bootstrap",
            state = Ipn.State.Running,
            loginFinished = true,
            bootstrap = true,
        )

    assertTrue(authenticationEventMayPresentLoginUrl(bootstrapUrl))
    assertTrue(shouldAcceptLoginUrl(activeAttemptGeneration = 8, bootstrapUrl.browseToURL))
    assertTrue(
        shouldReplayOwnedBootstrapLoginUrl(
            acceptLoginUrls = true,
            activeAttemptGeneration = 8,
            activeAuthenticationEpoch = 53,
            event = bootstrapUrl,
        ))
    assertFalse(authenticationEventProvesReady(53, bootstrapUrl))
  }

  @Test
  fun completedLoginRejectsDelayedSameEpochUrl() {
    val delayedUrl =
        AuthenticationEvent(
            sequence = 8,
            epoch = 54,
            browseToURL = "https://login.tailscale.com/a/delayed",
        )

    assertTrue(
        shouldPresentOwnedAuthenticationUrl(
            acceptLoginUrls = true,
            activeAttemptGeneration = 9,
            activeAuthenticationEpoch = 54,
            event = delayedUrl,
        ))
    assertFalse(
        shouldPresentOwnedAuthenticationUrl(
            acceptLoginUrls = false,
            activeAttemptGeneration = 9,
            activeAuthenticationEpoch = 54,
            event = delayedUrl,
        ))
  }

  @Test
  fun destructiveLogoutBlocksBackendUrlAdoption() {
    assertTrue(
        authenticationAdoptionBlockedByLogout(
            busyAction = WearAction.Logout,
            hasDestructiveLogoutOwner = false,
        ))
    assertTrue(
        authenticationAdoptionBlockedByLogout(
            busyAction = null,
            hasDestructiveLogoutOwner = true,
        ))
    assertFalse(
        authenticationAdoptionBlockedByLogout(
            busyAction = null,
            hasDestructiveLogoutOwner = false,
        ))
  }

  @Test
  fun strictlyNewerEpochSupersedesAnActiveWearOwner() {
    assertFalse(shouldSupersedeAuthenticationOwner(null, eventEpoch = 72))
    assertFalse(shouldSupersedeAuthenticationOwner(71, eventEpoch = 70))
    assertFalse(shouldSupersedeAuthenticationOwner(71, eventEpoch = 71))
    assertTrue(shouldSupersedeAuthenticationOwner(71, eventEpoch = 72))
  }

  @Test
  fun retiringEpochKeepsExactWearOwnershipAndRejectsSameEpochEvents() {
    val owner = AuthenticationRetirementOwner(epoch = 61, loginAttemptGeneration = 7)

    assertTrue(shouldIgnoreAuthenticationEventDuringRetirement(owner, eventEpoch = 61))
    assertFalse(shouldIgnoreAuthenticationEventDuringRetirement(owner, eventEpoch = 62))
    assertFalse(shouldSupersedeAuthenticationRetirement(owner, eventEpoch = 60))
    assertFalse(shouldSupersedeAuthenticationRetirement(owner, eventEpoch = 61))
    assertTrue(shouldSupersedeAuthenticationRetirement(owner, eventEpoch = 62))
    assertTrue(
        authenticationRetirementIsStillOwned(
            retirementOwner = owner,
            activeAuthenticationEpoch = 61,
            activeLoginAttemptGeneration = 7,
        ))
    assertFalse(
        authenticationRetirementIsStillOwned(
            retirementOwner = owner,
            activeAuthenticationEpoch = 61,
            activeLoginAttemptGeneration = 8,
        ))
    assertFalse(
        authenticationRetirementIsStillOwned(
            retirementOwner = owner,
            activeAuthenticationEpoch = 62,
            activeLoginAttemptGeneration = 7,
        ))
    assertEquals(
        AuthenticationRetirementCompletion.RetainForRetry,
        authenticationRetirementCompletion(
            retirementOwner = owner,
            activeAuthenticationEpoch = 61,
            activeLoginAttemptGeneration = 7,
            nativeRetired = false,
        ),
    )
    assertEquals(
        AuthenticationRetirementCompletion.ClearOwner,
        authenticationRetirementCompletion(
            retirementOwner = owner,
            activeAuthenticationEpoch = 61,
            activeLoginAttemptGeneration = 7,
            nativeRetired = true,
        ),
    )
    assertEquals(
        AuthenticationRetirementCompletion.IgnoreSuperseded,
        authenticationRetirementCompletion(
            retirementOwner = owner,
            activeAuthenticationEpoch = 62,
            activeLoginAttemptGeneration = 7,
            nativeRetired = false,
        ),
    )
    assertTrue(
        authenticationRetirementOwnsUiSettlement(
            expectedActionGeneration = 9,
            currentActionGeneration = 9,
            currentBusyAction = null,
        ))
    assertFalse(
        authenticationRetirementOwnsUiSettlement(
            expectedActionGeneration = 9,
            currentActionGeneration = 10,
            currentBusyAction = null,
        ))
    assertFalse(
        authenticationRetirementOwnsUiSettlement(
            expectedActionGeneration = 9,
            currentActionGeneration = 9,
            currentBusyAction = WearAction.Connect,
        ))
    assertFalse(
        authenticationRetirementOwnsUiSettlement(
            expectedActionGeneration = null,
            currentActionGeneration = 9,
            currentBusyAction = null,
        ))
  }

  @Test
  fun cancellationRemovesOnlyProfileCreatedAfterItsExactBaseline() {
    val createdFromEmpty =
        resolveLoginOwnedMachineAuthProfile(
            beforeCancellationCurrentProfileId = "created-by-attempt-a",
            afterCancellationCurrentProfileId = "created-by-attempt-a",
            afterCancellationProfileIds = setOf("created-by-attempt-a"),
            profileBaseline =
                LoginProfileBaseline(currentProfileId = null, profileIds = emptySet()),
        )
    assertTrue(createdFromEmpty.ownershipIsSafe)
    assertEquals("created-by-attempt-a", createdFromEmpty.profileId)

    val existingProfile =
        resolveLoginOwnedMachineAuthProfile(
            beforeCancellationCurrentProfileId = "existing-profile",
            afterCancellationCurrentProfileId = "existing-profile",
            afterCancellationProfileIds = setOf("existing-profile"),
            profileBaseline =
                LoginProfileBaseline(
                    currentProfileId = "existing-profile",
                    profileIds = setOf("existing-profile"),
                ),
        )
    assertTrue(existingProfile.ownershipIsSafe)
    assertNull(existingProfile.profileId)

    val unknownBaseline =
        resolveLoginOwnedMachineAuthProfile(
            beforeCancellationCurrentProfileId = "unknown-owner",
            afterCancellationCurrentProfileId = "unknown-owner",
            afterCancellationProfileIds = setOf("unknown-owner"),
            profileBaseline = null,
        )
    assertFalse(unknownBaseline.ownershipIsSafe)
    assertNull(unknownBaseline.profileId)
  }

  @Test
  fun unownedCancellationFailsClosedWhenANewerVpnCleanupAppears() {
    assertTrue(
        canReleaseAfterAwaitedBackendCleanup(
            authenticationEpoch = 41L,
            awaitedStopGeneration = 12L,
            pendingCleanupGeneration = 13L,
        ))
    assertTrue(
        canReleaseAfterAwaitedBackendCleanup(
            authenticationEpoch = null,
            awaitedStopGeneration = 13L,
            pendingCleanupGeneration = 13L,
        ))
    assertFalse(
        canReleaseAfterAwaitedBackendCleanup(
            authenticationEpoch = null,
            awaitedStopGeneration = 12L,
            pendingCleanupGeneration = 13L,
        ))
  }
}
