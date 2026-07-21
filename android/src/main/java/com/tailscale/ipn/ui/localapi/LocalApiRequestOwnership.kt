// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.localapi

internal class LocalApiRequestSupersededException :
    IllegalStateException("The LocalAPI request no longer owns its activation capability.")

/**
 * Runs a sensitive LocalAPI call only after its caller-provided authorization check succeeds.
 *
 * The optional gate is synchronous by contract, but it must never hold a global capability or
 * desired-state monitor across a blocking native call. WantRunning uses the separate native
 * generation/cancellation fence instead.
 */
internal fun <T> executeAuthorizedLocalApiOperation(
    authorization: () -> Boolean,
    executionGate: ((() -> Unit) -> Unit),
    operation: () -> T,
): T {
  var result: Result<T>? = null
  executionGate {
    if (authorization()) {
      val operationResult = runCatching(operation)
      // A generation can be superseded while the cancellable native request is returning. Give
      // ownership loss precedence over its transport error so stale callbacks cannot run failure
      // settlement for the old command.
      result =
          if (authorization()) operationResult
          else Result.failure(LocalApiRequestSupersededException())
    }
  }
  return result?.getOrThrow() ?: throw LocalApiRequestSupersededException()
}
