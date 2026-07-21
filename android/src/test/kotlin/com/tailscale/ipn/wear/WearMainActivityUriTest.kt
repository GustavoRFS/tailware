// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearMainActivityUriTest {
  @Test
  fun secureRemoteLinksWithAHostAreAccepted() {
    assertEquals(
        "https://login.tailscale.com/admin/device?next=%2Fwelcome",
        validateRemoteLoginUri("https://login.tailscale.com/admin/device?next=%2Fwelcome")
            .toString(),
    )
    assertEquals(
        "HTTPS://headscale.example.test:8443/register/node",
        validateRemoteLoginUri("HTTPS://headscale.example.test:8443/register/node").toString(),
    )
  }

  @Test
  fun insecureAndNonWebSchemesAreRejected() {
    listOf(
            "http://login.tailscale.com/device",
            "file:///sdcard/token",
            "intent://login/#Intent;scheme=https;end",
            "tailware://login/device",
        )
        .forEach(::assertRejected)
  }

  @Test
  fun hostlessAmbiguousAndCredentialBearingLinksAreRejected() {
    listOf(
            "",
            "https:/missing-host",
            "https:///missing-host",
            "//login.tailscale.com/device",
            "https://user:secret@login.tailscale.com/device",
            "not a URI",
        )
        .forEach(::assertRejected)
  }

  private fun assertRejected(value: String) {
    assertTrue(
        "Expected remote URI to be rejected: $value",
        runCatching { validateRemoteLoginUri(value) }.isFailure,
    )
  }
}
