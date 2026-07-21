// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import android.annotation.SuppressLint
import android.content.Context

internal class VpnStateOperationGate {
  private val monitor = Any()

  fun <T> run(block: () -> T): T = synchronized(monitor) { block() }
}

/**
 * Persists the connection state explicitly chosen by the user.
 *
 * This state is never startup authority. A matching process-local [TailwareActivationCapability] is
 * required before an ON value can be written, claimed, dispatched, or consumed by a service.
 */
object TailwareVpnStateStore {
  private const val PREFERENCES_FILE = "tailware_wear_preferences"
  private const val KEY_DESIRED_VPN_ENABLED = "desired_vpn_enabled"
  private const val KEY_DESIRED_VPN_GENERATION = "desired_vpn_generation"
  private const val KEY_DESIRED_VPN_WRITE_NONCE = "desired_vpn_write_nonce"
  /**
   * One-time upgrade fence for private.5 and older data.
   *
   * Version 1 clears the legacy persisted desired-ON/restore intent. The same startup operation
   * also clears any ON left by a later process because an in-memory activation capability cannot
   * survive process death.
   */
  internal const val P0_CONTAINMENT_MIGRATION_VERSION = 1
  internal const val KEY_P0_CONTAINMENT_MIGRATION_VERSION = "p0_containment_migration_version"
  private val operationGate = VpnStateOperationGate()
  @Volatile private var p0ContainmentReady = false
  @Volatile private var p0ContainmentWriteFailed = false

  /**
   * Performs only synchronous SharedPreferences cleanup; it never touches App, native code,
   * VpnService, WorkManager, or an Android service.
   */
  @SuppressLint("ApplySharedPref")
  fun ensureP0Containment(
      context: Context,
      forcePassiveCheck: Boolean = false,
  ): Boolean =
      operationGate.run {
        // Normal grants may reuse the result from this process's synchronous startup migration.
        // Passive system entry points force a fresh read so they can neutralize an ON value even
        // when delivered later in the same process; they still never gain engine authority.
        if (p0ContainmentReady && !forcePassiveCheck) return@run true
        val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
        val current = readDesiredVpnState(context)
        val recordedVersion = preferences.getInt(KEY_P0_CONTAINMENT_MIGRATION_VERSION, 0)
        val plan = p0ContainmentMigrationPlan(current, recordedVersion)
        if (!plan.writeRequired && !p0ContainmentWriteFailed) {
          p0ContainmentReady = true
          return@run true
        }

        val persisted =
            preferences
                .edit()
                .putBoolean(KEY_DESIRED_VPN_ENABLED, plan.safeState.enabled)
                .putLong(KEY_DESIRED_VPN_GENERATION, plan.safeState.generation)
                .putInt(
                    KEY_P0_CONTAINMENT_MIGRATION_VERSION,
                    P0_CONTAINMENT_MIGRATION_VERSION,
                )
                .putLong(
                    KEY_DESIRED_VPN_WRITE_NONCE,
                    nextVpnStateWriteNonce(preferences.getLong(KEY_DESIRED_VPN_WRITE_NONCE, 0L)))
                .commit()
        p0ContainmentReady = persisted
        p0ContainmentWriteFailed = !persisted
        persisted
      }

  fun desiredVpnEnabled(context: Context): Boolean = desiredVpnState(context).enabled

  fun desiredVpnState(context: Context): DesiredVpnState =
      operationGate.run { readDesiredVpnState(context) }

  private fun readDesiredVpnState(context: Context): DesiredVpnState {
    val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    return DesiredVpnState(
        enabled = preferences.getBoolean(KEY_DESIRED_VPN_ENABLED, false),
        generation = preferences.getLong(KEY_DESIRED_VPN_GENERATION, 0L),
    )
  }

  @SuppressLint("ApplySharedPref")
  fun setDesiredVpnEnabled(context: Context, enabled: Boolean): DesiredVpnState =
      operationGate.run {
        requireVpnActivationForEnabledWrite(enabled)
        val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
        val current = readDesiredVpnState(context)
        if (current.enabled == enabled) persistExactState(preferences, current)
        else persistNextState(preferences, enabled, current.generation)
      }

  /** Records an explicit user/system command, even when it repeats the currently desired value. */
  @SuppressLint("ApplySharedPref")
  fun requestVpnEnabled(context: Context, enabled: Boolean): DesiredVpnState =
      operationGate.run {
        requireVpnActivationForEnabledWrite(enabled)
        val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
        val generation = preferences.getLong(KEY_DESIRED_VPN_GENERATION, 0L)
        persistNextState(preferences, enabled, generation)
      }

  /**
   * Claims a new generation for a technical start while preserving the user's ON choice.
   *
   * Reading and claiming happen under the same lock. Therefore, a boot restore or technical restart
   * can never enqueue an old ON command after a newer OFF command has already won.
   */
  @SuppressLint("ApplySharedPref")
  fun claimEnabledCommandIfCurrent(
      context: Context,
      expected: DesiredVpnState,
  ): DesiredVpnState? =
      operationGate.run {
        requireVpnActivationForEnabledWrite(enabled = true)
        val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
        val current = readDesiredVpnState(context)
        if (current != expected || !current.enabled) null
        else persistNextState(preferences, enabled = true, current.generation)
      }

  /**
   * Changes the desired state only when no newer user command has replaced [expected].
   *
   * This is used to roll back a command that Android refused to dispatch without ever overwriting a
   * newer tap from the app or Tile.
   */
  @SuppressLint("ApplySharedPref")
  fun setDesiredVpnEnabledIfCurrent(
      context: Context,
      expected: DesiredVpnState,
      enabled: Boolean,
  ): DesiredVpnState? =
      operationGate.run {
        requireVpnActivationForEnabledWrite(enabled)
        val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
        val current = readDesiredVpnState(context)
        when {
          current != expected -> null
          current.enabled == enabled -> persistExactState(preferences, current)
          else -> persistNextState(preferences, enabled, current.generation)
        }
      }

  /**
   * Atomically rolls a terminally failed ON command forward to a new OFF generation.
   *
   * The exact failed generation must still own the desired state. A newer OFF or ON command is
   * therefore never overwritten by cleanup belonging to an older service attempt.
   */
  @SuppressLint("ApplySharedPref")
  fun rollbackFailedVpnStartIfCurrent(
      context: Context,
      failedCommand: DesiredVpnState,
  ): DesiredVpnState? =
      operationGate.run {
        val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
        val current = readDesiredVpnState(context)
        val rollback = terminalVpnFailureRollback(current, failedCommand) ?: return@run null
        persistExactState(preferences, rollback)
      }

  /**
   * Runs [block] while the same gate used by every desired-state writer is held.
   *
   * A failed notification OFF dispatch uses this to make its final owner check and
   * [android.content.Context.stopService] one indivisible operation relative to a newer ON command.
   */
  internal fun <T> withLockedDesiredVpnState(
      context: Context,
      block: (DesiredVpnState) -> T,
  ): T = operationGate.run { block(readDesiredVpnState(context)) }

  /** Runs a lifecycle decision under the writer gate without reading preferences first. */
  internal fun <T> withLockedVpnStateOperations(block: () -> T): T = operationGate.run(block)

  private fun persistNextState(
      preferences: android.content.SharedPreferences,
      enabled: Boolean,
      currentGeneration: Long,
  ): DesiredVpnState {
    val next = DesiredVpnState(enabled = enabled, generation = currentGeneration + 1L)
    return persistExactState(preferences, next)
  }

  @SuppressLint("UseKtx")
  private fun persistExactState(
      preferences: android.content.SharedPreferences,
      state: DesiredVpnState,
  ): DesiredVpnState =
      persistDesiredVpnState(state) { candidate ->
        // Commit synchronously: a stop followed immediately by a reboot must never restore the
        // VPN. Editor.commit updates this process's in-memory value even when its disk write
        // fails, which lets persistDesiredVpnState immediately force a safe local OFF successor.
        preferences
            .edit()
            .putBoolean(KEY_DESIRED_VPN_ENABLED, candidate.enabled)
            .putLong(KEY_DESIRED_VPN_GENERATION, candidate.generation)
            // Editor.commit() updates process memory before a failed disk write. Incrementing an
            // in-memory nonce on every attempt guarantees that an identical later state is still a
            // real SharedPreferences write rather than a successful no-op.
            .putLong(
                KEY_DESIRED_VPN_WRITE_NONCE,
                nextVpnStateWriteNonce(preferences.getLong(KEY_DESIRED_VPN_WRITE_NONCE, 0L)),
            )
            .commit()
      }

  private fun requireVpnActivationForEnabledWrite(enabled: Boolean) {
    check(!enabled || TailwareActivationCapability.hasVpnCapability()) {
      "Persisted desired VPN ON requires a current-process Connect capability."
    }
  }

  @Suppress("VisibleForTests")
  internal fun resetP0ContainmentForTests() {
    operationGate.run {
      p0ContainmentReady = false
      p0ContainmentWriteFailed = false
    }
  }
}

data class DesiredVpnState(val enabled: Boolean, val generation: Long)

internal data class P0ContainmentMigrationPlan(
    val safeState: DesiredVpnState,
    val writeRequired: Boolean,
)

/** Pure migration policy used by the passive startup path and JVM regression tests. */
internal fun p0ContainmentMigrationPlan(
    currentState: DesiredVpnState,
    recordedVersion: Int,
): P0ContainmentMigrationPlan {
  val writeRequired =
      recordedVersion < TailwareVpnStateStore.P0_CONTAINMENT_MIGRATION_VERSION ||
          currentState.enabled
  return P0ContainmentMigrationPlan(
      safeState =
          if (writeRequired) {
            DesiredVpnState(enabled = false, generation = currentState.generation + 1L)
          } else {
            currentState
          },
      writeRequired = writeRequired,
  )
}

internal fun nextVpnStateWriteNonce(current: Long): Long = current + 1L

class VpnStatePersistenceException(
    val attempted: DesiredVpnState,
    val localOff: DesiredVpnState,
    val cleanupPersisted: Boolean,
) :
    IllegalStateException(
        "Unable to persist desired VPN state $attempted; " +
            "forced local state to $localOff (persisted=$cleanupPersisted)")

/**
 * Persists one exact desired state and fails safe when Android reports a disk-write failure.
 *
 * A failed ON is never retried: it is immediately superseded by a new local OFF generation. A
 * failed OFF is superseded by a new OFF generation. SharedPreferences updates process memory before
 * reporting a disk failure, so retrying the identical values can become a successful no-op instead
 * of another disk write.
 */
internal fun persistDesiredVpnState(
    attempted: DesiredVpnState,
    commit: (DesiredVpnState) -> Boolean,
): DesiredVpnState {
  if (commit(attempted)) return attempted
  if (!attempted.enabled) {
    val localOff = DesiredVpnState(enabled = false, generation = attempted.generation + 1L)
    if (commit(localOff)) return localOff
    throw VpnStatePersistenceException(
        attempted = attempted,
        localOff = localOff,
        cleanupPersisted = false,
    )
  }

  val localOff = DesiredVpnState(enabled = false, generation = attempted.generation + 1L)
  val cleanupPersisted = commit(localOff)
  throw VpnStatePersistenceException(
      attempted = attempted,
      localOff = localOff,
      cleanupPersisted = cleanupPersisted,
  )
}

internal fun shouldRestoreVpnAfterBoot(
    desiredVpnEnabled: Boolean,
    vpnAuthorized: Boolean,
): Boolean = false

internal fun isCurrentVpnCommand(
    commandGeneration: Long,
    requestedEnabled: Boolean,
    currentState: DesiredVpnState,
): Boolean =
    commandGeneration == currentState.generation && requestedEnabled == currentState.enabled

internal fun shouldDispatchVpnRestart(desiredVpnEnabled: Boolean): Boolean = desiredVpnEnabled

internal fun shouldDispatchPersistedVpnCommand(
    command: DesiredVpnState,
    currentState: DesiredVpnState,
): Boolean = command == currentState

/**
 * Pure form of the terminal-start rollback rule, kept separate so every race edge can be unit
 * tested without Android storage.
 */
internal fun terminalVpnFailureRollback(
    currentState: DesiredVpnState,
    failedCommand: DesiredVpnState,
): DesiredVpnState? {
  if (!failedCommand.enabled || currentState != failedCommand) return null
  return DesiredVpnState(enabled = false, generation = currentState.generation + 1L)
}
