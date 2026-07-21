// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBackendStateCoordinatorTest {
  @Test
  fun startupFailureBeforeViewModelInitializationIsPublishedWhenViewModelBecomesReady() {
    val coordinator = NativeBackendStateCoordinator()

    val earlyFailure = coordinator.onStartupError(attemptId = 41L)
    assertNull(earlyFailure.startupFailed)

    val afterInitialization = coordinator.onViewModelReady()
    assertEquals(true, afterInitialization.startupFailed)
    assertEquals(41L, afterInitialization.attemptId)
  }

  @Test
  fun recoveryBeforeBridgeInitializationRestartsNotifierExactlyOnceWhenBridgeIsReady() {
    val coordinator = NativeBackendStateCoordinator()
    coordinator.onStartupError(attemptId = 41L)
    coordinator.onViewModelReady()

    val earlyRecovery = coordinator.onRecovered(attemptId = 42L)
    assertFalse(earlyRecovery.restartNotifier)
    assertEquals(false, earlyRecovery.startupFailed)
    assertEquals(42L, earlyRecovery.attemptId)

    val bridgeReady = coordinator.onBridgeReady()
    assertTrue(bridgeReady.restartNotifier)
    assertNull(bridgeReady.startupFailed)

    val repeatedPublication = coordinator.onViewModelReady()
    assertFalse(repeatedPublication.restartNotifier)
    assertNull(repeatedPublication.startupFailed)
  }

  @Test
  fun delayedOlderFailureCannotOverwriteNewerRecovery() {
    val coordinator = NativeBackendStateCoordinator()
    coordinator.onViewModelReady()
    coordinator.onBridgeReady()

    assertEquals(true, coordinator.onStartupError(attemptId = 100L).startupFailed)
    val recovered = coordinator.onRecovered(attemptId = 101L)
    assertEquals(false, recovered.startupFailed)
    assertTrue(recovered.restartNotifier)

    val staleFailure = coordinator.onStartupError(attemptId = 100L)
    assertNull(staleFailure.startupFailed)
    assertFalse(staleFailure.restartNotifier)
  }

  @Test
  fun duplicateRecoveryDoesNotStartTwoNotificationWatchers() {
    val coordinator = NativeBackendStateCoordinator()
    coordinator.onBridgeReady()

    assertTrue(coordinator.onRecovered(attemptId = 7L).restartNotifier)
    assertFalse(coordinator.onRecovered(attemptId = 7L).restartNotifier)
  }
}
