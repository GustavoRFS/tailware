// Copyright (c) Tailware contributors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.tailscale.ipn.App
import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.util.TSLog

/** Restores an explicitly enabled Tailware tunnel after the watch has finished booting. */
class TailwareBootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

    val desiredVpnState = TailwareVpnStateStore.desiredVpnState(context)
    val vpnAuthorized =
        runCatching { VpnService.prepare(context) == null }
            .onFailure { TSLog.w(TAG, "Unable to verify VPN authorization after boot: $it") }
            .getOrDefault(false)
    val ableToStartVpn = UninitializedApp.get().isAbleToStartVPN()

    if (shouldRestoreVpnAfterBoot(desiredVpnState.enabled, vpnAuthorized, ableToStartVpn)) {
      TSLog.d(TAG, "Restoring the user-enabled Tailware tunnel after boot")
      App.get().restoreVPN(desiredVpnState)
    } else {
      TSLog.d(
          TAG,
          "Not restoring after boot: desired=${desiredVpnState.enabled} " +
              "authorized=$vpnAuthorized able=$ableToStartVpn",
      )
    }
  }

  private companion object {
    const val TAG = "TailwareBootReceiver"
  }
}
