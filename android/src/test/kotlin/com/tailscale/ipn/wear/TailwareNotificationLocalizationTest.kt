// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import com.tailscale.ipn.ExitNodeFailureReason
import com.tailscale.ipn.StartVPNWorker
import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.tailwareNotificationRefreshPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailwareNotificationLocalizationTest {
  @Test
  fun languageRefreshPlansEveryStructuredAuxiliaryNotification() {
    val plan =
        tailwareNotificationRefreshPlan(
            setOf(
                UninitializedApp.STATUS_NOTIFICATION_ID,
                StartVPNWorker.NOTIFICATION_ID,
                StartVPNWorker.STOP_FAILURE_NOTIFICATION_ID,
                UninitializedApp.STATUS_EXIT_NODE_FAILURE_NOTIFICATION_ID,
            ))

    assertTrue(plan.vpnStatus)
    assertTrue(plan.vpnSetupRequired)
    assertTrue(plan.vpnStopFailure)
    assertTrue(plan.exitNodeFailure)
  }

  @Test
  fun languageRefreshDoesNotInventInactiveAuxiliaryNotifications() {
    val plan = tailwareNotificationRefreshPlan(emptySet())

    assertFalse(plan.vpnStatus)
    assertFalse(plan.vpnSetupRequired)
    assertFalse(plan.vpnStopFailure)
    assertFalse(plan.exitNodeFailure)
  }

  @Test
  fun exitNodeFailureReasonPersistsAsAStructuredStableValue() {
    for (reason in ExitNodeFailureReason.entries) {
      assertEquals(reason, ExitNodeFailureReason.fromStored(reason.name))
    }
    assertEquals(null, ExitNodeFailureReason.fromStored("unknown-future-reason"))
  }
}
