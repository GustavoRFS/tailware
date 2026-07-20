// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit

/**
 * Persists the connection state explicitly chosen by the user.
 *
 * Android's always-on VPN mode cannot represent Tailware's desired semantics because it may restart
 * a tunnel that the user deliberately switched off. This small mirror survives a device reboot and
 * lets [TailwareBootReceiver] restore only an explicitly enabled tunnel.
 */
object TailwareVpnStateStore {
  private const val PREFERENCES_FILE = "tailware_wear_preferences"
  private const val KEY_DESIRED_VPN_ENABLED = "desired_vpn_enabled"
  private const val KEY_DESIRED_VPN_GENERATION = "desired_vpn_generation"

  fun desiredVpnEnabled(context: Context): Boolean = desiredVpnState(context).enabled

  fun desiredVpnState(context: Context): DesiredVpnState {
    val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    return DesiredVpnState(
        enabled = preferences.getBoolean(KEY_DESIRED_VPN_ENABLED, false),
        generation = preferences.getLong(KEY_DESIRED_VPN_GENERATION, 0L),
    )
  }

  @SuppressLint("ApplySharedPref")
  @Synchronized
  fun setDesiredVpnEnabled(context: Context, enabled: Boolean): DesiredVpnState {
    val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    val current =
        DesiredVpnState(
            enabled = preferences.getBoolean(KEY_DESIRED_VPN_ENABLED, false),
            generation = preferences.getLong(KEY_DESIRED_VPN_GENERATION, 0L),
        )
    if (current.enabled == enabled) return current
    return persistNextState(preferences, enabled, current.generation)
  }

  /** Records an explicit user/system command, even when it repeats the currently desired value. */
  @SuppressLint("ApplySharedPref")
  @Synchronized
  fun requestVpnEnabled(context: Context, enabled: Boolean): DesiredVpnState {
    val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    val generation = preferences.getLong(KEY_DESIRED_VPN_GENERATION, 0L)
    return persistNextState(preferences, enabled, generation)
  }

  /**
   * Claims a new generation for a technical start while preserving the user's ON choice.
   *
   * Reading and claiming happen under the same lock. Therefore, a boot restore or technical restart
   * can never enqueue an old ON command after a newer OFF command has already won.
   */
  @SuppressLint("ApplySharedPref")
  @Synchronized
  fun claimEnabledCommandIfCurrent(
      context: Context,
      expected: DesiredVpnState,
  ): DesiredVpnState? {
    val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    val current =
        DesiredVpnState(
            enabled = preferences.getBoolean(KEY_DESIRED_VPN_ENABLED, false),
            generation = preferences.getLong(KEY_DESIRED_VPN_GENERATION, 0L),
        )
    if (current != expected || !current.enabled) return null
    return persistNextState(preferences, enabled = true, current.generation)
  }

  /**
   * Changes the desired state only when no newer user command has replaced [expected].
   *
   * This is used to roll back a command that Android refused to dispatch without ever overwriting a
   * newer tap from the app or Tile.
   */
  @SuppressLint("ApplySharedPref")
  @Synchronized
  fun setDesiredVpnEnabledIfCurrent(
      context: Context,
      expected: DesiredVpnState,
      enabled: Boolean,
  ): DesiredVpnState? {
    val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    val current =
        DesiredVpnState(
            enabled = preferences.getBoolean(KEY_DESIRED_VPN_ENABLED, false),
            generation = preferences.getLong(KEY_DESIRED_VPN_GENERATION, 0L),
        )
    if (current != expected) return null
    if (current.enabled == enabled) return current
    return persistNextState(preferences, enabled, current.generation)
  }

  /**
   * Atomically rolls a terminally failed ON command forward to a new OFF generation.
   *
   * The exact failed generation must still own the desired state. A newer OFF or ON command is
   * therefore never overwritten by cleanup belonging to an older service attempt.
   */
  @SuppressLint("ApplySharedPref")
  @Synchronized
  fun rollbackFailedVpnStartIfCurrent(
      context: Context,
      failedCommand: DesiredVpnState,
  ): DesiredVpnState? {
    val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    val current =
        DesiredVpnState(
            enabled = preferences.getBoolean(KEY_DESIRED_VPN_ENABLED, false),
            generation = preferences.getLong(KEY_DESIRED_VPN_GENERATION, 0L),
        )
    val rollback = terminalVpnFailureRollback(current, failedCommand) ?: return null
    preferences.edit(commit = true) {
      putBoolean(KEY_DESIRED_VPN_ENABLED, rollback.enabled)
      putLong(KEY_DESIRED_VPN_GENERATION, rollback.generation)
    }
    return rollback
  }

  private fun persistNextState(
      preferences: android.content.SharedPreferences,
      enabled: Boolean,
      currentGeneration: Long,
  ): DesiredVpnState {
    val next = DesiredVpnState(enabled = enabled, generation = currentGeneration + 1L)
    // Commit synchronously: a stop followed immediately by a reboot must never restore the VPN.
    preferences.edit(commit = true) {
      putBoolean(KEY_DESIRED_VPN_ENABLED, next.enabled)
      putLong(KEY_DESIRED_VPN_GENERATION, next.generation)
    }
    return next
  }
}

data class DesiredVpnState(val enabled: Boolean, val generation: Long)

internal fun shouldRestoreVpnAfterBoot(
    desiredVpnEnabled: Boolean,
    vpnAuthorized: Boolean,
    ableToStartVpn: Boolean,
): Boolean = desiredVpnEnabled && vpnAuthorized && ableToStartVpn

internal fun isCurrentVpnCommand(
    commandGeneration: Long,
    requestedEnabled: Boolean,
    currentState: DesiredVpnState,
): Boolean =
    commandGeneration == currentState.generation && requestedEnabled == currentState.enabled

internal fun shouldDispatchVpnRestart(desiredVpnEnabled: Boolean): Boolean = desiredVpnEnabled

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
