// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import com.tailscale.ipn.R
import com.tailscale.ipn.wear.localization.tailwareHealthResources
import org.junit.Assert.assertEquals
import org.junit.Test

class TailwareHealthLocalizationTest {
  @Test
  fun nativeHealthCodesMapToLocalizedCategoriesWithoutBackendCopy() {
    assertEquals(
        R.string.wear_health_relay_unavailable,
        tailwareHealthResources("no-derp-connection").title,
    )
    assertEquals(
        R.string.wear_health_dns_problem,
        tailwareHealthResources("dns-manager").title,
    )
    assertEquals(
        R.string.wear_health_clock_problem,
        tailwareHealthResources("clock-skew").title,
    )
    assertEquals(
        R.string.wear_health_account_problem,
        tailwareHealthResources("key-expired").title,
    )
    assertEquals(
        R.string.wear_health_account_problem,
        tailwareHealthResources("key-rotation-timeout").title,
    )
    assertEquals(
        R.string.wear_health_account_problem,
        tailwareHealthResources("auth-timeout").title,
    )
    assertEquals(
        R.string.wear_health_account_problem,
        tailwareHealthResources("login-timeout").title,
    )
    assertEquals(
        R.string.wear_health_problem,
        tailwareHealthResources("peer-connection-timeout").title,
    )
    assertEquals(
        R.string.wear_health_problem,
        tailwareHealthResources("future-unknown-warning").title,
    )
  }
}
