// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestErrorPublicationCoordinatorTest {
  @Test
  fun nativeRecoveryCannotClearANewerVpnCommandError() {
    val coordinator = LatestErrorPublicationCoordinator()
    val nativeStartupFailure = coordinator.publish()
    val newerVpnCommandFailure = coordinator.publish()

    assertFalse(coordinator.clearIfCurrent(nativeStartupFailure))
    assertTrue(coordinator.clearIfCurrent(newerVpnCommandFailure))
  }

  @Test
  fun publicationCanBeClearedOnlyOnce() {
    val coordinator = LatestErrorPublicationCoordinator()
    val publication = coordinator.publish()

    assertTrue(coordinator.clearIfCurrent(publication))
    assertFalse(coordinator.clearIfCurrent(publication))
  }
}
