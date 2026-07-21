// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.localapi

import org.junit.Assert.assertTrue
import org.junit.Test

class LocalApiRequestOwnershipTest {
  @Test
  fun ownershipLossDuringNativeReturnWinsOverTheOldTransportFailure() {
    var authorized = true

    val failure =
        runCatching {
              executeAuthorizedLocalApiOperation(
                  authorization = { authorized },
                  executionGate = { operation -> operation() },
              ) {
                authorized = false
                error("old generation was cancelled")
              }
            }
            .exceptionOrNull()

    assertTrue(failure is LocalApiRequestSupersededException)
  }
}
