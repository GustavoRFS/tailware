// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal fun shouldRunPassiveContainmentMigration(action: String?): Boolean =
    action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED

/**
 * Boot/update handling is intentionally limited to the cheap preference-only P0 migration.
 *
 * It must never initialize App, query VPN authorization, enqueue work, or start a service. A new
 * process has no activation capability, so no persisted value can authorize restoration.
 */
class TailwareBootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (!shouldRunPassiveContainmentMigration(intent.action)) return
    TailwareVpnStateStore.ensureP0Containment(
        context.applicationContext,
        forcePassiveCheck = true,
    )
  }
}
