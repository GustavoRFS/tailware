// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import com.tailscale.ipn.ui.model.Ipn
import org.junit.Assert.assertEquals
import org.junit.Test

class P1VpnTeardownUiStateTest {
  @Test
  fun backendStoppedBeforeNativeAcknowledgementStillRendersDisconnecting() {
    assertEquals(
        WearTunnelStatus.Disconnecting,
        wearTunnelStatus(
            ipnState = Ipn.State.Stopped,
            vpnActive = true,
            busyAction = null,
            desiredVpnEnabled = false,
        ),
    )
  }

  @Test
  fun exactAcknowledgementPublicationAllowsDisconnected() {
    assertEquals(
        WearTunnelStatus.Disconnected,
        wearTunnelStatus(
            ipnState = Ipn.State.Stopped,
            vpnActive = false,
            busyAction = null,
            desiredVpnEnabled = false,
        ),
    )
  }

  @Test
  fun successorConnectNeverFlashesDisconnectedBehindOldBarrier() {
    assertEquals(
        WearTunnelStatus.Connecting,
        wearTunnelStatus(
            ipnState = Ipn.State.Stopped,
            vpnActive = false,
            busyAction = WearAction.Connect,
            desiredVpnEnabled = true,
        ),
    )
  }
}
