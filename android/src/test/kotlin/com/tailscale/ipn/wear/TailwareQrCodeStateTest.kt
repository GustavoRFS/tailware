// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wear

import com.tailscale.ipn.wear.ui.QrCodeState
import com.tailscale.ipn.wear.ui.completedQrCodeState
import com.tailscale.ipn.wear.ui.initialQrCodeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TailwareQrCodeStateTest {
  @Test
  fun aNewNonBlankQrRequestStartsLoadingInsteadOfRetainingAnOldBitmap() {
    assertEquals(
        QrCodeState.Loading,
        initialQrCodeState("https://login.tailscale.com/a/new-attempt"),
    )
  }

  @Test
  fun anEmptyLoginUrlHasAnExplicitEmptyState() {
    assertEquals(QrCodeState.Empty, initialQrCodeState(null))
    assertEquals(QrCodeState.Empty, initialQrCodeState("  "))
  }

  @Test
  fun generationFailureIsDistinctFromLoadingAndCanRenderARecoveryPath() {
    val failed = completedQrCodeState(bitmap = null)

    assertEquals(QrCodeState.Error, failed)
    assertNotEquals(QrCodeState.Loading, failed)
  }
}
