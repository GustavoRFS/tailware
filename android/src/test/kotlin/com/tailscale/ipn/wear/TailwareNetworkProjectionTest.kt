// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.ui.localapi.DISCO_PING_LOCAL_API_TIMEOUT_MILLIS
import com.tailscale.ipn.ui.localapi.localApiFailureMessage
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailwareNetworkProjectionTest {
  @Test
  fun discoPingDeadlineLeavesRoomForTheBackendDiagnosticCycle() {
    assertEquals(10_000L, DISCO_PING_LOCAL_API_TIMEOUT_MILLIS)
    assertTrue(DISCO_PING_LOCAL_API_TIMEOUT_MILLIS > 5_000L)
  }

  @Test
  fun localApiFailurePreservesTheBackendDiagnosticWithoutJsonNoise() {
    assertEquals(
        "local Tailscale is stopped",
        localApiFailureMessage(
            statusCode = 500,
            responseBody = """{"error":"local Tailscale is stopped"}""".encodeToByteArray(),
        ),
    )
    assertEquals(
        "Request failed with status 503: unavailable",
        localApiFailureMessage(statusCode = 503, responseBody = "unavailable".encodeToByteArray()),
    )
  }

  @Test
  fun activeExitNodeUsesPeerNameAndFallsBackToStableIdWhenPeerMetadataIsMissing() {
    val prefs = Ipn.Prefs(ExitNodeID = "exit-node-id")
    val exitNode =
        Tailcfg.Node(
            StableID = "exit-node-id",
            Name = "desktop.example.ts.net.",
            ComputedName = "Desktop",
            AllowedIPs = listOf("0.0.0.0/0", "::/0"),
        )

    assertEquals(
        "Desktop",
        UninitializedApp.getExitNodeName(prefs, networkMap(peers = listOf(exitNode))),
    )
    assertEquals(
        "exit-node-id",
        UninitializedApp.getExitNodeName(prefs, networkMap(peers = emptyList())),
    )
    assertNull(UninitializedApp.getExitNodeName(Ipn.Prefs(), networkMap(peers = listOf(exitNode))))
  }

  private fun networkMap(peers: List<Tailcfg.Node>) =
      Netmap.NetworkMap(
          SelfNode = Tailcfg.Node(StableID = "watch", Name = "watch.example.ts.net."),
          Peers = peers,
          Domain = "example.ts.net",
          UserProfiles = emptyMap(),
          TKAEnabled = false,
      )
}
