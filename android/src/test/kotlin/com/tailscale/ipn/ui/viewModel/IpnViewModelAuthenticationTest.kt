// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.AuthenticationEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpnViewModelAuthenticationTest {
  private fun event(
      state: Ipn.State? = null,
      loginFinished: Boolean = false,
      error: String? = null,
      bootstrap: Boolean = false,
  ) =
      AuthenticationEvent(
          sequence = 1L,
          epoch = 1L,
          loginFinished = loginFinished,
          errMessage = error,
          state = state,
          bootstrap = bootstrap,
      )

  @Test
  fun baseLoginRetainsEpochUntilAuthenticationIsTerminal() {
    assertFalse(authenticationEventCompletesEpoch(event(state = Ipn.State.NeedsLogin)))
    assertFalse(authenticationEventCompletesEpoch(event(state = Ipn.State.NeedsMachineAuth)))
    assertFalse(
        authenticationEventCompletesEpoch(event(state = Ipn.State.Running, bootstrap = true)))
    assertTrue(authenticationEventCompletesEpoch(event(loginFinished = true)))
    assertTrue(authenticationEventCompletesEpoch(event(error = "login failed")))
    assertTrue(authenticationEventCompletesEpoch(event(state = Ipn.State.Stopped)))
  }
}
