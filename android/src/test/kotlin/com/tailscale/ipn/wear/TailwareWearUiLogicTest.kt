// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import com.tailscale.ipn.wear.ui.LogoutUiMode
import com.tailscale.ipn.wear.ui.connectionControlContentDescription
import com.tailscale.ipn.wear.ui.logoutUiMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailwareWearUiLogicTest {
  @Test
  fun failedLatestExitPreferenceUsesLiveThenProfileCacheThenEmpty() {
    val cached =
        ConfirmedExitPreferences(
            selectedExitNodeId = "node-a",
            activeExitNodeId = "node-a",
            allowLanAccess = false,
        )
    val live =
        ConfirmedExitPreferences(
            selectedExitNodeId = "node-b",
            activeExitNodeId = "node-b",
            allowLanAccess = true,
        )

    assertEquals(live, resolveConfirmedExitPreferences(live = live, profileCache = cached))
    assertEquals(cached, resolveConfirmedExitPreferences(live = null, profileCache = cached))
    assertEquals(
        ConfirmedExitPreferences(null, null, false),
        resolveConfirmedExitPreferences(live = null, profileCache = null),
    )
  }

  @Test
  fun notificationPreferenceRemainsRequestedWhenAndroidDeniesOrLaterRevokesPermission() {
    assertEquals(
        OptionalNotificationState(requested = true, effective = false, blockedBySystem = true),
        resolveOptionalNotificationState(requested = true, systemPermissionGranted = false),
    )
    assertEquals(
        OptionalNotificationState(requested = true, effective = true, blockedBySystem = false),
        resolveOptionalNotificationState(requested = true, systemPermissionGranted = true),
    )
    assertEquals(
        OptionalNotificationState(requested = false, effective = false, blockedBySystem = false),
        resolveOptionalNotificationState(requested = false, systemPermissionGranted = true),
    )
    assertEquals(
        OptionalNotificationState(requested = false, effective = false, blockedBySystem = false),
        resolveOptionalNotificationState(requested = false, systemPermissionGranted = false),
    )
  }

  @Test
  fun notificationTogglePersistsOnBeforePermissionDialogAndExplicitOffStaysOff() {
    assertEquals(
        OptionalNotificationToggleDecision(requested = true, launchPermissionDialog = true),
        optionalNotificationToggleDecision(
            enabled = true,
            runtimePermissionRequired = true,
            permissionGranted = false,
        ),
    )
    assertEquals(
        OptionalNotificationToggleDecision(requested = true, launchPermissionDialog = false),
        optionalNotificationToggleDecision(
            enabled = true,
            runtimePermissionRequired = true,
            permissionGranted = true,
        ),
    )
    assertEquals(
        OptionalNotificationToggleDecision(requested = false, launchPermissionDialog = false),
        optionalNotificationToggleDecision(
            enabled = false,
            runtimePermissionRequired = true,
            permissionGranted = false,
        ),
    )
  }

  @Test
  fun connectionActionDrivesBothClickAndAccessibilityIncludingFailedStopRetry() {
    assertEquals(
        ConnectionControlAction.Connect,
        resolveConnectionControlAction(
            vpnAuthorization = VpnAuthorization.Granted,
            desiredVpnEnabled = false,
            errorOperation = null,
        ),
    )
    assertEquals(
        ConnectionControlAction.Disconnect,
        resolveConnectionControlAction(
            vpnAuthorization = VpnAuthorization.Granted,
            desiredVpnEnabled = true,
            errorOperation = null,
        ),
    )
    assertEquals(
        ConnectionControlAction.Disconnect,
        resolveConnectionControlAction(
            vpnAuthorization = VpnAuthorization.Granted,
            desiredVpnEnabled = false,
            errorOperation = WearAction.Disconnect,
        ),
    )
    assertEquals(
        ConnectionControlAction.OpenVpnSetup,
        resolveConnectionControlAction(
            vpnAuthorization = VpnAuthorization.AdbRequired,
            desiredVpnEnabled = false,
            errorOperation = null,
        ),
    )
    assertEquals(
        "Tailware connection. Disconnected. Connect",
        connectionControlContentDescription(
            controlLabel = "Tailware connection",
            statusLabel = "Disconnected",
            actionLabel = "Connect",
        ),
    )
  }

  @Test
  fun serialQueueDispatchesOnlyTheLatestPendingExitPreferenceAfterCurrentWrite() {
    val queue = LatestIntentSerialQueue<ExitPreferencesIntent>()
    val first =
        checkNotNull(
            queue
                .submit(ExitPreferencesIntent(exitNodeId = "node-a", allowLanAccess = false))
                .dispatchNow)
    assertNull(
        queue
            .submit(ExitPreferencesIntent(exitNodeId = "node-b", allowLanAccess = true))
            .dispatchNow)
    assertNull(
        queue
            .submit(ExitPreferencesIntent(exitNodeId = "node-c", allowLanAccess = false))
            .dispatchNow)
    assertEquals(
        ExitPreferencesIntent(exitNodeId = "node-c", allowLanAccess = false),
        queue.optimisticValue(),
    )

    val firstCompletion = queue.complete(first.generation, success = true)
    assertTrue(firstCompletion.accepted)
    assertFalse(firstCompletion.completedLatest)
    assertEquals(
        ExitPreferencesIntent(exitNodeId = "node-c", allowLanAccess = false),
        firstCompletion.next?.value,
    )

    val latest = checkNotNull(firstCompletion.next)
    val latestCompletion = queue.complete(latest.generation, success = true)
    assertTrue(latestCompletion.completedLatest)
    queue.observe(latest.value)
    assertNull(queue.optimisticValue())
  }

  @Test
  fun failedLatestSerialWriteDropsOptimisticValueWhileStaleCompletionIsIgnored() {
    val queue = LatestIntentSerialQueue<Boolean>()
    val dispatch = checkNotNull(queue.submit(true).dispatchNow)
    val stale = queue.complete(dispatch.generation + 1, success = true)
    assertFalse(stale.accepted)
    assertEquals(true, queue.optimisticValue())

    val failed = queue.complete(dispatch.generation, success = false)
    assertTrue(failed.completedLatest)
    assertNull(queue.optimisticValue())
  }

  @Test
  fun rapidLanOnThenOffFinishesWithTheLastRequestedValue() {
    val queue = LatestIntentSerialQueue<Boolean>()
    val turnOn = checkNotNull(queue.submit(true).dispatchNow)
    assertNull(queue.submit(false).dispatchNow)
    val afterTurnOn = queue.complete(turnOn.generation, success = true)
    val turnOff = checkNotNull(afterTurnOn.next)
    assertFalse(turnOff.value)
    assertTrue(queue.complete(turnOff.generation, success = true).completedLatest)
    queue.observe(false)
    assertNull(queue.optimisticValue())
  }

  @Test
  fun failedLatestExitWriteProjectsTheCurrentBackendSelectionBackIntoUi() {
    val optimisticState =
        WearUiState(
            selectedExitNodeId = "node-b",
            selectedExitNodeName = "Node B",
            activeExitNodeId = "node-b",
            activeExitNodeName = "Node B",
            allowLanAccess = true,
            exitNodes =
                listOf(
                    exitNode(id = "node-a", name = "Node A", selected = false, active = false),
                    exitNode(id = "node-b", name = "Node B", selected = true, active = true),
                ),
        )

    val restored =
        projectBackendExitPreferences(
            state = optimisticState,
            selectedExitNodeId = "node-a",
            activeExitNodeId = "node-a",
            allowLanAccess = false,
        )

    assertEquals("node-a", restored.selectedExitNodeId)
    assertEquals("Node A", restored.selectedExitNodeName)
    assertEquals("node-a", restored.activeExitNodeId)
    assertEquals("Node A", restored.activeExitNodeName)
    assertFalse(restored.allowLanAccess)
    assertTrue(restored.exitNodes.first { it.stableId == "node-a" }.selected)
    assertTrue(restored.exitNodes.first { it.stableId == "node-a" }.active)
    assertFalse(restored.exitNodes.first { it.stableId == "node-b" }.selected)
    assertFalse(restored.exitNodes.first { it.stableId == "node-b" }.active)
  }

  @Test
  fun logoutPresentationMakesBusyStateExplicit() {
    assertEquals(LogoutUiMode.Idle, logoutUiMode(confirmLogout = false, busyAction = null))
    assertEquals(LogoutUiMode.Confirm, logoutUiMode(confirmLogout = true, busyAction = null))
    assertEquals(
        LogoutUiMode.Busy,
        logoutUiMode(confirmLogout = false, busyAction = WearAction.Logout),
    )
  }

  private fun exitNode(
      id: String,
      name: String,
      selected: Boolean,
      active: Boolean,
  ): WearExitNode =
      WearExitNode(
          stableId = id,
          displayName = name,
          online = true,
          selected = selected,
          active = active,
          isMullvad = false,
          countryCode = null,
          country = null,
          city = null,
      )
}
