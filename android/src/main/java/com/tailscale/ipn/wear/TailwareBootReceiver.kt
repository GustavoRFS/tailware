// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.util.TSLog

/** Restores an explicitly enabled tunnel after boot or an in-place Tailware update. */
class TailwareBootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action
    if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED)
        return

    val pendingResult = goAsync()
    Thread(
            {
              try {
                restorePersistedVpn(context.applicationContext, action)
              } catch (error: Exception) {
                TSLog.e(TAG, "Unable to restore Tailware after $action: $error")
              } finally {
                pendingResult.finish()
              }
            },
            "TailwareVpnRestore",
        )
        .start()
  }

  private fun restorePersistedVpn(context: Context, action: String) {
    val desiredVpnState = TailwareVpnStateStore.desiredVpnState(context)
    val vpnAuthorized =
        runCatching { VpnService.prepare(context) == null }
            .onFailure { TSLog.w(TAG, "Unable to verify VPN authorization after $action: $it") }
            .getOrDefault(false)
    val app = UninitializedApp.get()
    val ableToStartVpn = app.isAbleToStartVPN()
    if (shouldRestoreVpnAfterBoot(desiredVpnState.enabled, vpnAuthorized, ableToStartVpn)) {
      TSLog.d(TAG, "Restoring the user-enabled Tailware tunnel after $action")
      app.restoreVPN(desiredVpnState)
    } else {
      TSLog.d(
          TAG,
          "Not restoring after $action: desired=${desiredVpnState.enabled} " +
              "authorized=$vpnAuthorized able=$ableToStartVpn",
      )
    }
  }

  private companion object {
    const val TAG = "TailwareBootReceiver"
  }
}
