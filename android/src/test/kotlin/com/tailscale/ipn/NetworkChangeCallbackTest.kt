// Copyright (c) Tailware contributors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkChangeCallbackTest {
  @Test
  fun noCandidateNetworkIsOffline() {
    assertEquals(
        UnderlyingNetworkStatus.Offline,
        classifyUnderlyingNetwork(hasCandidateNetwork = false, hasValidatedInternet = false),
    )
  }

  @Test
  fun unvalidatedCandidateIsLimited() {
    assertEquals(
        UnderlyingNetworkStatus.Limited,
        classifyUnderlyingNetwork(hasCandidateNetwork = true, hasValidatedInternet = false),
    )
  }

  @Test
  fun validatedCandidateIsOnline() {
    assertEquals(
        UnderlyingNetworkStatus.Online,
        classifyUnderlyingNetwork(hasCandidateNetwork = true, hasValidatedInternet = true),
    )
  }
}
