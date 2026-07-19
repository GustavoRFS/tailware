// Copyright (c) Tailware contributors
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

  fun desiredVpnEnabled(context: Context): Boolean =
      context
          .getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
          .getBoolean(KEY_DESIRED_VPN_ENABLED, false)

  @SuppressLint("ApplySharedPref")
  fun setDesiredVpnEnabled(context: Context, enabled: Boolean) {
    val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    if (preferences.getBoolean(KEY_DESIRED_VPN_ENABLED, false) == enabled) return
    // Commit synchronously: a stop followed immediately by a reboot must never restore the VPN.
    preferences.edit(commit = true) { putBoolean(KEY_DESIRED_VPN_ENABLED, enabled) }
  }
}

internal fun shouldRestoreVpnAfterBoot(
    desiredVpnEnabled: Boolean,
    vpnAuthorized: Boolean,
    ableToStartVpn: Boolean,
): Boolean = desiredVpnEnabled && vpnAuthorized && ableToStartVpn
