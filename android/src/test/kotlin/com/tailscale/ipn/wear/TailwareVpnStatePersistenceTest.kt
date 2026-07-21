// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TailwareVpnStatePersistenceTest {
  @Test
  fun everySharedPreferencesAttemptUsesANewInMemoryWriteNonce() {
    val firstFailedAttempt = nextVpnStateWriteNonce(0L)
    val secondFailedAttempt = nextVpnStateWriteNonce(firstFailedAttempt)
    val laterSameStateRetry = nextVpnStateWriteNonce(secondFailedAttempt)

    assertEquals(1L, firstFailedAttempt)
    assertEquals(2L, secondFailedAttempt)
    assertEquals(3L, laterSameStateRetry)
  }

  @Test
  fun failedOnIsSupersededByOneNewOffGenerationAndNeverRetried() {
    val writes = mutableListOf<DesiredVpnState>()
    val attempted = DesiredVpnState(enabled = true, generation = 7L)
    val error = expectPersistenceFailure {
      persistDesiredVpnState(attempted) { candidate ->
        writes += candidate
        candidate.enabled.not()
      }
    }

    assertEquals(
        listOf(attempted, DesiredVpnState(enabled = false, generation = 8L)),
        writes,
    )
    assertEquals(attempted, error.attempted)
    assertEquals(DesiredVpnState(enabled = false, generation = 8L), error.localOff)
    assertTrue(error.cleanupPersisted)
  }

  @Test
  fun failedOffUsesANewGenerationSoSharedPreferencesMustRetryTheDiskWrite() {
    val writes = mutableListOf<DesiredVpnState>()
    val attempted = DesiredVpnState(enabled = false, generation = 11L)
    val result =
        persistDesiredVpnState(attempted) { candidate ->
          writes += candidate
          writes.size == 2
        }

    val successor = DesiredVpnState(enabled = false, generation = 12L)
    assertEquals(successor, result)
    assertEquals(listOf(attempted, successor), writes)
  }

  @Test
  fun repeatedOffFailureReportsTheSafeLocalOwner() {
    val attempted = DesiredVpnState(enabled = false, generation = 12L)
    val error = expectPersistenceFailure { persistDesiredVpnState(attempted) { false } }

    assertEquals(attempted, error.attempted)
    assertEquals(DesiredVpnState(enabled = false, generation = 13L), error.localOff)
    assertFalse(error.cleanupPersisted)
  }

  private fun expectPersistenceFailure(block: () -> Unit): VpnStatePersistenceException {
    try {
      block()
      fail("expected VpnStatePersistenceException")
    } catch (error: VpnStatePersistenceException) {
      return error
    }
    error("unreachable")
  }
}
