// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.notifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestNotifierWatchCoordinatorTest {
  private fun authenticationEvent(epoch: Long) =
      AuthenticationEvent(sequence = epoch, epoch = epoch, browseToURL = "https://login/$epoch")

  @Test
  fun replacementInvalidatesWatcherWhoseNativeStartupWaitFinishedLate() {
    val coordinator = LatestNotifierWatchCoordinator()
    val failedAttemptWatcher = coordinator.replace()
    val recoveredAttemptWatcher = coordinator.replace()

    assertFalse(coordinator.isCurrent(failedAttemptWatcher))
    assertTrue(coordinator.isCurrent(recoveredAttemptWatcher))
  }

  @Test
  fun stopInvalidatesCurrentWatcherBeforeItCanPublishItsManager() {
    val coordinator = LatestNotifierWatchCoordinator()
    val watcher = coordinator.replace()

    coordinator.replace()

    assertFalse(coordinator.isCurrent(watcher))
  }

  @Test
  fun obsoleteWatcherCannotPublishAfterRecoveryReplacement() {
    val coordinator = LatestNotifierWatchCoordinator()
    val failedAttemptWatcher = coordinator.replace()
    val recoveredAttemptWatcher = coordinator.replace()
    var published = "none"

    assertFalse(coordinator.runIfCurrent(failedAttemptWatcher) { published = "failed attempt" })
    assertTrue(
        coordinator.runIfCurrent(recoveredAttemptWatcher) { published = "recovered attempt" })
    assertEquals("recovered attempt", published)
  }

  @Test
  fun eventPublishedBeforeSameEpochRegistrationIsPreserved() {
    val coordinator = AuthenticationEpochCoordinator()
    val event = authenticationEvent(4L)

    assertTrue(coordinator.acceptNotification(event.epoch))
    coordinator.record(event)

    assertFalse(coordinator.register(event.epoch))
    assertEquals(event, coordinator.lastEvent)
  }

  @Test
  fun outOfOrderOlderRegistrationCannotClearANewerEvent() {
    val coordinator = AuthenticationEpochCoordinator()
    val newer = authenticationEvent(8L)
    assertTrue(coordinator.acceptNotification(newer.epoch))
    coordinator.record(newer)

    assertFalse(coordinator.register(7L))
    assertEquals(newer, coordinator.lastEvent)
    assertEquals(8L, coordinator.newestEpoch)
  }

  @Test
  fun retiredAndPreBoundaryCallbacksAreRejectedBeforeGlobalMutation() {
    val coordinator = AuthenticationEpochCoordinator()
    assertTrue(coordinator.acceptNotification(10L))
    assertTrue(coordinator.register(10L))
    assertFalse(coordinator.acceptNotification(0L))

    coordinator.retire(10L)
    assertFalse(coordinator.acceptNotification(10L))
    assertTrue(coordinator.acceptNotification(0L))

    assertTrue(coordinator.acceptNotification(11L))
    assertFalse(coordinator.acceptNotification(0L))
  }

  @Test
  fun backendAutoEpochCanBeAdoptedOrRetiredWithoutAnAndroidBegin() {
    val coordinator = AuthenticationEpochCoordinator()
    val url = authenticationEvent(12L)
    assertTrue(coordinator.acceptNotification(url.epoch))
    coordinator.record(url)
    assertFalse(coordinator.isAdopted(url.epoch))

    assertTrue(coordinator.claimUnadopted(url.epoch))
    assertTrue(coordinator.isAdopted(url.epoch))
    assertEquals(url, coordinator.lastEvent)

    // A second Android consumer must not hijack an epoch already owned by the first claimant.
    assertFalse(coordinator.claimUnadopted(url.epoch))

    coordinator.retire(url.epoch)
    assertFalse(coordinator.isAdopted(url.epoch))
  }

  @Test
  fun explicitlyRegisteredWorkerEpochCannotBeClaimedByTheWearObserver() {
    val coordinator = AuthenticationEpochCoordinator()
    val workerEpoch = 13L

    assertTrue(coordinator.register(workerEpoch))
    assertTrue(coordinator.isAdopted(workerEpoch))
    assertFalse(coordinator.claimUnadopted(workerEpoch))
    assertTrue(coordinator.hasNewerEpoch(workerEpoch - 1))
    assertFalse(coordinator.hasNewerEpoch(workerEpoch))
  }

  @Test
  fun retiredBackendAutoEpochCannotBeAdoptedByALateCollector() {
    val coordinator = AuthenticationEpochCoordinator()
    val event = authenticationEvent(14L)
    assertTrue(coordinator.acceptNotification(event.epoch))
    coordinator.record(event)
    coordinator.retire(event.epoch)

    assertFalse(coordinator.register(event.epoch))
    assertFalse(coordinator.isAdopted(event.epoch))
    assertEquals(null, coordinator.lastEvent)
  }

  @Test
  fun automaticRetirementBlocksLateAdoptionButDoesNotDiscardCallbacksUntilNativeSuccess() {
    val coordinator = AuthenticationEpochCoordinator()
    val event = authenticationEvent(15L)
    assertTrue(coordinator.acceptNotification(event.epoch))
    coordinator.record(event)

    assertTrue(coordinator.beginAutomaticRetirement(event.epoch))
    assertFalse(coordinator.register(event.epoch))
    assertTrue(coordinator.acceptNotification(event.epoch))
    assertFalse(coordinator.completeAutomaticRetirement(event.epoch, nativeRetired = false))
    assertEquals(event, coordinator.lastEvent)
    assertFalse(coordinator.register(event.epoch))
    assertTrue(coordinator.isAdopted(event.epoch))
  }

  @Test
  fun successfulAutomaticRetirementRejectsLaterCallbacks() {
    val coordinator = AuthenticationEpochCoordinator()
    val event = authenticationEvent(16L)
    assertTrue(coordinator.acceptNotification(event.epoch))
    coordinator.record(event)
    assertTrue(coordinator.beginAutomaticRetirement(event.epoch))

    assertTrue(coordinator.completeAutomaticRetirement(event.epoch, nativeRetired = true))
    assertFalse(coordinator.acceptNotification(event.epoch))
    assertEquals(null, coordinator.lastEvent)
  }

  @Test
  fun bootstrapStateCannotAutomaticallyRetireAnEpoch() {
    assertFalse(
        shouldAutomaticallyRetireAuthentication(
            authenticationEvent(13L)
                .copy(state = com.tailscale.ipn.ui.model.Ipn.State.Running, bootstrap = true)))
    assertTrue(
        shouldAutomaticallyRetireAuthentication(
            authenticationEvent(13L).copy(loginFinished = true)))
  }
}
