// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UseExitNodeWorkerTest {
  @Test
  fun callbackSuccessResumesWorker() = runTest {
    val result =
        awaitUseExitNodeResult<String> { callback -> callback(kotlin.Result.success("updated")) }

    assertEquals("updated", result.getOrThrow())
  }

  @Test
  fun callbackFailureResumesWorker() = runTest {
    val failure = IllegalStateException("edit failed")

    val result =
        awaitUseExitNodeResult<String> { callback -> callback(kotlin.Result.failure(failure)) }

    assertSame(failure, result.exceptionOrNull())
  }

  @Test
  fun cancellationDoesNotWaitForLateCallback() = runTest {
    lateinit var callback: (kotlin.Result<Unit>) -> Unit
    val worker = launch {
      awaitUseExitNodeResult<Unit> { registeredCallback -> callback = registeredCallback }
    }
    runCurrent()

    worker.cancelAndJoin()
    callback(kotlin.Result.success(Unit))

    assertTrue(worker.isCancelled)
  }
}
