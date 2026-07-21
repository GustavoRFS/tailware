// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

/**
 * Generation-fenced state machine for asynchronous native VPN teardown.
 *
 * The caller owns the actual timers, native calls, foreground notification, and service lifetime.
 * It must remove foreground protection or publish Disconnected only when a returned decision
 * explicitly permits that effect. A first timeout requests one force-close attempt. A hard timeout
 * reports a fail-closed failure while retaining protection. A later exact native acknowledgement is
 * still authoritative closure evidence and can safely complete the barrier.
 *
 * A newer Start does not erase an unresolved teardown. It is retained as a successor behind the old
 * generation's native-close barrier. The exact old acknowledgement either completes a terminal
 * stop, or releases the barrier to the successor without removing foreground protection or
 * transiently publishing Disconnected.
 */
internal class NativeVpnTeardownCoordinator {
  private var latestGeneration: Long? = null
  private var activeGeneration: Long? = null
  private var teardownGeneration: Long? = null
  private var successorGeneration: Long? = null
  private var phase = NativeVpnTeardownPhase.None

  /** Records a newly accepted start, queuing it behind any unresolved native-close barrier. */
  @Synchronized
  fun onStarted(startGeneration: Long): NativeVpnTeardownDecision {
    val latest = latestGeneration
    if (latest != null && startGeneration <= latest) {
      return ignored(startGeneration)
    }
    latestGeneration = startGeneration

    if (hasUnresolvedTeardown()) {
      successorGeneration = startGeneration
      return NativeVpnTeardownDecision(
          outcome = NativeVpnTeardownOutcome.SuccessorQueued,
          generation = startGeneration,
          teardownGeneration = teardownGeneration,
          successorGeneration = startGeneration,
      )
    }

    activeGeneration = startGeneration
    successorGeneration = null
    phase = NativeVpnTeardownPhase.Active
    return NativeVpnTeardownDecision(
        outcome = NativeVpnTeardownOutcome.StartAccepted,
        generation = startGeneration,
    )
  }

  /** Begins waiting for native closure of [requestedTeardownGeneration]. */
  @Synchronized
  fun beginTeardown(requestedTeardownGeneration: Long): NativeVpnTeardownDecision {
    if (hasUnresolvedTeardown()) {
      return ignored(requestedTeardownGeneration)
    }

    val latest = latestGeneration
    if (latest != null && requestedTeardownGeneration < latest) {
      return ignored(requestedTeardownGeneration)
    }
    if (latest == requestedTeardownGeneration &&
        (phase != NativeVpnTeardownPhase.Active ||
            activeGeneration != requestedTeardownGeneration)) {
      return ignored(requestedTeardownGeneration)
    }

    latestGeneration = requestedTeardownGeneration
    teardownGeneration = requestedTeardownGeneration
    successorGeneration = null
    phase = NativeVpnTeardownPhase.AwaitingNativeAcknowledgement
    return NativeVpnTeardownDecision(
        outcome = NativeVpnTeardownOutcome.AwaitingNativeAcknowledgement,
        generation = requestedTeardownGeneration,
        teardownGeneration = requestedTeardownGeneration,
    )
  }

  /** Accepts only the acknowledgement for the exact currently waiting teardown generation. */
  @Synchronized
  fun onNativeAcknowledged(acknowledgedGeneration: Long): NativeVpnTeardownDecision {
    if (teardownGeneration != acknowledgedGeneration ||
        (phase != NativeVpnTeardownPhase.AwaitingNativeAcknowledgement &&
            phase != NativeVpnTeardownPhase.ForceCloseRequested &&
            phase != NativeVpnTeardownPhase.FailedClosed)) {
      return ignored(acknowledgedGeneration)
    }

    val successor = successorGeneration
    teardownGeneration = null
    successorGeneration = null
    return if (successor == null) {
      activeGeneration = null
      phase = NativeVpnTeardownPhase.Acknowledged
      NativeVpnTeardownDecision(
          outcome = NativeVpnTeardownOutcome.Complete,
          generation = acknowledgedGeneration,
      )
    } else {
      activeGeneration = successor
      phase = NativeVpnTeardownPhase.Active
      NativeVpnTeardownDecision(
          outcome = NativeVpnTeardownOutcome.BarrierReleasedForSuccessor,
          generation = acknowledgedGeneration,
          successorGeneration = successor,
      )
    }
  }

  /** Requests force-close once while continuing to require foreground protection. */
  @Synchronized
  fun onForceCloseTimeout(timedOutGeneration: Long): NativeVpnTeardownDecision {
    if (teardownGeneration != timedOutGeneration ||
        phase != NativeVpnTeardownPhase.AwaitingNativeAcknowledgement) {
      return ignored(timedOutGeneration)
    }

    phase = NativeVpnTeardownPhase.ForceCloseRequested
    return NativeVpnTeardownDecision(
        outcome = NativeVpnTeardownOutcome.RequestForceClose,
        generation = timedOutGeneration,
        teardownGeneration = timedOutGeneration,
        successorGeneration = successorGeneration,
    )
  }

  /**
   * Terminates the exact unresolved teardown fail-closed.
   *
   * This outcome never authorizes service termination, a Disconnected publication, or foreground
   * removal. The barrier and any queued successor remain recorded. Only a later exact native
   * acknowledgement may provide the missing proof and complete or release the barrier.
   */
  @Synchronized
  fun onHardTimeout(timedOutGeneration: Long): NativeVpnTeardownDecision {
    if (teardownGeneration != timedOutGeneration ||
        (phase != NativeVpnTeardownPhase.AwaitingNativeAcknowledgement &&
            phase != NativeVpnTeardownPhase.ForceCloseRequested)) {
      return ignored(timedOutGeneration)
    }

    phase = NativeVpnTeardownPhase.FailedClosed
    return NativeVpnTeardownDecision(
        outcome = NativeVpnTeardownOutcome.HardTimeoutFailedClosed,
        generation = timedOutGeneration,
        teardownGeneration = timedOutGeneration,
        successorGeneration = successorGeneration,
    )
  }

  @Synchronized
  fun snapshot(): NativeVpnTeardownSnapshot =
      NativeVpnTeardownSnapshot(
          latestGeneration = latestGeneration,
          activeGeneration = activeGeneration,
          teardownGeneration = teardownGeneration,
          successorGeneration = successorGeneration,
          phase = phase,
          teardownProtectionRequired = hasUnresolvedTeardown(),
      )

  private fun hasUnresolvedTeardown(): Boolean =
      teardownGeneration != null &&
          (phase == NativeVpnTeardownPhase.AwaitingNativeAcknowledgement ||
              phase == NativeVpnTeardownPhase.ForceCloseRequested ||
              phase == NativeVpnTeardownPhase.FailedClosed)

  private fun ignored(eventGeneration: Long) =
      NativeVpnTeardownDecision(
          outcome = NativeVpnTeardownOutcome.Ignored,
          generation = eventGeneration,
          teardownGeneration = teardownGeneration,
          successorGeneration = successorGeneration,
      )
}

internal enum class NativeVpnTeardownPhase {
  None,
  Active,
  AwaitingNativeAcknowledgement,
  ForceCloseRequested,
  Acknowledged,
  FailedClosed,
}

internal enum class NativeVpnTeardownOutcome {
  Ignored,
  StartAccepted,
  SuccessorQueued,
  AwaitingNativeAcknowledgement,
  RequestForceClose,
  Complete,
  BarrierReleasedForSuccessor,
  HardTimeoutFailedClosed,
}

/** One-shot effects authorized by a single generation-tagged event. */
internal data class NativeVpnTeardownDecision(
    val outcome: NativeVpnTeardownOutcome,
    val generation: Long,
    val teardownGeneration: Long? = null,
    val successorGeneration: Long? = null,
) {
  val scheduleTimeouts: Boolean
    get() = outcome == NativeVpnTeardownOutcome.AwaitingNativeAcknowledgement

  val cancelTimeouts: Boolean
    get() =
        outcome == NativeVpnTeardownOutcome.Complete ||
            outcome == NativeVpnTeardownOutcome.BarrierReleasedForSuccessor ||
            outcome == NativeVpnTeardownOutcome.HardTimeoutFailedClosed

  val requestForceClose: Boolean
    get() = outcome == NativeVpnTeardownOutcome.RequestForceClose

  val continueWithSuccessor: Boolean
    get() = outcome == NativeVpnTeardownOutcome.BarrierReleasedForSuccessor

  val hardTimeoutFailedClosed: Boolean
    get() = outcome == NativeVpnTeardownOutcome.HardTimeoutFailedClosed

  val removeForegroundProtection: Boolean
    get() = outcome == NativeVpnTeardownOutcome.Complete

  val publishDisconnected: Boolean
    get() = outcome == NativeVpnTeardownOutcome.Complete
}

internal data class NativeVpnTeardownSnapshot(
    val latestGeneration: Long?,
    val activeGeneration: Long?,
    val teardownGeneration: Long?,
    val successorGeneration: Long?,
    val phase: NativeVpnTeardownPhase,
    val teardownProtectionRequired: Boolean,
)
