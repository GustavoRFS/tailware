// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.notifier

import com.tailscale.ipn.ui.model.Tailcfg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifierPeerBootstrapTest {
  @Test
  fun selfChangeBootstrapsNetworkMapAfterEmptyInitialStatus() {
    val self =
        Tailcfg.Node(
            ID = 42L,
            StableID = "self-node",
            Name = "watch.example.ts.net.",
            User = 7L,
        )

    val result = Notifier.bootstrapNetworkMapFromSelfChange(current = null, selfChange = self)

    assertNotNull(result)
    assertEquals("self-node", result?.SelfNode?.StableID)
    assertEquals("example.ts.net", result?.Domain)
    assertTrue(result?.Peers.orEmpty().isEmpty())
  }
}
