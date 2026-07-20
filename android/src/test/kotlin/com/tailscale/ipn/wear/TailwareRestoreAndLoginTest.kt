// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import com.tailscale.ipn.UnderlyingNetworkStatus
import com.tailscale.ipn.ui.model.Ipn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailwareRestoreAndLoginTest {
  @Test
  fun rebootRestoresOnlyAnEnabledAuthorizedReadyVpn() {
    assertTrue(
        shouldRestoreVpnAfterBoot(
            desiredVpnEnabled = true,
            vpnAuthorized = true,
            ableToStartVpn = true,
        ))
    assertFalse(
        shouldRestoreVpnAfterBoot(
            desiredVpnEnabled = false,
            vpnAuthorized = true,
            ableToStartVpn = true,
        ))
    assertFalse(
        shouldRestoreVpnAfterBoot(
            desiredVpnEnabled = true,
            vpnAuthorized = false,
            ableToStartVpn = true,
        ))
    assertFalse(
        shouldRestoreVpnAfterBoot(
            desiredVpnEnabled = true,
            vpnAuthorized = true,
            ableToStartVpn = false,
        ))
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
  fun everyNonOnlineNetworkStateUsesLocalProfileRemoval() {
    assertTrue(shouldUseLocalOnlyLogout(UnderlyingNetworkStatus.Offline))
    assertTrue(shouldUseLocalOnlyLogout(UnderlyingNetworkStatus.Limited))
    assertTrue(shouldUseLocalOnlyLogout(UnderlyingNetworkStatus.Unknown))
    assertFalse(shouldUseLocalOnlyLogout(UnderlyingNetworkStatus.Online))
  }

  @Test
  fun pingFailureIncludesTargetAndUsefulBackendDiagnostics() {
    assertEquals(
        "Ping to desktop (100.64.0.2) timed out. The device may be asleep, offline, or unreachable.",
        pingFailureMessage("desktop", "100.64.0.2", "context deadline exceeded"),
    )
    assertEquals(
        "Ping to desktop (100.64.0.2) failed: no matching peer",
        pingFailureMessage("desktop", "100.64.0.2", "no matching peer."),
    )
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
  fun technicalRestartNeverTurnsAnExplicitlyDisabledVpnOn() {
    assertFalse(shouldDispatchVpnRestart(desiredVpnEnabled = false))
    assertTrue(shouldDispatchVpnRestart(desiredVpnEnabled = true))
  }
}
