// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnCommandRequestCoordinatorTest {
  @Test
  fun queuedSlowDisconnectIsSupersededBeforeItCanPersistAfterNewerConnect() {
    val coordinator = VpnCommandRequestCoordinator()
    val disconnectSequence = coordinator.reserve()
    val workerReady = CountDownLatch(1)
    val allowWorker = CountDownLatch(1)
    val disconnectPersisted = AtomicBoolean(false)
    val disconnectSuperseded = AtomicBoolean(false)

    val worker =
        thread(name = "queued-disconnect") {
          workerReady.countDown()
          assertTrue(allowWorker.await(5, TimeUnit.SECONDS))
          coordinator.runIfLatest(
              sequence = disconnectSequence,
              superseded = { disconnectSuperseded.set(true) },
              operation = { disconnectPersisted.set(true) },
          )
        }

    assertTrue(workerReady.await(5, TimeUnit.SECONDS))
    val connectSequence = coordinator.reserve()
    var connectPersisted = false
    coordinator.runIfLatest(
        sequence = connectSequence,
        superseded = { error("the newest Connect must not be superseded") },
        operation = { connectPersisted = true },
    )
    allowWorker.countDown()
    worker.join(5_000)

    assertFalse(worker.isAlive)
    assertTrue(connectPersisted)
    assertTrue(disconnectSuperseded.get())
    assertFalse(disconnectPersisted.get())
  }

  @Test
  fun newerConnectReservedDuringAcceptedDisconnectRunsLast() {
    val coordinator = VpnCommandRequestCoordinator()
    val effects = Collections.synchronizedList(mutableListOf<String>())
    val disconnectSequence = coordinator.reserve()
    val disconnectEntered = CountDownLatch(1)
    val allowDisconnectToFinish = CountDownLatch(1)

    val disconnect =
        thread(name = "accepted-disconnect") {
          coordinator.runIfLatest(
              sequence = disconnectSequence,
              superseded = { effects += "disconnect-superseded" },
              operation = {
                effects += "disconnect-off"
                disconnectEntered.countDown()
                assertTrue(allowDisconnectToFinish.await(5, TimeUnit.SECONDS))
              },
          )
        }

    assertTrue(disconnectEntered.await(5, TimeUnit.SECONDS))
    val connectSequence = coordinator.reserve()
    val connect =
        thread(name = "newer-connect") {
          coordinator.runIfLatest(
              sequence = connectSequence,
              superseded = { effects += "connect-superseded" },
              operation = { effects += "connect-on" },
          )
        }
    allowDisconnectToFinish.countDown()
    disconnect.join(5_000)
    connect.join(5_000)

    assertFalse(disconnect.isAlive)
    assertFalse(connect.isAlive)
    assertEquals(listOf("disconnect-off", "connect-on"), effects)
  }

  @Test
  fun rapidConnectDisconnectConnectExecutesOnlyLatestQueuedOwner() {
    val coordinator = VpnCommandRequestCoordinator()
    val firstConnect = coordinator.reserve()
    val disconnect = coordinator.reserve()
    val finalConnect = coordinator.reserve()
    val effects = mutableListOf<String>()

    coordinator.runIfLatest(firstConnect, { effects += "first-stale" }) { effects += "first-on" }
    coordinator.runIfLatest(disconnect, { effects += "disconnect-stale" }) { effects += "off" }
    coordinator.runIfLatest(finalConnect, { effects += "final-stale" }) { effects += "final-on" }

    assertEquals(listOf("first-stale", "disconnect-stale", "final-on"), effects)
  }

  @Test
  fun delayedLoginCleanupCannotWriteOffAfterNewerConnectInFinalSettlementWindow() {
    val coordinator = VpnCommandRequestCoordinator()
    val delayedLoginCleanup = coordinator.reserve()
    val connect = coordinator.reserve()
    val effects = mutableListOf<String>()

    coordinator.runIfLatest(
        sequence = connect,
        superseded = { effects += "connect-superseded" },
        operation = { effects += "connect-on" },
    )
    coordinator.runIfLatest(
        sequence = delayedLoginCleanup,
        superseded = { effects += "login-cleanup-superseded" },
        operation = { effects += "login-cleanup-off" },
    )

    assertEquals(listOf("connect-on", "login-cleanup-superseded"), effects)
  }
}
