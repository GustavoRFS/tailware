// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear.tile

import com.tailscale.ipn.ui.model.Ipn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            magicDnsName = null,
            showIp = true,
            showMagicDns = true,
        ),
    )
    assertEquals(
        "DNS OFF",
        buildTileNetworkDetail(
            selfIp = "100.64.0.1",
            tailscaleDnsEnabled = false,
            magicDnsName = null,
            showIp = false,
            showMagicDns = true,
        ),
    )
    assertNull(
        buildTileNetworkDetail(
            selfIp = "100.64.0.1",
            tailscaleDnsEnabled = true,
            magicDnsName = null,
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
            allowLanAccess = false,
            showExitNode = true,
            offLabel = "Off",
            lanLabel = "LAN",
            onLabel = "On",
            offStateLabel = "Off",
        ),
    )
    assertEquals(
        "Exit: desktop • LAN On",
        buildTileExitDetail(
            activeExitNodeName = "desktop",
            allowLanAccess = true,
            showExitNode = true,
            offLabel = "Off",
            lanLabel = "LAN",
            onLabel = "On",
            offStateLabel = "Off",
        ),
    )
    assertNull(
        buildTileExitDetail(
            activeExitNodeName = "desktop",
            allowLanAccess = false,
            showExitNode = false,
            offLabel = "Off",
            lanLabel = "LAN",
            onLabel = "On",
            offStateLabel = "Off",
        ))
  }

  @Test
  fun warningAndErrorStatesOpenTheAppForDetails() {
    assertEquals(
        TileVisualStatus.Warning,
        resolveTileVisualStatus(
            tunnelStatus = TileTunnelStatus.Connected,
            underlyingOnline = false,
            setupRequired = false,
            problemMessage = null,
            problemIsError = false,
        ),
    )
    assertEquals(
        TileToggleDecision.OpenApp,
        displayedTileAction(
            alwaysOpenApp = false,
            visualStatus = TileVisualStatus.Warning,
            tunnelStatus = TileTunnelStatus.Connected,
        ),
    )
    assertEquals(
        TileVisualStatus.Error,
        resolveTileVisualStatus(
            tunnelStatus = TileTunnelStatus.Connected,
            underlyingOnline = true,
            setupRequired = false,
            problemMessage = "DNS failed",
            problemIsError = true,
        ),
    )
    assertEquals(
        TileToggleDecision.OpenApp,
        displayedTileAction(
            alwaysOpenApp = false,
            visualStatus = TileVisualStatus.Error,
            tunnelStatus = TileTunnelStatus.Connected,
        ),
    )
  }

  @Test
  fun displayedConnectingStateAlwaysMapsToStop() {
    assertEquals(
        TileToggleDecision.Stop,
        displayedTileAction(
            alwaysOpenApp = false,
            visualStatus = TileVisualStatus.Connecting,
            tunnelStatus = TileTunnelStatus.Connecting,
        ),
    )
  }

  @Test
  fun magicDnsNameIsShownWhenAvailable() {
    assertEquals(
        "DNS watch",
        buildTileNetworkDetail(
            selfIp = null,
            tailscaleDnsEnabled = true,
            magicDnsName = "watch.example.ts.net",
            showIp = false,
            showMagicDns = true,
        ),
    )
  }

  @Test
  fun compactDetailsUseLocalizedStateLabels() {
    assertEquals(
        "DNS AAN",
        buildTileNetworkDetail(
            selfIp = null,
            tailscaleDnsEnabled = true,
            magicDnsName = null,
            showIp = false,
            showMagicDns = true,
            onLabel = "AAN",
            offLabel = "UIT",
        ),
    )
    assertEquals(
        "Sortie: bureau-princi… • LAN Activé",
        buildTileExitDetail(
            activeExitNodeName = "bureau-principal-paris",
            allowLanAccess = true,
            showExitNode = true,
            exitLabel = "Sortie",
            offLabel = "Inactif",
            lanLabel = "LAN",
            onLabel = "Activé",
            offStateLabel = "Inactif",
        ),
    )
  }

  @Test
  fun coldTileFallsBackToOpeningAppAndCacheResetPreservesDisplayedAction() {
    assertEquals(TileToggleDecision.OpenApp, displayedActionFromStored(null))
    assertEquals(TileToggleDecision.Start, displayedActionFromStored("Start"))
    assertEquals(TileToggleDecision.OpenApp, displayedActionFromStored("unexpected"))
    assertTrue(shouldClearTileCacheKey("tile_cached_self_ip"))
    assertFalse(shouldClearTileCacheKey("tile_displayed_action_7"))
  }
}
