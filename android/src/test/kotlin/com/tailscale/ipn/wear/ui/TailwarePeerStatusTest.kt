// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear.ui

import com.tailscale.ipn.wear.WearPeer
import com.tailscale.ipn.wear.WearTunnelStatus
import com.tailscale.ipn.wear.WearUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailwarePeerStatusTest {
  private val remotePeer =
      WearPeer(
          stableId = "peer",
          displayName = "Peer",
          dnsName = "peer.tailnet.ts.net",
          ipv4 = "100.64.0.2",
          ipv6 = null,
          addresses = listOf("100.64.0.2"),
          online = true,
          isSelf = false,
          os = "linux",
          lastSeen = null,
          keyExpiry = null,
      )

  @Test
  fun cachedOnlinePeerIsNotShownOnlineWhileTunnelIsDisconnected() {
    assertFalse(
        peerIsEffectivelyOnline(
            WearUiState(tunnelStatus = WearTunnelStatus.Disconnected),
            remotePeer,
        ))
  }

  @Test
  fun onlinePeerIsShownOnlineOnlyWhenTunnelIsConnected() {
    assertTrue(
        peerIsEffectivelyOnline(
            WearUiState(tunnelStatus = WearTunnelStatus.Connected),
            remotePeer,
        ))
  }
}
