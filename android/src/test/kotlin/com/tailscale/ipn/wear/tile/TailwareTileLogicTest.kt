// Copyright (c) Tailware contributors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear.tile

import com.tailscale.ipn.ui.model.Ipn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TailwareTileLogicTest {
  @Test
  fun tileTapStartsFromPersistedOffStateWhenSetupIsReady() {
    assertEquals(
        TileToggleDecision.Start,
        tileToggleDecision(
            desiredVpnEnabled = false,
            vpnAuthorized = true,
            ableToStartVpn = true,
        ),
    )
  }

  @Test
  fun tileTapStopsFromPersistedOnStateEvenWhenSetupIsNoLongerReady() {
    assertEquals(
        TileToggleDecision.Stop,
        tileToggleDecision(
            desiredVpnEnabled = true,
            vpnAuthorized = false,
            ableToStartVpn = false,
        ),
    )
  }

  @Test
  fun tileTapOpensAppInsteadOfSilentlyIgnoringIncompleteSetup() {
    assertEquals(
        TileToggleDecision.OpenApp,
        tileToggleDecision(
            desiredVpnEnabled = false,
            vpnAuthorized = false,
            ableToStartVpn = true,
        ),
    )
  }

  @Test
  fun desiredOffOptimisticallyOverridesLaggingActiveTunnel() {
    assertEquals(
        TileTunnelStatus.Disconnected,
        resolveTileTunnelStatus(
            ipnState = Ipn.State.Running,
            vpnActive = true,
            desiredVpnEnabled = false,
            vpnAuthorized = true,
            ableToStartVpn = true,
        ),
    )
  }

  @Test
  fun desiredOnShowsConnectingUntilTunIsActive() {
    assertEquals(
        TileTunnelStatus.Connecting,
        resolveTileTunnelStatus(
            ipnState = Ipn.State.Stopping,
            vpnActive = false,
            desiredVpnEnabled = true,
            vpnAuthorized = true,
            ableToStartVpn = true,
        ),
    )
  }

  @Test
  fun compactNetworkLineHonorsVisibilityPreferences() {
    assertEquals(
        "IP 100.64.0.1 • DNS ON",
        buildTileNetworkDetail(
            selfIp = "100.64.0.1",
            tailscaleDnsEnabled = true,
            showIp = true,
            showMagicDns = true,
        ),
    )
    assertEquals(
        "DNS OFF",
        buildTileNetworkDetail(
            selfIp = "100.64.0.1",
            tailscaleDnsEnabled = false,
            showIp = false,
            showMagicDns = true,
        ),
    )
    assertNull(
        buildTileNetworkDetail(
            selfIp = "100.64.0.1",
            tailscaleDnsEnabled = true,
            showIp = false,
            showMagicDns = false,
        ))
  }

  @Test
  fun exitLineIsAlwaysPresentWhenEnabled() {
    assertEquals(
        "Exit: Off",
        buildTileExitDetail(
            activeExitNodeName = null,
            showExitNode = true,
            offLabel = "Off",
        ),
    )
    assertEquals(
        "Exit: desktop",
        buildTileExitDetail(
            activeExitNodeName = "desktop",
            showExitNode = true,
            offLabel = "Off",
        ),
    )
    assertNull(
        buildTileExitDetail(
            activeExitNodeName = "desktop",
            showExitNode = false,
            offLabel = "Off",
        ))
  }
}
