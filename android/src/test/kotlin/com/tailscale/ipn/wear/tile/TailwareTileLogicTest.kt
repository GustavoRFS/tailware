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
  fun tileTapOpensPassiveAppEvenWhenSetupIsReady() {
    assertEquals(
        TileToggleDecision.OpenApp,
        tileToggleDecision(desiredVpnEnabled = false, vpnAuthorized = true, ableToStartVpn = true),
    )
  }

  @Test
  fun persistedOnCannotTurnAColdTileIntoAStopCommand() {
    assertEquals(
        TileToggleDecision.OpenApp,
        tileToggleDecision(desiredVpnEnabled = true, vpnAuthorized = false, ableToStartVpn = false),
    )
  }

  @Test
  fun tileTapOpensAppInsteadOfSilentlyIgnoringIncompleteSetup() {
    assertEquals(
        TileToggleDecision.OpenApp,
        tileToggleDecision(desiredVpnEnabled = false, vpnAuthorized = false, ableToStartVpn = true),
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
  fun ipAndMagicDnsAreIndependentStructuredRows() {
    assertEquals(
        TileNetworkRows(ip = "IP 100.64.0.1", magicDns = "DNS ON"),
        buildTileNetworkRows(
            selfIp = "100.64.0.1",
            tailscaleDnsEnabled = true,
            magicDnsName = null,
            showIp = true,
            showMagicDns = true,
        ),
    )
    assertEquals(
        TileNetworkRows(ip = null, magicDns = "DNS OFF"),
        buildTileNetworkRows(
            selfIp = "100.64.0.1",
            tailscaleDnsEnabled = false,
            magicDnsName = null,
            showIp = false,
            showMagicDns = true,
        ),
    )
    assertEquals(
        TileNetworkRows(ip = null, magicDns = null),
        buildTileNetworkRows(
            selfIp = "100.64.0.1",
            tailscaleDnsEnabled = true,
            magicDnsName = null,
            showIp = false,
            showMagicDns = false,
        ),
    )
  }

  @Test
  fun rowVisibilityTogglesDoNotAffectEachOther() {
    assertEquals("IP 100.64.0.1", buildTileIpRow("100.64.0.1", showIp = true))
    assertNull(buildTileIpRow("100.64.0.1", showIp = false))
    assertEquals(
        "DNS watch",
        buildTileMagicDnsRow(
            tailscaleDnsEnabled = true,
            magicDnsName = "watch.example.ts.net",
            showMagicDns = true,
        ),
    )
    assertNull(
        buildTileMagicDnsRow(
            tailscaleDnsEnabled = true,
            magicDnsName = "watch.example.ts.net",
            showMagicDns = false,
        ))
  }

  @Test
  fun technicalValuesAreCompactedBeforeBidiFormatting() {
    val wrap: (String) -> String = { value -> "[$value]" }
    val networkRows =
        buildTileNetworkRows(
            selfIp = "2001:db8:1234:5678:9abc:def0:1234:5678",
            tailscaleDnsEnabled = true,
            magicDnsName = "wearable-with-a-long-name.example.ts.net",
            showIp = true,
            showMagicDns = true,
            valueFormatter = wrap,
        )
    val exitRows =
        buildTileExitRows(
            activeExitNodeName = "long-desktop-exit-node",
            allowLanAccess = true,
            showExitNode = true,
            offLabel = "Off",
            lanLabel = "LAN",
            onLabel = "On",
            offStateLabel = "Off",
            valueFormatter = wrap,
        )

    val ip = checkNotNull(networkRows.ip)
    val dns = checkNotNull(networkRows.magicDns)
    val exitNode = checkNotNull(exitRows.exitNode)
    assertTrue(ip.startsWith("IP ["))
    assertTrue(ip.endsWith("]"))
    assertTrue(dns.startsWith("DNS ["))
    assertTrue(dns.endsWith("]"))
    assertFalse(dns.contains("example"))
    assertTrue(exitNode.startsWith("Exit: ["))
    assertTrue(exitNode.endsWith("]"))
  }

  @Test
  fun setupOrErrorTextTakesPriorityOverStructuredRows() {
    val networkRows = TileNetworkRows(ip = "IP 100.64.0.1", magicDns = "DNS watch")
    assertEquals(
        listOf("VPN setup required"),
        prioritizeTileDetailRows(
            priorityText = "VPN setup required",
            networkRows = networkRows,
            exitRows = TileExitRows(exitNode = "Exit: desktop", lan = "LAN On"),
            readyText = "Ready",
        ),
    )
    assertEquals(
        listOf("IP 100.64.0.1", "DNS watch", "Exit: desktop", "LAN On"),
        prioritizeTileDetailRows(
            priorityText = null,
            networkRows = networkRows,
            exitRows = TileExitRows(exitNode = "Exit: desktop", lan = "LAN On"),
            readyText = "Ready",
            maximumNormalRows = 4,
        ),
    )
  }

  @Test
  fun compactRowsFallBackToReadyAndRespectTheScreenBudget() {
    assertEquals(
        listOf("Ready"),
        prioritizeTileDetailRows(
            priorityText = null,
            networkRows = TileNetworkRows(ip = null, magicDns = null),
            exitRows = TileExitRows(exitNode = null, lan = null),
            readyText = "Ready",
        ),
    )
    assertEquals(
        listOf("Exit: desktop", "LAN On"),
        prioritizeTileDetailRows(
            priorityText = null,
            networkRows = TileNetworkRows(ip = "IP 100.64.0.1", magicDns = "DNS watch"),
            exitRows = TileExitRows(exitNode = "Exit: desktop", lan = "LAN On"),
            readyText = "Ready",
            maximumNormalRows = 2,
        ),
    )
  }

  @Test
  fun activeExitAndLanAreReservedTogetherUnderAThreeRowBudget() {
    assertEquals(
        listOf("IP 100.64.0.1", "Exit: very-long-exit", "LAN On"),
        prioritizeTileDetailRows(
            priorityText = null,
            networkRows = TileNetworkRows(ip = "IP 100.64.0.1", magicDns = "DNS watch"),
            exitRows = TileExitRows(exitNode = "Exit: very-long-exit", lan = "LAN On"),
            readyText = "Ready",
            maximumNormalRows = 3,
        ),
    )
    assertEquals(
        listOf("DNS failed"),
        prioritizeTileDetailRows(
            priorityText = "DNS failed",
            networkRows = TileNetworkRows(ip = "IP 100.64.0.1", magicDns = "DNS watch"),
            exitRows = TileExitRows(exitNode = "Exit: very-long-exit", lan = "LAN On"),
            readyText = "Ready",
            maximumNormalRows = 3,
        ),
    )
  }

  @Test
  fun tileLayoutShrinksTheRingOn192And200DpScreensWithoutDroppingDetailRows() {
    listOf(192, 200).forEach { size ->
      val profile = tileLayoutProfile(size, size)
      assertEquals(50f, profile.ringSizeDp)
      assertTrue(profile.buttonSizeDp >= 48f)
      assertEquals(4, profile.maximumNormalRows)
    }

    val regular = tileLayoutProfile(220, 220)
    assertEquals(52f, regular.ringSizeDp)
    assertTrue(regular.buttonSizeDp >= 48f)
    assertEquals(4, regular.maximumNormalRows)
  }

  @Test
  fun tileLayoutUsesTheSmallestDimensionForRectangularWearDisplays() {
    assertEquals(50f, tileLayoutProfile(screenWidthDp = 240, screenHeightDp = 200).ringSizeDp)
    assertEquals(52f, tileLayoutProfile(screenWidthDp = 240, screenHeightDp = 220).ringSizeDp)
  }

  @Test
  fun tileContentDescriptionIncludesCurrentStatusAndConcreteNextAction() {
    assertEquals(
        "Disconnected — Connect",
        buildTileContentDescription(
            statusLabel = "Disconnected",
            displayedAction = TileToggleDecision.Start,
            connectLabel = "Connect",
            disconnectLabel = "Disconnect",
            openAppLabel = "Open Tailware",
        ),
    )
    assertEquals(
        "Connected — Disconnect",
        buildTileContentDescription(
            statusLabel = "Connected",
            displayedAction = TileToggleDecision.Stop,
            connectLabel = "Connect",
            disconnectLabel = "Disconnect",
            openAppLabel = "Open Tailware",
        ),
    )
    assertEquals(
        "Open Tailware",
        buildTileContentDescription(
            statusLabel = "Error",
            displayedAction = TileToggleDecision.OpenApp,
            connectLabel = "Connect",
            disconnectLabel = "Disconnect",
            openAppLabel = "Open Tailware",
        ),
    )
  }

  @Test
  fun exitAndLanUseIndependentRowsWhenEnabled() {
    assertEquals(
        TileExitRows(exitNode = "Exit: Off", lan = null),
        buildTileExitRows(
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
        TileExitRows(exitNode = "Exit: desktop", lan = "LAN On"),
        buildTileExitRows(
            activeExitNodeName = "desktop",
            allowLanAccess = true,
            showExitNode = true,
            offLabel = "Off",
            lanLabel = "LAN",
            onLabel = "On",
            offStateLabel = "Off",
        ),
    )
    assertEquals(
        TileExitRows(exitNode = null, lan = null),
        buildTileExitRows(
            activeExitNodeName = "desktop",
            allowLanAccess = false,
            showExitNode = false,
            offLabel = "Off",
            lanLabel = "LAN",
            onLabel = "On",
            offStateLabel = "Off",
        ),
    )
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
  fun displayedConnectingStateStillOpensThePassiveApp() {
    assertEquals(
        TileToggleDecision.OpenApp,
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
        buildTileMagicDnsRow(
            tailscaleDnsEnabled = true,
            magicDnsName = "watch.example.ts.net",
            showMagicDns = true,
        ),
    )
  }

  @Test
  fun compactDetailsUseLocalizedStateLabels() {
    assertEquals(
        "DNS AAN",
        buildTileMagicDnsRow(
            tailscaleDnsEnabled = true,
            magicDnsName = null,
            showMagicDns = true,
            onLabel = "AAN",
            offLabel = "UIT",
        ),
    )
    assertEquals(
        TileExitRows(exitNode = "Sortie: bureau-princi…", lan = "LAN Activé"),
        buildTileExitRows(
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
  fun humanOffStatusIsDisconnectedWhilePowerActionOpensTheApp() {
    assertEquals(TileHumanStatus.Disconnected, resolveTileHumanStatus(TileVisualStatus.Off))
    assertEquals(
        TileToggleDecision.OpenApp,
        displayedTileAction(
            alwaysOpenApp = false,
            visualStatus = TileVisualStatus.Off,
            tunnelStatus = TileTunnelStatus.Disconnected,
        ),
    )
    TileVisualStatus.values().forEach { status ->
      assertEquals(TileButtonContent.PowerIcon, resolveTileButtonContent(status))
    }
  }

  @Test
  fun coldTileFallsBackToOpeningAppAndCacheResetPreservesDisplayedAction() {
    assertEquals(TileToggleDecision.OpenApp, displayedActionFromStored(null))
    assertEquals(TileToggleDecision.OpenApp, displayedActionFromStored("Start"))
    assertEquals(TileToggleDecision.OpenApp, displayedActionFromStored("unexpected"))
    assertTrue(shouldClearTileCacheKey("tile_cached_self_ip"))
    assertFalse(shouldClearTileCacheKey("tile_displayed_action_7"))
  }
}
