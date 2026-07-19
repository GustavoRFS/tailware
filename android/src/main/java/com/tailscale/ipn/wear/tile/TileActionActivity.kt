// Copyright (c) Tailware contributors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear.tile

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import com.tailscale.ipn.App
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier

/**
 * Invisible, single-purpose activity launched by the Tile.
 *
 * It never opens Android's VPN consent screen. A start is allowed only after the one-time VPN
 * authorization and account setup have both completed.
 */
class TileActionActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val app = App.get()
    val vpnAuthorized =
        try {
          VpnService.prepare(this) == null
        } catch (_: RuntimeException) {
          false
        }

    try {
      if (vpnAuthorized && app.isAbleToStartVPN()) {
        when {
          Notifier.state.value == Ipn.State.Stopping -> Unit
          app.getAppScopedViewModel().vpnActive.value -> app.stopVPN()
          else -> app.startVPN()
        }
      }
    } finally {
      TailwareTileUpdates.request(this)
      finish()
    }
  }
}
