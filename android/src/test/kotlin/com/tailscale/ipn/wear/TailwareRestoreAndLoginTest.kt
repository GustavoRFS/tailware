// Copyright (c) Tailware contributors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

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
  fun loginUrlRequiresAnActiveAttemptAndMustBeFresh() {
    val oldUrl = "https://login.tailscale.com/a/old"
    val newUrl = "https://login.tailscale.com/a/new"
    val oldFingerprint = loginUrlFingerprint(oldUrl)

    assertFalse(shouldAcceptLoginUrl(false, newUrl, oldFingerprint))
    assertFalse(shouldAcceptLoginUrl(true, oldUrl, oldFingerprint))
    assertTrue(shouldAcceptLoginUrl(true, newUrl, oldFingerprint))
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
