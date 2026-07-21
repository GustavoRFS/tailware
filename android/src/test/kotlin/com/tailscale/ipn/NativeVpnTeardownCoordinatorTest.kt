// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeVpnTeardownCoordinatorTest {
  @Test
  fun staleAndMismatchedAcknowledgementsCannotCompleteOrRemoveProtection() {
    val coordinator = NativeVpnTeardownCoordinator()
    coordinator.onStarted(startGeneration = 10L)
    coordinator.beginTeardown(requestedTeardownGeneration = 11L)

    val stale = coordinator.onNativeAcknowledged(acknowledgedGeneration = 10L)
    val future = coordinator.onNativeAcknowledged(acknowledgedGeneration = 12L)

    assertIgnoredWithoutTerminalEffects(stale)
    assertIgnoredWithoutTerminalEffects(future)
    assertEquals(
        NativeVpnTeardownSnapshot(
            latestGeneration = 11L,
            activeGeneration = 10L,
            teardownGeneration = 11L,
            successorGeneration = null,
            phase = NativeVpnTeardownPhase.AwaitingNativeAcknowledgement,
            teardownProtectionRequired = true,
        ),
        coordinator.snapshot(),
    )
  }

  @Test
  fun exactGenerationAcknowledgementCompletesAndAuthorizesReleaseOnce() {
    val coordinator = NativeVpnTeardownCoordinator()
    val waiting = coordinator.beginTeardown(requestedTeardownGeneration = 21L)
    assertTrue(waiting.scheduleTimeouts)

    val acknowledged = coordinator.onNativeAcknowledged(acknowledgedGeneration = 21L)

    assertEquals(NativeVpnTeardownOutcome.Complete, acknowledged.outcome)
    assertTrue(acknowledged.cancelTimeouts)
    assertTrue(acknowledged.removeForegroundProtection)
    assertTrue(acknowledged.publishDisconnected)
    assertFalse(acknowledged.continueWithSuccessor)
    assertFalse(coordinator.snapshot().teardownProtectionRequired)
    assertEquals(NativeVpnTeardownPhase.Acknowledged, coordinator.snapshot().phase)

    assertIgnoredWithoutTerminalEffects(
        coordinator.onNativeAcknowledged(acknowledgedGeneration = 21L))
  }

  @Test
  fun firstTimeoutRequestsForceCloseExactlyOnce() {
    val coordinator = NativeVpnTeardownCoordinator()
    coordinator.beginTeardown(requestedTeardownGeneration = 31L)

    val firstTimeout = coordinator.onForceCloseTimeout(timedOutGeneration = 31L)
    val duplicateTimeout = coordinator.onForceCloseTimeout(timedOutGeneration = 31L)

    assertEquals(NativeVpnTeardownOutcome.RequestForceClose, firstTimeout.outcome)
    assertTrue(firstTimeout.requestForceClose)
    assertFalse(firstTimeout.removeForegroundProtection)
    assertFalse(firstTimeout.publishDisconnected)
    assertIgnoredWithoutTerminalEffects(duplicateTimeout)
    assertEquals(NativeVpnTeardownPhase.ForceCloseRequested, coordinator.snapshot().phase)
    assertTrue(coordinator.snapshot().teardownProtectionRequired)
  }

  @Test
  fun hardTimeoutRetainsProtectionUntilLateExactAcknowledgementReleasesSuccessorBarrier() {
    val coordinator = NativeVpnTeardownCoordinator()
    coordinator.beginTeardown(requestedTeardownGeneration = 41L)
    coordinator.onStarted(startGeneration = 42L)
    coordinator.onForceCloseTimeout(timedOutGeneration = 41L)

    val hardTimeout = coordinator.onHardTimeout(timedOutGeneration = 41L)

    assertEquals(NativeVpnTeardownOutcome.HardTimeoutFailedClosed, hardTimeout.outcome)
    assertTrue(hardTimeout.cancelTimeouts)
    assertTrue(hardTimeout.hardTimeoutFailedClosed)
    assertFalse(hardTimeout.removeForegroundProtection)
    assertFalse(hardTimeout.publishDisconnected)
    assertEquals(NativeVpnTeardownPhase.FailedClosed, coordinator.snapshot().phase)
    assertEquals(41L, coordinator.snapshot().teardownGeneration)
    assertEquals(42L, coordinator.snapshot().successorGeneration)
    assertTrue(coordinator.snapshot().teardownProtectionRequired)

    assertIgnoredWithoutTerminalEffects(
        coordinator.onNativeAcknowledged(acknowledgedGeneration = 40L))
    assertIgnoredWithoutTerminalEffects(
        coordinator.onNativeAcknowledged(acknowledgedGeneration = 42L))
    assertIgnoredWithoutTerminalEffects(coordinator.onHardTimeout(timedOutGeneration = 41L))

    val lateExactAcknowledgement = coordinator.onNativeAcknowledged(acknowledgedGeneration = 41L)
    assertEquals(
        NativeVpnTeardownOutcome.BarrierReleasedForSuccessor,
        lateExactAcknowledgement.outcome,
    )
    assertTrue(lateExactAcknowledgement.continueWithSuccessor)
    assertFalse(lateExactAcknowledgement.removeForegroundProtection)
    assertFalse(lateExactAcknowledgement.publishDisconnected)
    assertEquals(42L, coordinator.snapshot().activeGeneration)
    assertEquals(NativeVpnTeardownPhase.Active, coordinator.snapshot().phase)
    assertFalse(coordinator.snapshot().teardownProtectionRequired)
  }

  @Test
  fun lateExactAcknowledgementAfterHardTimeoutCanCompleteTerminalStop() {
    val coordinator = NativeVpnTeardownCoordinator()
    coordinator.beginTeardown(requestedTeardownGeneration = 45L)

    val hardTimeout = coordinator.onHardTimeout(timedOutGeneration = 45L)
    assertTrue(hardTimeout.hardTimeoutFailedClosed)
    assertFalse(hardTimeout.removeForegroundProtection)
    assertFalse(hardTimeout.publishDisconnected)
    assertTrue(coordinator.snapshot().teardownProtectionRequired)
    assertIgnoredWithoutTerminalEffects(
        coordinator.onNativeAcknowledged(acknowledgedGeneration = 44L))

    val lateExactAcknowledgement = coordinator.onNativeAcknowledged(acknowledgedGeneration = 45L)
    assertEquals(NativeVpnTeardownOutcome.Complete, lateExactAcknowledgement.outcome)
    assertTrue(lateExactAcknowledgement.removeForegroundProtection)
    assertTrue(lateExactAcknowledgement.publishDisconnected)
    assertEquals(NativeVpnTeardownPhase.Acknowledged, coordinator.snapshot().phase)
    assertFalse(coordinator.snapshot().teardownProtectionRequired)
  }

  @Test
  fun newerGenerationWaitsForOldExactAcknowledgementWithoutDroppingProtection() {
    val coordinator = NativeVpnTeardownCoordinator()
    coordinator.onStarted(startGeneration = 50L)
    coordinator.beginTeardown(requestedTeardownGeneration = 51L)

    val queuedStart = coordinator.onStarted(startGeneration = 52L)

    assertEquals(NativeVpnTeardownOutcome.SuccessorQueued, queuedStart.outcome)
    assertEquals(51L, queuedStart.teardownGeneration)
    assertEquals(52L, queuedStart.successorGeneration)
    assertFalse(queuedStart.cancelTimeouts)
    assertFalse(queuedStart.removeForegroundProtection)
    assertFalse(queuedStart.publishDisconnected)
    assertEquals(NativeVpnTeardownPhase.AwaitingNativeAcknowledgement, coordinator.snapshot().phase)
    assertTrue(coordinator.snapshot().teardownProtectionRequired)
    assertIgnoredWithoutTerminalEffects(
        coordinator.onNativeAcknowledged(acknowledgedGeneration = 52L))

    val barrierReleased = coordinator.onNativeAcknowledged(acknowledgedGeneration = 51L)
    assertEquals(
        NativeVpnTeardownOutcome.BarrierReleasedForSuccessor,
        barrierReleased.outcome,
    )
    assertTrue(barrierReleased.cancelTimeouts)
    assertTrue(barrierReleased.continueWithSuccessor)
    assertEquals(52L, barrierReleased.successorGeneration)
    assertFalse(barrierReleased.removeForegroundProtection)
    assertFalse(barrierReleased.publishDisconnected)
    assertEquals(
        NativeVpnTeardownSnapshot(
            latestGeneration = 52L,
            activeGeneration = 52L,
            teardownGeneration = null,
            successorGeneration = null,
            phase = NativeVpnTeardownPhase.Active,
            teardownProtectionRequired = false,
        ),
        coordinator.snapshot(),
    )
  }

  @Test
  fun rapidStartStopStartOrderingCompletesOnlyTheLaterTerminalStop() {
    val coordinator = NativeVpnTeardownCoordinator()
    assertEquals(NativeVpnTeardownOutcome.StartAccepted, coordinator.onStarted(60L).outcome)
    assertTrue(coordinator.beginTeardown(61L).scheduleTimeouts)
    assertTrue(coordinator.onForceCloseTimeout(61L).requestForceClose)

    val secondStart = coordinator.onStarted(62L)
    assertEquals(NativeVpnTeardownOutcome.SuccessorQueued, secondStart.outcome)
    assertFalse(secondStart.cancelTimeouts)
    assertTrue(coordinator.snapshot().teardownProtectionRequired)
    assertEquals(NativeVpnTeardownPhase.ForceCloseRequested, coordinator.snapshot().phase)
    assertIgnoredWithoutTerminalEffects(coordinator.onNativeAcknowledged(62L))

    val barrierReleased = coordinator.onNativeAcknowledged(61L)
    assertEquals(
        NativeVpnTeardownOutcome.BarrierReleasedForSuccessor,
        barrierReleased.outcome,
    )
    assertFalse(barrierReleased.removeForegroundProtection)
    assertFalse(barrierReleased.publishDisconnected)
    assertEquals(62L, coordinator.snapshot().activeGeneration)

    val latestStop = coordinator.beginTeardown(63L)
    assertTrue(latestStop.scheduleTimeouts)
    assertNull(latestStop.successorGeneration)
    val latestAcknowledgement = coordinator.onNativeAcknowledged(63L)
    assertEquals(NativeVpnTeardownOutcome.Complete, latestAcknowledgement.outcome)
    assertTrue(latestAcknowledgement.removeForegroundProtection)
    assertTrue(latestAcknowledgement.publishDisconnected)
  }

  private fun assertIgnoredWithoutTerminalEffects(decision: NativeVpnTeardownDecision) {
    assertEquals(NativeVpnTeardownOutcome.Ignored, decision.outcome)
    assertFalse(decision.scheduleTimeouts)
    assertFalse(decision.cancelTimeouts)
    assertFalse(decision.requestForceClose)
    assertFalse(decision.continueWithSuccessor)
    assertFalse(decision.hardTimeoutFailedClosed)
    assertFalse(decision.removeForegroundProtection)
    assertFalse(decision.publishDisconnected)
  }
}
