// Copyright (c) Tailware contributors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import com.tailscale.ipn.TerminalVpnFailureCleanup
import com.tailscale.ipn.VpnStartWatchdogDecision
import com.tailscale.ipn.shouldPublishOptionalDisconnectedNotification
import com.tailscale.ipn.shouldPublishStopping
import com.tailscale.ipn.terminalVpnFailureCleanup
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.vpnStartWatchdogDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Private5VpnServiceLifecycleTest {
  private val owner = DesiredVpnState(enabled = true, generation = 12L)

  @Test
  fun stoppingIsPublishedOnlyForAStartedTunnel() {
    assertTrue(shouldPublishStopping(Ipn.State.Starting))
    assertTrue(shouldPublishStopping(Ipn.State.Running))
    assertFalse(shouldPublishStopping(Ipn.State.NoState))
    assertFalse(shouldPublishStopping(Ipn.State.NeedsLogin))
    assertFalse(shouldPublishStopping(Ipn.State.Stopped))
    assertFalse(shouldPublishStopping(Ipn.State.Stopping))
  }

  @Test
  fun startWatchdogStopsNeedsLoginAndMissingTunnelStates() {
    assertEquals(
        VpnStartWatchdogDecision.StopNeedsLogin,
        vpnStartWatchdogDecision(
            commandOwner = owner,
            currentState = owner,
            ipnState = Ipn.State.NeedsLogin,
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
            tunnelActive = false,
            closed = false,
        ),
    )
  }

  @Test
  fun startWatchdogNeverStopsACompletedClosedOrSupersededCommand() {
    assertEquals(
        VpnStartWatchdogDecision.None,
        vpnStartWatchdogDecision(
            commandOwner = owner,
            currentState = owner,
            ipnState = Ipn.State.Running,
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
}
