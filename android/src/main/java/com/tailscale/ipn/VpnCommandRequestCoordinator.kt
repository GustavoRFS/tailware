// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import java.util.concurrent.atomic.AtomicLong

/**
 * Orders user VPN commands without making the caller wait for persistence or service dispatch.
 *
 * A caller reserves its sequence on the gesture/broadcast thread and performs the potentially slow
 * work later. [runIfLatest] serializes the final ownership check with every other accepted user
 * command, so an older queued Disconnect can never persist OFF after a newer Connect has won.
 * Nothing executed under this monitor may call a blocking native API.
 */
internal class VpnCommandRequestCoordinator {
  private val nextSequence = AtomicLong()

  fun reserve(): Long = nextSequence.incrementAndGet()

  fun isLatest(sequence: Long): Boolean = sequence != 0L && nextSequence.get() == sequence

  @Synchronized
  fun <T> runIfLatest(
      sequence: Long,
      superseded: () -> T,
      operation: () -> T,
  ): T = if (isLatest(sequence)) operation() else superseded()
}
