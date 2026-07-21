// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CompletableDeferred

internal suspend fun <T> awaitUseExitNodeResult(
    call: ((kotlin.Result<T>) -> Unit) -> Unit
): kotlin.Result<T> {
  val result = CompletableDeferred<kotlin.Result<T>>()
  call { result.complete(it) }
  return result.await()
}

class UseExitNodeWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {
  override suspend fun doWork(): Result {
    // Compatibility shell for previously persisted work. Process recreation cannot carry a
    // foreground activation capability, so background exit-node mutation is permanently rejected.
    return Result.failure()
  }

  companion object {
    const val EXIT_NODE_NAME = "EXIT_NODE_NAME"
    const val ALLOW_LAN_ACCESS = "ALLOW_LAN_ACCESS"
    const val ERROR_KEY = "error"
  }
}
