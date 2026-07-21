// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import com.tailscale.ipn.wear.ui.SensitiveLoginWindowCoordinator
import com.tailscale.ipn.wear.ui.isAuthKeyAuthenticationContinuation
import com.tailscale.ipn.wear.ui.loginProfileMutationAllowed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TailwareP1LifecycleRaceTest {
  @Test
  fun delayedSuccessfulLoginTransfersForegroundToNewerExactConnect() {
    // Login owns action 12 and observes VPN generation 30 before native retirement begins.
    // While retirement is delayed, a real Connect advances both ownership generations.
    val settlement =
        successfulLoginSettlementPlan(
            expectedActionGeneration = 12,
            desiredVpnGenerationBeforeRetirement = 30,
            currentActionGeneration = 13,
            currentBusyAction = WearAction.Connect,
            currentDesiredVpnState = DesiredVpnState(enabled = true, generation = 31),
            currentVpnCommandAuthorized = true,
        )

    assertFalse(settlement.ownsUiSettlement)
    assertEquals(
        SuccessfulLoginForegroundSettlement.TransferToNewerVpn,
        settlement.foregroundSettlement,
    )

    val newerConnectError = WearError(WearAction.Connect, "newer Connect still owns this state")
    val connectedUi =
        WearUiState(
            desiredVpnEnabled = true,
            tunnelStatus = WearTunnelStatus.Connecting,
            busyAction = WearAction.Connect,
            activeLoginMethod = LoginMethod.AuthenticationKey,
            loginUrl = "https://login.example/owned",
            loginCode = "owned-code",
            error = newerConnectError,
        )
    val afterDelayedSettlement =
        successfulLoginUiStateAfterSettlement(connectedUi, settlement.ownsUiSettlement)

    assertTrue(afterDelayedSettlement.desiredVpnEnabled)
    assertEquals(WearTunnelStatus.Connecting, afterDelayedSettlement.tunnelStatus)
    assertEquals(WearAction.Connect, afterDelayedSettlement.busyAction)
    assertEquals(newerConnectError, afterDelayedSettlement.error)
    assertNull(afterDelayedSettlement.activeLoginMethod)
    assertNull(afterDelayedSettlement.loginUrl)
    assertNull(afterDelayedSettlement.loginCode)
  }

  @Test
  fun newerExactConnectWinsEvenWhenItsUiActionGenerationHasNotPropagatedYet() {
    val settlement =
        successfulLoginSettlementPlan(
            expectedActionGeneration = 12,
            desiredVpnGenerationBeforeRetirement = 30,
            currentActionGeneration = 12,
            currentBusyAction = null,
            currentDesiredVpnState = DesiredVpnState(enabled = true, generation = 31),
            currentVpnCommandAuthorized = true,
        )

    assertFalse(settlement.ownsUiSettlement)
    assertEquals(
        SuccessfulLoginForegroundSettlement.TransferToNewerVpn,
        settlement.foregroundSettlement,
    )
  }

  @Test
  fun delayedSuccessfulLoginNeverTreatsForgedOrStaleDesiredOnAsExactConnect() {
    val unauthorized =
        successfulLoginSettlementPlan(
            expectedActionGeneration = 4,
            desiredVpnGenerationBeforeRetirement = 8,
            currentActionGeneration = 5,
            currentBusyAction = WearAction.Connect,
            currentDesiredVpnState = DesiredVpnState(enabled = true, generation = 9),
            currentVpnCommandAuthorized = false,
        )
    val staleGeneration =
        successfulLoginSettlementPlan(
            expectedActionGeneration = 4,
            desiredVpnGenerationBeforeRetirement = 9,
            currentActionGeneration = 5,
            currentBusyAction = WearAction.Connect,
            currentDesiredVpnState = DesiredVpnState(enabled = true, generation = 9),
            currentVpnCommandAuthorized = true,
        )
    val stillOwned =
        successfulLoginSettlementPlan(
            expectedActionGeneration = 4,
            desiredVpnGenerationBeforeRetirement = 9,
            currentActionGeneration = 4,
            currentBusyAction = null,
            currentDesiredVpnState = DesiredVpnState(enabled = false, generation = 9),
            currentVpnCommandAuthorized = false,
        )

    assertEquals(
        SuccessfulLoginForegroundSettlement.ReleaseWithoutVpnMutation,
        unauthorized.foregroundSettlement,
    )
    assertEquals(
        SuccessfulLoginForegroundSettlement.ReleaseWithoutVpnMutation,
        staleGeneration.foregroundSettlement,
    )
    assertTrue(stillOwned.ownsUiSettlement)
    assertEquals(SuccessfulLoginForegroundSettlement.StopWithOff, stillOwned.foregroundSettlement)
  }

  @Test
  fun authKeyMachineApprovalIsSameOwnerContinuationButRealNavigationCancels() {
    assertTrue(
        isAuthKeyAuthenticationContinuation(
            currentRoute = "auth_key",
            destination = "login_qr",
            accountState = WearAccountState.NeedsMachineAuthorization,
        ))
    assertFalse(
        isAuthKeyAuthenticationContinuation(
            currentRoute = "auth_key",
            destination = "settings",
            accountState = WearAccountState.NeedsMachineAuthorization,
        ))
    assertFalse(
        shouldCancelLoginMethodOnRouteDisposal(
            activityRecreationInProgress = false,
            disposedMethod = LoginMethod.AuthenticationKey,
            activeMethod = LoginMethod.AuthenticationKey,
            activeAttemptGeneration = 42,
            accountState = WearAccountState.NeedsMachineAuthorization,
            preserveAuthenticatedContinuation = true,
        ))
    assertFalse(
        shouldCancelLoginMethodOnRouteDisposal(
            activityRecreationInProgress = false,
            disposedMethod = LoginMethod.AuthenticationKey,
            activeMethod = LoginMethod.AuthenticationKey,
            activeAttemptGeneration = 42,
            accountState = WearAccountState.SignedIn,
            preserveAuthenticatedContinuation = true,
        ))
    assertTrue(
        shouldCancelLoginMethodOnRouteDisposal(
            activityRecreationInProgress = false,
            disposedMethod = LoginMethod.AuthenticationKey,
            activeMethod = LoginMethod.AuthenticationKey,
            activeAttemptGeneration = 42,
            accountState = WearAccountState.Authenticating,
            preserveAuthenticatedContinuation = true,
        ))
    assertTrue(
        shouldCancelLoginMethodOnRouteDisposal(
            activityRecreationInProgress = false,
            disposedMethod = LoginMethod.AuthenticationKey,
            activeMethod = LoginMethod.AuthenticationKey,
            activeAttemptGeneration = 42,
            accountState = WearAccountState.NeedsMachineAuthorization,
            preserveAuthenticatedContinuation = false,
        ))
    assertFalse(
        shouldCancelLoginMethodOnRouteDisposal(
            activityRecreationInProgress = false,
            disposedMethod = LoginMethod.AuthenticationKey,
            activeMethod = LoginMethod.CustomServer,
            activeAttemptGeneration = 43,
            accountState = WearAccountState.Authenticating,
            preserveAuthenticatedContinuation = true,
        ))
    assertFalse(
        shouldCancelLoginMethodOnRouteDisposal(
            activityRecreationInProgress = true,
            disposedMethod = LoginMethod.AuthenticationKey,
            activeMethod = LoginMethod.AuthenticationKey,
            activeAttemptGeneration = 42,
            accountState = WearAccountState.Authenticating,
            preserveAuthenticatedContinuation = false,
        ))
  }

  @Test
  fun recreationHandoffIsConfigurationOnlyProcessLocalAndOneShot() {
    val handoff = ConfigurationRecreationHandoff<Any>()
    val oldActivity = Any()
    val unrelatedActivity = Any()
    val retainedAuthorizedOwner = Any()

    assertFalse(
        handoff.retain(
            source = oldActivity,
            value = retainedAuthorizedOwner,
            isChangingConfigurations = false,
        ))
    assertNull(handoff.consume(hasSavedInstanceState = true))

    assertTrue(
        handoff.retain(
            source = oldActivity,
            value = retainedAuthorizedOwner,
            isChangingConfigurations = true,
        ))
    handoff.clear(unrelatedActivity)
    assertSame(retainedAuthorizedOwner, handoff.consume(hasSavedInstanceState = true))
    assertNull(handoff.consume(hasSavedInstanceState = true))

    assertTrue(
        handoff.retain(
            source = oldActivity,
            value = retainedAuthorizedOwner,
            isChangingConfigurations = true,
        ))
    assertNull(handoff.consume(hasSavedInstanceState = false))
    assertNull(handoff.consume(hasSavedInstanceState = true))

    // A new process has a new in-memory coordinator and therefore no inherited authorization.
    assertNull(ConfigurationRecreationHandoff<Any>().consume(hasSavedInstanceState = true))
  }

  @Test
  fun sensitiveRoutePolicySetsOnceAcrossSensitiveRoutesAndAlwaysCleansUp() {
    val secureTransitions = mutableListOf<Boolean>()
    val coordinator = SensitiveLoginWindowCoordinator { secure -> secureTransitions.add(secure) }

    coordinator.moveTo(null)
    coordinator.moveTo("welcome")
    coordinator.moveTo("login_qr")
    coordinator.moveTo("auth_key")
    coordinator.moveTo("dashboard")
    coordinator.moveTo("auth_key")
    coordinator.close()

    assertEquals(listOf(false, true, false, true, false), secureTransitions)
  }

  @Test
  fun logoutOwnerSurvivesTimeoutAndRejectsNewLoginUntilExactTerminalCallback() {
    val gate = LogoutMutationGate()

    assertTrue(gate.begin(70))
    assertTrue(gate.blocksProfileMutation())
    assertFalse(gate.allowsAuthenticationSupersession())
    assertFalse(loginProfileMutationAllowed(WearAction.Logout))
    assertFalse(gate.begin(71))

    val newerEpochWhileLogoutOwnsCleanup =
        authenticationSupersessionPlan(
            authenticationSupersessionAllowed = gate.allowsAuthenticationSupersession(),
            retirementOwner = AuthenticationRetirementOwner(epoch = 80, loginAttemptGeneration = 6),
            activeAuthenticationEpoch = 80,
            loginCancellationInProgress = false,
            retainedCancellationAuthenticationEpoch = null,
            eventEpoch = 81,
        )
    assertFalse(newerEpochWhileLogoutOwnsCleanup.supersedesAnyOwner)
    assertFalse(newerEpochWhileLogoutOwnsCleanup.supersedesRetirement)
    assertFalse(newerEpochWhileLogoutOwnsCleanup.supersedesActiveOwner)

    // UI timeout is deliberately not a terminal transition.
    assertTrue(gate.owns(70))
    assertTrue(gate.blocksProfileMutation())
    assertFalse(gate.finish(71))
    assertTrue(gate.owns(70))
    assertFalse(gate.allowsAuthenticationSupersession())
    assertTrue(gate.blocksProfileMutation())

    assertTrue(gate.finish(70))
    assertFalse(gate.blocksProfileMutation())
    assertTrue(gate.allowsAuthenticationSupersession())
    assertTrue(
        authenticationSupersessionPlan(
                authenticationSupersessionAllowed = gate.allowsAuthenticationSupersession(),
                retirementOwner =
                    AuthenticationRetirementOwner(epoch = 80, loginAttemptGeneration = 6),
                activeAuthenticationEpoch = 80,
                loginCancellationInProgress = false,
                retainedCancellationAuthenticationEpoch = null,
                eventEpoch = 81,
            )
            .supersedesAnyOwner)
    assertTrue(loginProfileMutationAllowed(null))
    assertTrue(gate.begin(71))
  }
}
