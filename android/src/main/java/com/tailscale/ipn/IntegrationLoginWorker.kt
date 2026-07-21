// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Retained compatibility shell for previously persisted debug work.
 *
 * A Worker can run after process death and cannot prove a contemporaneous user gesture, so P0
 * containment permanently rejects this login path.
 */
class IntegrationLoginWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    return Result.failure()
  }

  companion object {
    const val TAG = "IntegrationLoginWorker"
    const val WORK_NAME = "ipn-integration-login"
    const val EXTRA_CONTROL_URL = "control_url"
    const val EXTRA_AUTH_KEY = "auth_key"
  }
}
