// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnAuthorizationTest {
  @Test
  fun preparedVpnIsGranted() {
    assertEquals(VpnAuthorization.Granted, vpnAuthorizationFromPrepared(vpnPrepared = true))
  }

  @Test
  fun unpreparedVpnRequiresAdb() {
    assertEquals(VpnAuthorization.AdbRequired, vpnAuthorizationFromPrepared(vpnPrepared = false))
  }

  @Test
  fun initialWearStateRequiresAdb() {
    assertEquals(VpnAuthorization.AdbRequired, WearUiState().vpnAuthorization)
  }
}
